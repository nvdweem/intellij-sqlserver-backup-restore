package dev.niels.sqlbackuprestore.action;

import org.apache.commons.lang3.Strings;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Unpacking the compressed backups this plugin's own download produces, so they can be restored without the user
 * having to gunzip them first.
 * <p>
 * SQL Server cannot read a gzipped backup, and the unpacking happens on this machine rather than on the server - which
 * is the same situation the download was in when it wrote the {@code .gzip} in the first place.
 */
final class Gzip {
    static final String EXTENSION = ".gzip";

    private Gzip() {
    }

    /**
     * Where {@code path} unpacks to: alongside the original, with the {@code .gzip} taken off and {@code .bak} put on
     * if it isn't already there. {@code shop.bak.gzip} becomes {@code shop.bak}, and so does {@code shop.gzip}.
     */
    static @NotNull String unpackedPathOf(@NotNull String path) {
        return Strings.CS.appendIfMissing(Strings.CI.removeEnd(path, EXTENSION), ".bak");
    }

    static boolean isGzipped(@NotNull String path) {
        return Strings.CI.endsWith(path, EXTENSION);
    }

    /**
     * @return the path of the unpacked file.
     */
    static @NotNull String unpack(@NotNull String path) throws IOException {
        var unpacked = unpackedPathOf(path);
        try (var in = new GZIPInputStream(Files.newInputStream(Path.of(path)));
             var out = Files.newOutputStream(Path.of(unpacked))) {
            in.transferTo(out);
        }
        return unpacked;
    }
}
