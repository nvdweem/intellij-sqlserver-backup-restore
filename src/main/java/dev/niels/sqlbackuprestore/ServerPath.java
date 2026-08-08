package dev.niels.sqlbackuprestore;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

/**
 * Joining paths the way the server would. SQL Server runs on Linux too, so the separator is whichever one the paths
 * coming back from that server already use rather than the one this machine happens to prefer.
 */
public final class ServerPath {
    private ServerPath() {
    }

    public static @NotNull String join(@NotNull String directory, @NotNull String name) {
        return StringUtils.stripEnd(directory, "/\\") + separatorOf(directory) + name;
    }

    public static @NotNull String separatorOf(@NotNull String path) {
        return StringUtils.contains(path, '/') ? "/" : "\\";
    }
}
