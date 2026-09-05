package dev.niels.sqlbackuprestore.ui.filedialog;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Path arithmetic for files on the SQL Server. The server decides the separator: a Linux instance uses {@code /}
 * even when the IDE runs on Windows, so {@link java.io.File#separator} must never be used for these paths.
 */
public final class RemotePaths {
    /** What {@link com.intellij.openapi.vfs.VirtualFile#getUrl()} prefixes to a {@link RemoteFile} path. */
    static final String PROTOCOL_PREFIX = DatabaseFileSystem.PROTOCOL + "://";

    private RemotePaths() {
    }

    /** The separator the server uses for {@code path}: {@code /} for Linux-style paths, {@code \} otherwise. */
    public static String separator(String path) {
        return path.startsWith("/") ? "/" : "\\";
    }

    /** {@code directory} + separator + {@code name}, without doubling a separator the directory already ends in. */
    public static String join(String directory, String name) {
        return StringUtils.stripEnd(directory, "/\\") + separator(directory) + name;
    }

    /** Strips the VFS protocol and any trailing separator, so {@code C:\} becomes {@code C:} (a root's path). */
    public static String normalize(String path) {
        var plain = Strings.CS.removeStart(path, PROTOCOL_PREFIX);
        return "/".equals(plain) ? plain : StringUtils.stripEnd(plain, "/\\");
    }
}
