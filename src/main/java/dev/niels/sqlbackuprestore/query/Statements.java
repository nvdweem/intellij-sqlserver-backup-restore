package dev.niels.sqlbackuprestore.query;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Every statement this plugin sends to the server, as a pure function of its inputs.
 * <p>
 * These used to be string literals inlined at their call sites, spread over the actions and the file dialog. Gathering
 * them here means the exact text that reaches the server can be run against a real SQL Server from a test
 * ({@code SqlServerStatementsIT}) instead of a hand-copied approximation of it - the one kind of mistake that no amount
 * of unit testing around the call sites would catch, because the SQL only fails once a server sees it.
 * <p>
 * Values are pasted in rather than bound: T-SQL takes neither a parameter where {@code BACKUP DATABASE} wants an
 * identifier nor one where {@code DISK =} wants a literal. {@link Sql} does the escaping that makes that safe.
 */
public final class Statements {
    /** Everything up to and including the last separator of a {@code physical_name}. */
    private static final String DIRECTORY_OF = """
            LEFT(physical_name, LEN(physical_name) - (CASE
                WHEN CHARINDEX('\\', REVERSE(physical_name)) > CHARINDEX('/', REVERSE(physical_name))
                THEN CHARINDEX('\\', REVERSE(physical_name))
                ELSE CHARINDEX('/', REVERSE(physical_name))
            END) + 1)""";

    /**
     * Where to put restored data files: the directory that already holds most of this server's database files. The
     * server may well be running on Linux, hence the two separators in {@link #DIRECTORY_OF}.
     */
    public static final String DEFAULT_DATA_DIRECTORY = """
            SELECT TOP 1 %1$s AS path, COUNT(*)
            FROM sys.master_files mf
            INNER JOIN sys.[databases] d ON mf.[database_id] = d.[database_id]
            GROUP BY %1$s
            ORDER BY COUNT(*) DESC;""".formatted(DIRECTORY_OF);

    /** EditionID is documented as a bigint but comes back as a String, so it is cast to be sure of what arrives. */
    public static final String EDITION_ID = "SELECT cast(SERVERPROPERTY('EditionID') as varchar(20)) AS edition";

    public static final String CURRENT_SESSION_ID = "SELECT @@SPID AS spid";

    /** The staged blob's length, measured by {@link #stageForDownload}. */
    public static final String STAGED_LENGTH = "SELECT fs FROM #filedownload";

    public static final String DROP_STAGED_DOWNLOAD = "IF OBJECT_ID('tempdb..#filedownload') IS NOT NULL DROP TABLE #filedownload;";

    private static final String DEFAULT_BACKUP_DIRECTORY = """
            declare @BackupDirectory nvarchar(512)
            if 1=isnull(cast(SERVERPROPERTY('IsLocalDB') as bit), 0)
                select @BackupDirectory=cast(SERVERPROPERTY('instancedefaultdatapath') as nvarchar(512))
            else
                exec master.dbo.xp_instance_regread N'HKEY_LOCAL_MACHINE', N'SOFTWARE\\Microsoft\\MSSQLServer\\MSSQLServer', N'BackupDirectory', @BackupDirectory OUTPUT

            select @BackupDirectory as directory""";

    private static final String DRIVES = """
            create table #fixdrv (Name sysname NOT NULL, Size int NOT NULL, Type sysname NULL)
            if exists (select 1 from sys.all_objects where name = 'dm_os_enumerate_fixed_drives' and type = 'V' and is_ms_shipped = 1)
            begin
                insert #fixdrv select fixed_drive_path, free_space_in_bytes/(1024*1024), drive_type_desc from sys.dm_os_enumerate_fixed_drives
            end
            else
            begin
                insert #fixdrv (Name, Size) EXECUTE master.dbo.xp_fixeddrives
                update #fixdrv set Name = Name + ':/', Type = 'Fixed' where Type IS NULL
            end
            select * from #fixdrv;
            drop table #fixdrv;""";

