package dev.niels.sqlbackuprestore.ui.filedialog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteFileTest {
    /** {@code /var/backups/db.bak} on a Linux SQL Server. */
    private static FakeRemoteFile linuxRoot() {
        var root = FakeRemoteFile.root("/");
        root.dir("/var").dir("/var/backups").file("/var/backups/db.bak");
        return root;
    }

    /** {@code C:\temp\db.bak} on a Windows SQL Server. */
    private static FakeRemoteFile windowsRoot() {
        var root = FakeRemoteFile.root("C:\\");
        root.dir("C:\\temp").file("C:\\temp\\db.bak");
        return root;
    }

    @Test
    void resolveWalksTheCachedTreeOnLinux() {
        var root = linuxRoot();
        var file = RemoteFile.resolve(List.of(root), "/var/backups/db.bak");

        assertNotNull(file);
        assertTrue(file.exists());
        assertSame(root.getChildren()[0].getChildren()[0].getChildren()[0], file);
    }

    @Test
    void resolveWalksTheCachedTreeOnWindows() {
        var root = windowsRoot();
        var file = RemoteFile.resolve(List.of(root), "C:\\temp\\db.bak");

        assertNotNull(file);
        assertSame(root.getChildren()[0].getChildren()[0], file);
    }

    @Test
    void resolveFindsTheRootItself() {
        var linux = linuxRoot();
        var windows = windowsRoot();

        assertSame(linux, RemoteFile.resolve(List.of(linux, windows), "/"));
        assertSame(windows, RemoteFile.resolve(List.of(linux, windows), "C:\\"));
        assertSame(windows, RemoteFile.resolve(List.of(linux, windows), "C:"));
    }

    @Test
    void resolveYieldsPlaceholderForMissingFileUnderExistingDirectory() {
        var root = linuxRoot();
        var file = RemoteFile.resolve(List.of(root), "/var/backups/new.bak");

        assertNotNull(file);
        assertFalse(file.exists());
        assertEquals("/var/backups/new.bak", file.getPath());
        assertSame(root.getChildren()[0].getChildren()[0], file.getParent());
    }

    @Test
    void resolveIsNullWhenNoRootMatches() {
        assertNull(RemoteFile.resolve(List.of(windowsRoot()), "/var/backups/db.bak"));
        assertNull(RemoteFile.resolve(List.of(linuxRoot()), "D:\\db.bak"));
    }

    @Test
    void placeholderChildUsesTheServersSeparator() {
        var backups = linuxRoot().getChildren()[0].getChildren()[0];
        assertEquals("/var/backups/new.bak", ((RemoteFile) backups).getChild("new.bak", true).getPath());

        var temp = windowsRoot().getChildren()[0];
        assertEquals("C:\\temp\\new.bak", ((RemoteFile) temp).getChild("new.bak", true).getPath());
    }

    @Test
    void presentableUrlIsTheServerPathNotTheClientsFlavour() {
        assertEquals("/var/backups", linuxRoot().getChildren()[0].getChildren()[0].getPresentableUrl());
        assertEquals("C:\\temp", windowsRoot().getChildren()[0].getPresentableUrl());
    }
}
