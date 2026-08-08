package dev.niels.sqlbackuprestore.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The statements as text. That they are also accepted by a real server is covered by
 * {@link dev.niels.sqlbackuprestore.it.StatementsIT}, which needs one to talk to; these run everywhere and pin the
 * escaping, which is the part a server would happily accept and silently misinterpret.
 */
class StatementsTest {
    private static RestoreFile file(String logicalName, String type, String restoreAs) {
        return new RestoreFile(logicalName, "C:\\original\\" + logicalName, type).setRestoreAs(restoreAs);
    }

    @Test
    void backsUpCopyOnlyWithoutCompression() {
        assertEquals("BACKUP DATABASE [shop] TO  DISK = N'C:\\Backups\\shop.bak' "
                        + "WITH COPY_ONLY, NOFORMAT, INIT, SKIP, NOREWIND, NOUNLOAD, STATS = 10",
                Statements.backup("shop", "C:\\Backups\\shop.bak", false));
    }

    @Test
    void backsUpWithCompressionWhenAsked() {
        assertTrue(Statements.backup("shop", "/var/backups/shop.bak", true).contains("NOUNLOAD, COMPRESSION, STATS = 10"));
    }

    @Test
    void escapesTheDatabaseNameAndPathOfABackup() {
        var sql = Statements.backup("o]d[b", "C:\\O'Brien\\a.bak", false);

        // A closing bracket doubles inside [], a quote doubles inside ''. Getting either wrong turns a name into syntax.
        assertTrue(sql.startsWith("BACKUP DATABASE [o]]d[b] "), sql);
        assertTrue(sql.contains("N'C:\\O''Brien\\a.bak'"), sql);
    }

    @Test
    void restoresAFullBackupWithOneMovePerFile() {
        var sql = Statements.restoreFull("shop", "C:\\Backups\\shop.bak",
                List.of(file("shop", "D", "C:\\Data\\shop.mdf"), file("shop_log", "L", "C:\\Data\\shop_log.ldf")), false);

        assertEquals("RESTORE DATABASE [shop] FROM DISK = N'C:\\Backups\\shop.bak' WITH file = 1, "
                + "MOVE N'shop' TO N'C:\\Data\\shop.mdf', MOVE N'shop_log' TO N'C:\\Data\\shop_log.ldf', "
                + "NOUNLOAD, STATS = 5, REPLACE", sql);
    }

    @Test
    void leavesTheDatabaseRestoringWhenADifferentialFollows() {
        var sql = Statements.restoreFull("shop", "C:\\b.bak", List.of(file("shop", "D", "C:\\Data\\shop.mdf")), true);

        assertTrue(sql.contains("NORECOVERY, NOUNLOAD"), sql);
    }

    @Test
    void bringsTheDatabaseOnlineWhenNoDifferentialFollows() {
        var sql = Statements.restoreFull("shop", "C:\\b.bak", List.of(file("shop", "D", "C:\\Data\\shop.mdf")), false);

        assertFalse(sql.contains("NORECOVERY"), sql);
    }

    @Test
    void restoresADifferentialWithoutMoveOrReplace() {
        var sql = Statements.restoreDifferential("shop", "C:\\Backups\\shop.dif");

        assertEquals("RESTORE DATABASE [shop] FROM DISK = N'C:\\Backups\\shop.dif' WITH file = 1, NOUNLOAD, STATS = 5", sql);
        assertFalse(sql.contains("MOVE"), sql);
        assertFalse(sql.contains("REPLACE"), sql);
    }

    @Test
    void escapesQuotesInMoveTargets() {
        var sql = Statements.restoreFull("shop", "C:\\b.bak", List.of(file("it's", "D", "C:\\O'Brien\\shop.mdf")), false);

        assertTrue(sql.contains("MOVE N'it''s' TO N'C:\\O''Brien\\shop.mdf'"), sql);
    }

    @Test
    void readsTheHeaderAndFileList() {
        assertEquals("RESTORE HEADERONLY FROM DISK = N'C:\\b.bak' WITH NOUNLOAD;", Statements.headerOnly("C:\\b.bak"));
        assertEquals("RESTORE FILELISTONLY FROM DISK = N'C:\\b.bak';", Statements.fileListOnly("C:\\b.bak"));
    }

    @Test
    void looksUpSessionsOnADatabaseExcludingItsOwn() {
        var sql = Statements.sessionsOn("O'Brien");

        assertTrue(sql.contains("DB_ID(N'O''Brien')"), sql);
        assertTrue(sql.contains("s.session_id <> @@SPID"), sql);
    }

    @Test
    void killsBySessionId() {
        assertEquals("KILL 53;", Statements.kill(53));
    }


    @Test
    void stagesTheDownloadWithOrWithoutCompression() {
        assertTrue(Statements.stageForDownload("C:\\b.bak", false).contains("BulkColumn AS f"));
        assertTrue(Statements.stageForDownload("C:\\b.bak", true).contains("COMPRESS(BulkColumn) AS f"));
        assertTrue(Statements.stageForDownload("C:\\O'Brien\\b.bak", false).contains("BULK N'C:\\O''Brien\\b.bak'"));
    }

    @Test
    void readsDownloadChunksFromAOneBasedOffset() {
        assertEquals("select substring(f, 1, 1000) AS part from #filedownload", Statements.downloadChunk(1, 1000));
        assertEquals("select substring(f, 1001, 1000) AS part from #filedownload", Statements.downloadChunk(1001, 1000));
    }

    @Test
    void measuresTheStagedBlobWithDatalengthRatherThanLen() {
        // LEN is a character function and stops at the first zero byte, which a backup file has plenty of.
        assertTrue(Statements.measureStagedLength().contains("DATALENGTH(f)"));
        assertFalse(Statements.measureStagedLength().contains("LEN(f)"));
    }

    @Test
    void rendersThePathIntoThePathChildrenQuery() {
        var query = Statements.pathChildren("C:\\Backups");

        assertTrue(query.contains("select @Path = N'C:\\Backups'"), query);
    }

    @Test
    void escapesQuotesInTheListedPath() {
        var query = Statements.pathChildren("C:\\O'Brien");

        assertTrue(query.contains("N'C:\\O''Brien'"), query);
    }

    @Test
    void keepsTheWindowsFallbackSeparatorsIntact() {
        // The query is a text block, where a single backslash would be an illegal escape rather than a separator.
        var query = Statements.pathChildren("/var/opt/mssql");

        assertTrue(query.contains("if (right(@Path, 1) = '\\')"), query);
        assertTrue(query.contains("@Path + '\\' + Name"), query);
    }

    @Test
    void looksForBothSeparatorsWhenFindingTheDataDirectory() {
        // The server may be on Linux even when the IDE is not.
        assertTrue(Statements.DEFAULT_DATA_DIRECTORY.contains("CHARINDEX('\\', REVERSE(physical_name))"));
        assertTrue(Statements.DEFAULT_DATA_DIRECTORY.contains("CHARINDEX('/', REVERSE(physical_name))"));
    }
}