    /**
     * Lists one directory. Comes from SQL Server Management Studio, which is why it goes out of its way to work on
     * servers that predate the {@code sys.dm_os_*} views and falls back to the {@code xp_} procedures.
     */
    private static final String PATH_CHILDREN = """
            declare @Path nvarchar(255)
            select @Path = N'%s'

            create table #filetmpfin (Name nvarchar(255) NOT NULL, IsFile int NULL, FullName nvarchar(300) not NULL, SizeInBytes bigint NULL, LastWriteTime datetime NULL)
            if exists (select 1 from sys.all_objects where name = 'dm_os_enumerate_filesystem' and type = 'IF' and is_ms_shipped = 1)
            begin
                insert #filetmpfin
                    select file_or_directory_name, 1 - is_directory, full_filesystem_path, size_in_bytes, last_write_time
                    from sys.dm_os_enumerate_filesystem(@Path, '*')
                    where [level] = 0
            end
            else
            begin
                if (right(@Path, 1) = '\\')
                    select @Path = substring(@Path, 1, len(@Path) - charindex('\\', reverse(@Path)))

                -- xp_dirtree reports neither size nor date, so those columns stay null on servers old enough to need it.
                create table #filetmp (Name nvarchar(255) NOT NULL, depth int NOT NULL, IsFile bit NULL)
                insert #filetmp EXECUTE master.dbo.xp_dirtree @Path, 1, 1
                insert #filetmpfin select Name, IsFile, @Path + '\\' + Name, NULL, NULL from #filetmp
                drop table #filetmp
            end

            SELECT Name, IsFile, FullName, SizeInBytes, LastWriteTime FROM #filetmpfin ORDER BY IsFile ASC, Name ASC
            drop table #filetmpfin""";

    private static final String SESSIONS_ON = """
            SELECT
                [Session ID]    = s.session_id,
                [User Process]  = CONVERT(CHAR(1), s.is_user_process),
                [Login]         = s.login_name,
                [Application]   = ISNULL(s.program_name, N''),
                [Open Transactions] = ISNULL(r.open_transaction_count,0),
                [Last Request Start Time] = s.last_request_start_time,
                [Host Name]     = ISNULL(s.host_name, N''),
                [Net Address]   = ISNULL(c.client_net_address, N'')
            FROM sys.dm_exec_sessions s
            LEFT OUTER JOIN sys.dm_exec_connections c ON (s.session_id = c.session_id)
            LEFT OUTER JOIN sys.dm_exec_requests r ON (s.session_id = r.session_id)
            WHERE s.database_id = DB_ID(N'%s')
              AND s.session_id <> @@SPID
            ORDER BY s.session_id;""";

    private Statements() {
    }

    // --- backup --------------------------------------------------------------------------------------------------

    /**
     * {@code COPY_ONLY} so that taking a backup from the IDE does not disturb whatever backup chain the server is
     * already maintaining.
     */
    public static @NotNull String backup(@NotNull String database, @NotNull String path, boolean compress) {
        return "BACKUP DATABASE " + Sql.quoted(database) + " TO  DISK = " + diskOf(path)
                + " WITH COPY_ONLY, NOFORMAT, INIT, SKIP, NOREWIND, NOUNLOAD" + (compress ? ", COMPRESSION" : "") + ", STATS = 10";
    }

    /**
     * Size of the database itself, not of the backup file. {@code @oneresultset} keeps it to a single row, which is the
     * only shape {@link Client#getSingle} can read.
     */
    public static @NotNull String spaceUsed(@NotNull String database) {
        return "USE " + Sql.quoted(database) + " exec sp_spaceused @oneresultset = 1";
    }

    // --- restore -------------------------------------------------------------------------------------------------

    /** The backup's own metadata: type, LSNs, when and where it was taken. */
    public static @NotNull String headerOnly(@NotNull String path) {
        return "RESTORE HEADERONLY FROM DISK = " + diskOf(path) + " WITH NOUNLOAD;";
    }

