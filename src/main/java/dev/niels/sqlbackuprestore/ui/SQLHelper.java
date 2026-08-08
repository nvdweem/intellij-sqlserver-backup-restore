package dev.niels.sqlbackuprestore.ui;

import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.query.Sql;
import lombok.SneakyThrows;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The queries behind the remote file picker. They come from SQL Server Management Studio, which is why they go out of
 * their way to work on servers that predate the {@code sys.dm_os_*} views and fall back to the {@code xp_} procedures.
 */
public final class SQLHelper {
    /** A registry read or a directory listing on a busy server can take a moment; two seconds was optimistic. */
    private static final int TIMEOUT_SECONDS = 10;

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
     * Lists one directory. {@code %s} is the (escaped) path to list.
     */
    private static final String PATH_CHILDREN = """
            declare @Path nvarchar(255)
            select @Path = N'%s'

            create table #filetmpfin (Name nvarchar(255) NOT NULL, IsFile int NULL, FullName nvarchar(300) not NULL)
            if exists (select 1 from sys.all_objects where name = 'dm_os_enumerate_filesystem' and type = 'IF' and is_ms_shipped = 1)
            begin
                insert #filetmpfin
                    select file_or_directory_name, 1 - is_directory, full_filesystem_path
                    from sys.dm_os_enumerate_filesystem(@Path, '*')
                    where [level] = 0
            end
            else
            begin
                if (right(@Path, 1) = '\\')
                    select @Path = substring(@Path, 1, len(@Path) - charindex('\\', reverse(@Path)))

                create table #filetmp (Name nvarchar(255) NOT NULL, depth int NOT NULL, IsFile bit NULL)
                insert #filetmp EXECUTE master.dbo.xp_dirtree @Path, 1, 1
                insert #filetmpfin select Name, IsFile, @Path + '\\' + Name from #filetmp
                drop table #filetmp
            end

            SELECT Name, IsFile, FullName FROM #filetmpfin ORDER BY IsFile ASC, Name ASC
            drop table #filetmpfin""";

    private SQLHelper() {
    }

    @SneakyThrows
    public static String getDefaultBackupDirectory(Client connection) {
        return connection.getSingle(DEFAULT_BACKUP_DIRECTORY, "directory", String.class).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @SneakyThrows
    public static List<Map<String, Object>> getDrives(Client connection) {
        return connection.getResult(DRIVES).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @SneakyThrows
    public static List<Map<String, Object>> getSQLPathChildren(Client connection, String path) {
        return connection.getResult(pathChildrenQuery(path)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Split out so the rendering can be tested: a stray {@code %} in the query text would only fail here, at the point
     * a user browses a directory.
     */
    static String pathChildrenQuery(String path) {
        return PATH_CHILDREN.formatted(Sql.literal(path));
    }
}
