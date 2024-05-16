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
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.OutputStream;

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

    @Contract("_, true -> !null")
    public VirtualFile getChild(String name, boolean nonExistingIfNotFound) {
        for (VirtualFile child : getChildren()) {
            if (name.equals(child.getName())) {
                return child;
            }
        }

        if (nonExistingIfNotFound) {
            return new RemoteFile((DatabaseFileSystem) getFileSystem(), this, getPath() + "\\" + name, false, false);
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
