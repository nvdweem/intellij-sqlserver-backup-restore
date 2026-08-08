package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileSystem;
import dev.niels.sqlbackuprestore.Notifier;
import dev.niels.sqlbackuprestore.ServerPath;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.ui.SQLHelper;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import java.util.Objects;

/**
 * Tree navigation from the connection
 */
@Slf4j
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
    /** Epoch millis, or 0 when the server didn't report one - which is what {@link VirtualFile} uses for "unknown". */
    private long timeStamp;

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

    @Override
    public VirtualFile[] getChildren() {
        if (children != null) {
            return children;
        }
        if (!isDirectory()) {
            children = new VirtualFile[0];
            return children;
        }

        try {
            children = SQLHelper.getSQLPathChildren(databaseFileSystem.getConnection(), path).stream()
                    .map(this::toChild)
                    .toArray(RemoteFile[]::new);
        } catch (Exception e) {
            // This runs from the tree while it is painting, so an exception escaping here would break the dialog
            // rather than the listing. An unreadable directory shows up as an empty one, and says why.
            log.warn("Unable to list {}", path, e);
            Notifier.warning("Unable to list directory", "Could not read " + path + ":\n" + Notifier.rootMessage(e));
            children = new VirtualFile[0];
        }
        return children;
    }

    private RemoteFile toChild(Map<String, Object> row) {
        var child = new RemoteFile(databaseFileSystem, this, Objects.toString(row.get("FullName"), ""),
                !Integer.valueOf(1).equals(row.get("IsFile")), true);
        child.setLength(row.get("SizeInBytes") instanceof Number size ? size.longValue() : 0L);
        child.timeStamp = toEpochMillis(row.get("LastWriteTime"));
        return child;
    }

    /**
     * The driver hands back a {@link Timestamp} for a datetime column, but which temporal type it picks has changed
     * between driver versions, so the ones that could reasonably turn up are all accepted.
     */
    private static long toEpochMillis(Object value) {
        return switch (value) {
            case Timestamp timestamp -> timestamp.getTime();
            case Date date -> date.getTime();
            case LocalDateTime dateTime -> dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            case Instant instant -> instant.toEpochMilli();
            case null, default -> 0L;
        };
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
            return new RemoteFile((DatabaseFileSystem) getFileSystem(), this, ServerPath.join(getPath(), name), false, false);
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
        return timeStamp;
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
