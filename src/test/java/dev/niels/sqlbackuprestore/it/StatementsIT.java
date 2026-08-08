package dev.niels.sqlbackuprestore.it;

import dev.niels.sqlbackuprestore.action.Backup;
import dev.niels.sqlbackuprestore.query.RemoteFileWithMeta.BackupType;
import dev.niels.sqlbackuprestore.query.RestoreFile;
import dev.niels.sqlbackuprestore.query.Sql;
import dev.niels.sqlbackuprestore.query.Statements;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backup and restore statements against a real server. A statement can be perfectly well-formed, escape everything
 * correctly and still be wrong - {@code WITH MOVE} pointing somewhere the server cannot write, {@code NORECOVERY} on the
 * wrong half of a chain, a differential paired with the wrong full backup. None of that shows up until a server runs it.
 */
class StatementsIT extends SqlServerTestBase {
    private String backUp(String database, String fileName) throws SQLException {
        var path = backupPath(fileName);
        execute(Statements.backup(database, path, false));
        return path;
    }

    /**
     * A full backup that a differential can be based on, which the plugin's own {@link Statements#backup} deliberately
     * is not: it backs up {@code COPY_ONLY} so as not to disturb whatever backup chain the server is maintaining, and
     * SQL Server will not base a differential on a copy-only backup. The differentials this plugin restores therefore
     * always come from the server's real backup schedule, which is what these two statements stand in for.
     */
    private String backUpAsChainBase(String database, String fileName) throws SQLException {
        var path = backupPath(fileName);
        execute("BACKUP DATABASE " + Sql.quoted(database) + " TO DISK = N'" + Sql.literal(path) + "' WITH INIT;");
        return path;
    }

    private String backUpDifferential(String database, String fileName) throws SQLException {
        var path = backupPath(fileName);
        execute("BACKUP DATABASE " + Sql.quoted(database) + " TO DISK = N'" + Sql.literal(path) + "' WITH DIFFERENTIAL, INIT;");
        return path;
    }

    /**
     * Restores {@code path} as {@code target}, doing what the plugin does: read the file list, find the data directory,
     * point each file at it, restore.
     */
    private void restoreFull(String path, String target, boolean leaveRestoring) throws SQLException {
        var files = RestoreFile.from(query(Statements.fileListOnly(path)));
        assertFalse(files.isEmpty(), "FILELISTONLY reported no files in " + path);

        var directory = Objects.toString(SqlServer.single(connection, Statements.DEFAULT_DATA_DIRECTORY, "path"), "");
        assertFalse(directory.isBlank(), "No default data directory found");

        RestoreFile.assignDefaultTargets(files, directory, target);
        execute(Statements.restoreFull(target, path, files, leaveRestoring));
    }

    @Test
    void backsUpAndRestoresADatabase() throws SQLException {
        var source = createDatabase(PREFIX + "roundtrip", 7);
        var path = backUp(source, PREFIX + "roundtrip.bak");

        // Into a new name, which is what makes it prove the MOVE clauses work: restoring over the original would
        // succeed even if every file were pointed back at its own existing path.
        var target = willCreate(PREFIX + "roundtrip_copy");
        restoreFull(path, target, false);

        assertEquals(7, rowCount(target));
        assertEquals("ONLINE", state(target));
    }

    @Test
    void movesEveryFileIntoTheDataDirectoryUnderTheNewName() throws SQLException {
        var source = createDatabase(PREFIX + "moved", 3);
        var path = backUp(source, PREFIX + "moved.bak");

        var target = willCreate(PREFIX + "moved_copy");
        restoreFull(path, target, false);

        var files = databaseFiles(target);
        assertEquals(2, files.size(), files.toString());
        assertTrue(files.stream().anyMatch(f -> f.endsWith(target + ".mdf")), files.toString());
        assertTrue(files.stream().anyMatch(f -> f.endsWith(target + "_log.ldf")), files.toString());
    }

    @Test
    void restoresADifferentialOnTopOfItsFullBackup() throws SQLException {
        var source = createDatabase(PREFIX + "diff", 4);
        var full = backUpAsChainBase(source, PREFIX + "diff_full.bak");

        insertRows(source, 5, 6);
        var differential = backUpDifferential(source, PREFIX + "diff_diff.bak");

        var target = willCreate(PREFIX + "diff_copy");
        // NORECOVERY, so the database stays mid-restore and can take the differential.
        restoreFull(full, target, true);
        assertEquals("RESTORING", state(target));

        execute(Statements.restoreDifferential(target, differential));

        assertEquals("ONLINE", state(target));
        assertEquals(10, rowCount(target), "the differential's rows are missing");
    }

    @Test
    void reportsBackupTypeAndPairingLsnsInTheHeader() throws SQLException {
        var source = createDatabase(PREFIX + "header", 2);
        var full = backUpAsChainBase(source, PREFIX + "header_full.bak");
        var differential = backUpDifferential(source, PREFIX + "header_diff.bak");

        var fullHeader = query(Statements.headerOnly(full)).getFirst();
        var differentialHeader = query(Statements.headerOnly(differential)).getFirst();

        // The numbers the plugin's BackupType and isDifferentialOf are built on. If SQL Server ever renamed these
        // columns or renumbered the types, the picker would quietly decide nothing is restorable.
        assertEquals(BackupType.FULL, BackupType.from(((Number) fullHeader.get("BackupType")).intValue()));
        assertEquals(BackupType.DIFFERENTIAL, BackupType.from(((Number) differentialHeader.get("BackupType")).intValue()));
        assertEquals(lsn(fullHeader, "FirstLSN"), lsn(differentialHeader, "DatabaseBackupLSN"),
                "a differential is matched to its full backup on exactly this equality");
    }

