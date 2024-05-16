package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.openapi.vfs.NonPhysicalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileListener;
import com.intellij.openapi.vfs.VirtualFileSystem;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.ui.SQLHelper;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists files from the connection
 */
@Getter
@NoArgsConstructor(force = true)
@RequiredArgsConstructor
public class DatabaseFileSystem extends VirtualFileSystem implements NonPhysicalFileSystem {
    private static final String PROTOCOL = "mssqldb";
    private final Client connection;

    @SneakyThrows
    public VirtualFile[] getRoots() {
        return SQLHelper.getDrives(connection).stream().map(r -> (String) r.get("Name")).map(p -> new RemoteFile(this, null, p, true, true)).toArray(RemoteFile[]::new);
    }

    @NotNull
    @Override
    public String getProtocol() {
        return PROTOCOL;
    }

    @Nullable
    @Override
    public VirtualFile findFileByPath(@NotNull String path) {
        return new RemoteFile(this, null, path, false, true);
    }

    @Override
    public void refresh(boolean asynchronous) {
        // Refreshing not needed
    }

    @Nullable
    @Override
    public VirtualFile refreshAndFindFileByPath(@NotNull String path) {
        return null;
    }

    @Override
    public void addVirtualFileListener(@NotNull VirtualFileListener listener) {
        // Not needed
    }

    @Override
    public void removeVirtualFileListener(@NotNull VirtualFileListener listener) {
        // Not needed
    }

    @Override
    protected void deleteFile(Object requestor, @NotNull VirtualFile vFile) {
        // Not needed
    }

    @Override
    protected void moveFile(Object requestor, @NotNull VirtualFile vFile, @NotNull VirtualFile newParent) {
        // Not needed
    }

    @Override
    protected void renameFile(Object requestor, @NotNull VirtualFile vFile, @NotNull String newName) {
        // Not needed
    }

    @NotNull @Override
    protected VirtualFile createChildFile(Object requestor, @NotNull VirtualFile vDir, @NotNull String fileName) {
        throw new IllegalStateException("Creating files is not supported");
    }

    @NotNull @Override
    protected VirtualFile createChildDirectory(Object requestor, @NotNull VirtualFile vDir, @NotNull String dirName) {
        throw new IllegalStateException("Creating directories is not supported");
    }

    @NotNull @Override
    protected VirtualFile copyFile(Object requestor, @NotNull VirtualFile virtualFile, @NotNull VirtualFile newParent, @NotNull String copyName) {
        throw new IllegalStateException("Copying files is not supported");
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }
}