    /** What is inside the backup, so that each file can be restored WITH MOVE to a path that exists here. */
    public static @NotNull String fileListOnly(@NotNull String path) {
        return "RESTORE FILELISTONLY FROM DISK = " + diskOf(path) + ";";
    }

    /**
     * @param leaveRestoring when a differential still has to be applied on top, which needs the database left in the
     *                       restoring state rather than brought online.
     */
    public static @NotNull String restoreFull(@NotNull String database, @NotNull String path, @NotNull List<RestoreFile> files, boolean leaveRestoring) {
        var options = new ArrayList<String>();
        options.add("file = 1");
        files.forEach(file -> options.add("MOVE N'%s' TO N'%s'"
                .formatted(Sql.literal(file.getLogicalName()), Sql.literal(Objects.toString(file.getRestoreAs(), "")))));
        if (leaveRestoring) {
            options.add("NORECOVERY");
        }
        options.add("NOUNLOAD");
        options.add("STATS = 5");
        options.add("REPLACE");

        // Built as a list rather than a format string with an optional clause pasted into it, which is how NORECOVERY
        // ended up followed by two spaces and an empty option position by a stray comma.
        return "RESTORE DATABASE %s FROM DISK = %s WITH %s"
                .formatted(Sql.quoted(database), diskOf(path), String.join(", ", options));
    }

    /**
     * A differential goes on top of the files the full restore already put in place and names none of them, so it needs
     * neither MOVE nor REPLACE.
     */
    public static @NotNull String restoreDifferential(@NotNull String database, @NotNull String path) {
        return "RESTORE DATABASE %s FROM DISK = %s WITH file = 1, NOUNLOAD, STATS = 5"
                .formatted(Sql.quoted(database), diskOf(path));
    }

    // --- sessions ------------------------------------------------------------------------------------------------

    /** Every session on {@code database} other than the one asking, which must survive to run the restore. */
    public static @NotNull String sessionsOn(@NotNull String database) {
        return SESSIONS_ON.formatted(Sql.literal(database));
    }

    public static @NotNull String kill(int sessionId) {
        return "KILL " + sessionId + ";";
    }


    // --- download ------------------------------------------------------------------------------------------------

    /**
     * Reads the server's own backup file into a temp table so the download can pull it out in chunks, without either
     * end ever holding the whole file in memory.
     */
    public static @NotNull String stageForDownload(@NotNull String path, boolean compress) {
        return "SELECT 1 as id, CAST(0 as bigint) AS fs, %s AS f into #filedownload FROM OPENROWSET(BULK N'%s', SINGLE_BLOB) x;"
                .formatted(compress ? "COMPRESS(BulkColumn)" : "BulkColumn", Sql.literal(path));
    }

    /** DATALENGTH, not LEN: LEN is a character function and would stop at the first zero byte. */
    public static @NotNull String measureStagedLength() {
        return "update #filedownload set fs = DATALENGTH(f) where id = 1;";
    }

    /**
     * @param offset 1-based, as {@code SUBSTRING} is in T-SQL. Passing a 0-based offset made the first chunk a byte
     *               short and every later one inherit the shift, so a file whose size was an exact multiple of the
     *               chunk size lost its last byte.
     */
    public static @NotNull String downloadChunk(long offset, long length) {
        return "select substring(f, %s, %s) AS part from #filedownload".formatted(offset, length);
    }

    // --- file dialog ---------------------------------------------------------------------------------------------

    public static @NotNull String defaultBackupDirectory() {
        return DEFAULT_BACKUP_DIRECTORY;
    }

    public static @NotNull String drives() {
        return DRIVES;
    }

    /**
     * @param path the directory to list, unescaped.
     */
    public static @NotNull String pathChildren(@NotNull String path) {
        return PATH_CHILDREN.formatted(Sql.literal(path));
    }

    private static @NotNull String diskOf(@NotNull String path) {
        return "N'" + Sql.literal(path) + "'";
    }
}