    @Test
    void reportsTheFilesInsideABackup() throws SQLException {
        var source = createDatabase(PREFIX + "filelist", 1);
        var path = backUp(source, PREFIX + "filelist.bak");

        var files = RestoreFile.from(query(Statements.fileListOnly(path)));

        assertEquals(2, files.size(), "expected one data and one log file");
        assertEquals(1, files.stream().filter(RestoreFile::isLog).count(), "the log file was not recognised");
        files.forEach(file -> {
            assertFalse(file.getLogicalName().isBlank(), "logical name missing");
            assertFalse(file.getPhysicalName().isBlank(), "physical name missing");
        });
    }

    @Test
    void handlesDatabaseNamesAndPathsThatNeedEscaping() throws SQLException {
        // A quote breaks the DISK literal, a bracket breaks the identifier. Both in one name.
        var source = createDatabase(PREFIX + "O'Brien]x", 5);
        var path = backUp(source, PREFIX + "O'Brien]x.bak");

        var target = willCreate(PREFIX + "O'Brien]x_copy");
        restoreFull(path, target, false);

        assertEquals(5, rowCount(target));
    }

    @Test
    void agreesWithTheServerAboutBackupCompressionSupport() throws SQLException {
        var edition = Objects.toString(SqlServer.single(connection, Statements.EDITION_ID, "edition"), "");
        var source = createDatabase(PREFIX + "compress", 3);
        var path = backupPath(PREFIX + "compress.bak");

        var accepted = true;
        try {
            execute(Statements.backup(source, path, true));
        } catch (SQLException e) {
            accepted = false;
        }

        // The edition list is hardcoded in Backup; if it disagrees with the server, users of that edition get a
        // failing backup rather than an uncompressed one.
        assertEquals(Backup.supportsCompression(edition), accepted,
                "edition " + edition + (accepted ? " accepted" : " rejected") + " COMPRESSION");
    }

    @Test
    void reportsADatabaseSizeThatTheBackupActionCanParse() throws SQLException {
        var source = createDatabase(PREFIX + "size", 10);

        var reserved = Objects.toString(SqlServer.single(connection, Statements.spaceUsed(source), "reserved"), "");

        // Backup strips " KB" and parses the rest as a long; anything else there is a NumberFormatException.
        assertTrue(reserved.endsWith(" KB"), "unexpected format: '" + reserved + "'");
        assertTrue(Long.parseLong(reserved.substring(0, reserved.length() - 3).trim()) > 0);
    }

    @Test
    void findsAndKillsOtherSessionsOnADatabaseButNotItsOwn() throws Exception {
        var database = createDatabase(PREFIX + "sessions", 1);

        try (var other = SqlServer.connect()) {
            SqlServer.execute(other, "USE " + Sql.quoted(database) + "; SELECT COUNT(*) FROM dbo.payload;");
            var otherSpid = ((Number) SqlServer.single(other, Statements.CURRENT_SESSION_ID, "spid")).intValue();
            var ownSpid = ((Number) SqlServer.single(connection, Statements.CURRENT_SESSION_ID, "spid")).intValue();

            var sessions = query(Statements.sessionsOn(database));
            var found = sessions.stream().map(row -> ((Number) row.get("Session ID")).intValue()).toList();

            assertTrue(found.contains(otherSpid), "the other session was not found: " + found);
            assertFalse(found.contains(ownSpid), "our own session must be left alone or the restore kills itself");

            execute(Statements.kill(otherSpid));

            assertFalse(query(Statements.sessionsOn(database)).stream()
                    .anyMatch(row -> ((Number) row.get("Session ID")).intValue() == otherSpid), "the session survived KILL");
        }
    }

    @Test
    void findsADataDirectoryThatTheServerCanActuallyWriteTo() throws SQLException {
        var directory = Objects.toString(SqlServer.single(connection, Statements.DEFAULT_DATA_DIRECTORY, "path"), "");

        assertFalse(directory.isBlank());
        // The query strips the filename off a physical_name, so what is left must end in a separator.
        assertTrue(directory.endsWith("\\") || directory.endsWith("/"), "not a directory: " + directory);
    }

    private String state(String database) throws SQLException {
        return Objects.toString(SqlServer.single(connection,
                "SELECT state_desc FROM sys.databases WHERE name = N'" + Sql.literal(database) + "';",
                "state_desc"), "");
    }

    private static BigDecimal lsn(Map<String, Object> header, String column) {
        var value = header.get(column);
        assertNotNull(value, column + " missing from RESTORE HEADERONLY");
        return new BigDecimal(value.toString());
    }

    @Test
    void restoresABackupThatContainsSeveralDataFilesWithoutOverwritingEitherOfThem() throws SQLException {
        var source = createDatabase(PREFIX + "multifile", 2);
        // A second data file, so the restore has two files wanting the same default .mdf name.
        execute("ALTER DATABASE " + Sql.quoted(source)
                + " ADD FILE (NAME = N'extra', FILENAME = N'" + backupPath(PREFIX + "multifile_extra.ndf") + "', SIZE = 8MB);");
        var path = backUp(source, PREFIX + "multifile.bak");

        var target = willCreate(PREFIX + "multifile_copy");
        restoreFull(path, target, false);

        var files = databaseFiles(target);
        assertEquals(3, files.size(), files.toString());
        // Three files, three distinct paths. Defaulting both data files to "<database>.mdf" would have the second MOVE
        // overwrite the first, and the restore would still report success.
        assertEquals(3, new LinkedHashSet<>(files).size(), "two files were restored to the same path: " + files);
        assertEquals(2, rowCount(target));
    }
}
