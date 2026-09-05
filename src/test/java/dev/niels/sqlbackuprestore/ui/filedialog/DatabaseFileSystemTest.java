package dev.niels.sqlbackuprestore.ui.filedialog;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The file chooser's actions only handle {@link IOException}; anything else escapes as a logged exception while the
 * user gets no feedback at all (#44).
 */
class DatabaseFileSystemTest {
    private final DatabaseFileSystem disconnected = new DatabaseFileSystem();
    private final RemoteFile directory = FakeRemoteFile.root("/").dir("/var");

    @Test
    void creatingDirectoryWithoutConnectionIsAnIoError() {
        assertThrows(IOException.class, () -> disconnected.createChildDirectory(this, directory, "backups"));
    }

    @Test
    void creatingFileIsAnIoError() {
        assertThrows(IOException.class, () -> disconnected.createChildFile(this, directory, "db.bak"));
    }

    @Test
    void copyingFileIsAnIoError() {
        assertThrows(IOException.class, () -> disconnected.copyFile(this, directory, directory, "copy"));
    }
}
