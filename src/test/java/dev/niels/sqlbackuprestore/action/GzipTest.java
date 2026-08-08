package dev.niels.sqlbackuprestore.action;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unpacking a compressed backup before restoring it.
 * <p>
 * Restoring a {@code .gzip} never worked at all before this branch - the file was unpacked and then the original was
 * handed to SQL Server anyway. Since the download writes these files, this is the other half of its own feature.
 */
class GzipTest {
    @TempDir
    Path directory;

    private Path gzipContaining(String name, byte[] content) throws IOException {
        var path = directory.resolve(name);
        try (var out = new GZIPOutputStream(Files.newOutputStream(path))) {
            out.write(content);
        }
        return path;
    }

    @Test
    void unpacksToTheOriginalBytes() throws IOException {
        var content = "BACKUP CONTENT\u0000with a zero byte".getBytes(StandardCharsets.UTF_8);
        var packed = gzipContaining("shop.bak.gzip", content);

        var unpacked = Gzip.unpack(packed.toString());

        assertArrayEquals(content, Files.readAllBytes(Path.of(unpacked)));
    }

    @Test
    void putsTheUnpackedFileNextToTheOriginal() throws IOException {
        var packed = gzipContaining("shop.bak.gzip", new byte[]{1, 2, 3});

        var unpacked = Path.of(Gzip.unpack(packed.toString()));

        assertEquals(directory, unpacked.getParent());
        assertEquals("shop.bak", unpacked.getFileName().toString());
        assertTrue(Files.exists(packed), "the original should be left alone");
    }

    @Test
    void dropsTheGzipAndKeepsTheBakItAlreadyHad() {
        assertEquals("C:\\Backups\\shop.bak", Gzip.unpackedPathOf("C:\\Backups\\shop.bak.gzip"));
    }

    @Test
    void addsBakWhenTheNameHadNothingButGzip() {
        // The download names the file after the backup, so this is what a "shop.gzip" has to become.
        assertEquals("C:\\Backups\\shop.bak", Gzip.unpackedPathOf("C:\\Backups\\shop.gzip"));
    }

    @Test
    void recognisesTheExtensionWhateverTheCase() {
        assertTrue(Gzip.isGzipped("shop.bak.gzip"));
        assertTrue(Gzip.isGzipped("shop.bak.GZIP"));
        assertFalse(Gzip.isGzipped("shop.bak"));
        assertFalse(Gzip.isGzipped("gzip"), "an extension needs its dot");
    }

    @Test
    void reportsAFileThatIsNotActuallyGzipped() throws IOException {
        // A .gzip that is not one has to fail here, where the user is told the file could not be unpacked, rather than
        // producing an empty .bak that SQL Server then rejects for a much less helpful reason.
        var notGzipped = directory.resolve("lying.bak.gzip");
        Files.writeString(notGzipped, "this is not compressed");

        assertThrows(IOException.class, () -> Gzip.unpack(notGzipped.toString()));
    }

    @Test
    void reportsAMissingFile() {
        assertThrows(IOException.class, () -> Gzip.unpack(directory.resolve("absent.bak.gzip").toString()));
    }

    @Test
    void unpacksSomethingLargerThanOneBuffer() throws IOException {
        // transferTo loops; a backup is never going to fit in a single read.
        var content = new byte[512 * 1024];
        for (var i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }
        var packed = gzipContaining("big.bak.gzip", content);

        assertArrayEquals(content, Files.readAllBytes(Path.of(Gzip.unpack(packed.toString()))));
    }
}
