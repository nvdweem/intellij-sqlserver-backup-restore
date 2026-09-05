package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link RemoteFile} whose children are set up in the test instead of being queried from SQL Server.
 */
class FakeRemoteFile extends RemoteFile {
    private final List<RemoteFile> kids = new ArrayList<>();

    private FakeRemoteFile(RemoteFile parent, String path, boolean directory) {
        super(new DatabaseFileSystem(), parent, path, directory, true);
    }

    /** A drive root as {@code getDrives} reports it, e.g. {@code C:\} or {@code /}. */
    static FakeRemoteFile root(String drivePath) {
        return new FakeRemoteFile(null, drivePath, true);
    }

    FakeRemoteFile dir(String fullPath) {
        return add(new FakeRemoteFile(this, fullPath, true));
    }

    FakeRemoteFile file(String fullPath) {
        return add(new FakeRemoteFile(this, fullPath, false));
    }

    private FakeRemoteFile add(FakeRemoteFile child) {
        kids.add(child);
        return child;
    }

    @Override
    public VirtualFile[] getChildren() {
        return kids.toArray(VirtualFile[]::new);
    }
}
