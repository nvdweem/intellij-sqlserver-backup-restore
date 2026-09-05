package dev.niels.sqlbackuprestore.ui.filedialog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Path arithmetic for files that live on the SQL Server, which may be a Linux box even when the IDE runs on Windows.
 */
class RemotePathsTest {
    @Test
    void separatorIsForwardSlashForLinuxPaths() {
        assertEquals("/", RemotePaths.separator("/"));
        assertEquals("/", RemotePaths.separator("/var/backups"));
    }

    @Test
    void separatorIsBackslashForWindowsPaths() {
        assertEquals("\\", RemotePaths.separator("C:"));
        assertEquals("\\", RemotePaths.separator("C:\\temp"));
    }

    @Test
    void joinUsesTheSeparatorOfTheDirectory() {
        assertEquals("/var/backups/db.bak", RemotePaths.join("/var/backups", "db.bak"));
        assertEquals("/var/opt/mssql/data/db.mdf", RemotePaths.join("/var/opt/mssql/data/", "db.mdf"));
        assertEquals("C:\\temp\\db.bak", RemotePaths.join("C:\\temp", "db.bak"));
        assertEquals("C:\\data\\db.mdf", RemotePaths.join("C:\\data\\", "db.mdf"));
    }

    @Test
    void joinOntoRootDoesNotDoubleTheSeparator() {
        assertEquals("/var", RemotePaths.join("/", "var"));
        assertEquals("C:\\temp", RemotePaths.join("C:", "temp"));
        assertEquals("C:\\temp", RemotePaths.join("C:\\", "temp"));
    }

    @Test
    void normalizeStripsTheVfsProtocol() {
        assertEquals("/var/backups", RemotePaths.normalize("mssqldb:///var/backups"));
        assertEquals("C:\\temp", RemotePaths.normalize("mssqldb://C:\\temp"));
    }

    @Test
    void normalizeStripsTrailingSeparatorsExceptOnRoots() {
        assertEquals("/var/backups", RemotePaths.normalize("/var/backups/"));
        assertEquals("C:\\temp", RemotePaths.normalize("C:\\temp\\"));
        assertEquals("/", RemotePaths.normalize("/"));
        assertEquals("C:", RemotePaths.normalize("C:\\"));
    }

    @Test
    void normalizeLeavesPlainPathsAlone() {
        assertEquals("/var/backups/db.bak", RemotePaths.normalize("/var/backups/db.bak"));
        assertEquals("", RemotePaths.normalize(""));
    }
}
