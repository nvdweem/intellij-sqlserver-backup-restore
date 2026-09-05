package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications.Bus;
import com.intellij.openapi.vfs.NonPhysicalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileListener;
import com.intellij.openapi.vfs.VirtualFileSystem;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.ui.SQLHelper;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * Lists files from the connection
 */
@Getter
@NoArgsConstructor(force = true)
@RequiredArgsConstructor
public class DatabaseFileSystem extends VirtualFileSystem implements NonPhysicalFileSystem {
    static final String PROTOCOL = "mssqldb";
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

    /*
     * The chooser's actions only catch IOException; anything else escapes into the log while the user sees nothing
     * happen (#44). So every unsupported or failed operation below is reported as an IOException.
     */

    @NotNull @Override
    protected VirtualFile createChildFile(Object requestor, @NotNull VirtualFile vDir, @NotNull String fileName) throws IOException {
        throw new IOException("Creating files on the SQL Server is not supported");
    }

    /**
     * Creates the directory on the SQL Server's machine, which its service account needs write access for. A failure
     * is also raised as a notification because the chooser's own error dialog drops the reason.
     */
    @NotNull @Override
    protected VirtualFile createChildDirectory(Object requestor, @NotNull VirtualFile vDir, @NotNull String dirName) throws IOException {
        if (connection == null || !(vDir instanceof RemoteFile parent)) {
            throw new IOException("Directories can only be created through a SQL Server connection");
        }

        var path = RemotePaths.join(parent.getPath(), dirName);
        List<String> errors;
        try {
            errors = SQLHelper.createDirectory(connection, path);
        } catch (Exception e) {
            throw creationFailed(path, e.getMessage() == null ? e.toString() : e.getMessage());
        }
        parent.invalidateChildren();

        var created = parent.getChild(dirName, false);
        if (created == null || !created.isDirectory()) {
            throw creationFailed(path, errors.isEmpty() ? "the server reported no error but the directory does not show up" : String.join("; ", errors));
        }
        return created;
    }

    private static IOException creationFailed(String path, String reason) {
        var message = "SQL Server could not create the directory " + path + ": " + reason;
        Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, Constants.ERROR, message, NotificationType.ERROR));
        return new IOException(message);
    }

    @NotNull @Override
    protected VirtualFile copyFile(Object requestor, @NotNull VirtualFile virtualFile, @NotNull VirtualFile newParent, @NotNull String copyName) throws IOException {
        throw new IOException("Copying files on the SQL Server is not supported");
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }
}
