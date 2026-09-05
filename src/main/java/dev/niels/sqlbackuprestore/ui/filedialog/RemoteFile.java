package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileSystem;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.ui.SQLHelper;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Tree navigation from the connection
 */
public class RemoteFile extends VirtualFile {
    private final DatabaseFileSystem databaseFileSystem;
    private final RemoteFile parent;
    private final String path;
    private final boolean directory;
    @Getter
    private final boolean exists;
    private VirtualFile[] children;
    @Getter
    @Setter
    @Accessors(chain = true)
    private long length;

    public RemoteFile(DatabaseFileSystem databaseFileSystem, RemoteFile parent, String path, boolean directory, boolean exists) {
        this.databaseFileSystem = databaseFileSystem;
        this.parent = parent;
        this.path = StringUtils.stripEnd(path, "\\");
        this.directory = directory;
        this.exists = exists;
    }

    @NotNull
    @Override
    public String getName() {
        var idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (idx == -1) {
            return path;
        }
        return path.substring(idx + 1);
    }

    @NotNull
    @Override
    public VirtualFileSystem getFileSystem() {
        return databaseFileSystem;
    }

    @NotNull
    @Override
    public String getPath() {
        return path;
    }

    /**
     * The path as the server knows it. The default swaps separators for the IDE's platform, which turns a Linux
     * server's {@code /var/backups} into {@code \var\backups} on a Windows client.
     */
    @NotNull
    @Override
    public String getPresentableUrl() {
        return path;
    }

    /**
     * Walk {@code path} down from the root it starts with, through the cached children, so the result is the very
     * instance the tree shows. A missing tail yields a placeholder that doesn't {@link #exists()}; {@code null} means
     * no root matched (e.g. a Windows path on a Linux server).
     */
    @Nullable
    public static RemoteFile resolve(List<? extends VirtualFile> roots, String path) {
        var normalized = RemotePaths.normalize(path);
        for (VirtualFile root : roots) {
            if (!(root instanceof RemoteFile current)) {
                continue;
            }
            var rootPath = current.getPath();
            if (normalized.equalsIgnoreCase(rootPath)) {
                return current;
            }
            var rootPrefix = Strings.CS.appendIfMissing(rootPath, RemotePaths.separator(rootPath), "/", "\\");
            if (!Strings.CI.startsWith(normalized, rootPrefix)) {
                continue;
            }

            for (var name : normalized.substring(rootPrefix.length()).split("[\\\\/]")) {
                current = (RemoteFile) current.getChild(name, true);
            }
            return current;
        }
        return null;
    }

    @Override
    public boolean isWritable() {
        return true;
    }

    @Override
    public boolean isDirectory() {
        return directory;
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public VirtualFile getParent() {
        return parent;
    }

    @SneakyThrows
    @Override
    public VirtualFile[] getChildren() {
        if (children == null) {
            if (isDirectory()) {
                children = SQLHelper.getSQLPathChildren(databaseFileSystem.getConnection(), path).stream().map(r -> new RemoteFile(databaseFileSystem, this, r.get("FullName").toString(), !Integer.valueOf(1).equals(r.get("IsFile")), true)).toArray(RemoteFile[]::new);
            } else {
                children = new VirtualFile[]{};
            }
        }
        return children;
    }

    /** Drop the cached child list so the next {@link #getChildren()} re-queries the server. */
    public void invalidateChildren() {
        children = null;
    }

    @Contract("_, true -> !null")
    public VirtualFile getChild(String name, boolean nonExistingIfNotFound) {
        for (VirtualFile child : getChildren()) {
            if (name.equals(child.getName())) {
                return child;
            }
        }

        if (nonExistingIfNotFound) {
            return new RemoteFile((DatabaseFileSystem) getFileSystem(), this, RemotePaths.join(getPath(), name), false, false);
        }
        return null;
    }

    @NotNull @Override
    public OutputStream getOutputStream(Object requestor, long newModificationStamp, long newTimeStamp) {
        throw new IllegalStateException("Unable to get output stream");
    }

    @Override
    public byte @NotNull [] contentsToByteArray() {
        return new byte[0];
    }

    @Override
    public long getTimeStamp() {
        return 0;
    }

    @Override
    public void refresh(boolean asynchronous, boolean recursive, @Nullable Runnable postRunnable) {
        if (children != null) {
            children = null;
            getChildren();
            if (postRunnable != null) {
                postRunnable.run();
            }
        }
    }

    @NotNull @Override
    public InputStream getInputStream() {
        throw new IllegalStateException("Unable to get input stream");
    }

    @Override
    public boolean exists() {
        return isExists();
    }

    public Client getConnection() {
        return databaseFileSystem.getConnection();
    }
}
