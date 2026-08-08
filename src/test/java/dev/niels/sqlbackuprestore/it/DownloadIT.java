package dev.niels.sqlbackuprestore.it;

import dev.niels.sqlbackuprestore.query.ChunkPlan;
import dev.niels.sqlbackuprestore.query.Statements;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The download's staging and chunking against a real server.
 * <p>
 * A download that loses bytes still finishes, still reports success and still produces a file - the damage only shows up
 * when someone tries to restore it, possibly months later. So the assertion here is the whole point: the bytes that come
 * back out must be the bytes that went in, compared against a hash the server computed itself.
 */
class DownloadIT extends SqlServerTestBase {
    /** Reassembles the staged blob exactly as {@code Download} does, and returns what a file would have received. */
    private byte[] pullStagedBlob(ChunkPlan plan) throws SQLException {
        var out = new ByteArrayOutputStream();
        for (var chunk = 0L; chunk < plan.count(); chunk++) {
            var rows = query(Statements.downloadChunk(plan.offsetOf(chunk), plan.chunkSize()));
            assertEquals(1, rows.size(), "chunk " + chunk + " returned no row");
            out.writeBytes((byte[]) rows.getFirst().get("part"));
        }
        return out.toByteArray();
    }

    private long stagedLength() throws SQLException {
        return ((Number) SqlServer.single(connection, Statements.STAGED_LENGTH, "fs")).longValue();
    }

    private String serverSideHashOfStagedBlob() throws SQLException {
        var hash = (byte[]) SqlServer.single(connection, "SELECT HASHBYTES('SHA2_256', f) AS h FROM #filedownload", "h");
        return HexFormat.of().formatHex(hash);
    }

    @Test
    void stagesABackupAndPullsItBackByteForByte() throws SQLException {
        var database = createDatabase(PREFIX + "download", 500);
        var path = backupPath(PREFIX + "download.bak");
        execute(Statements.backup(database, path, false));

        execute(Statements.stageForDownload(path, false));
        execute(Statements.measureStagedLength());

        var length = stagedLength();
        assertTrue(length > 0, "DATALENGTH reported nothing staged");
        var expectedHash = serverSideHashOfStagedBlob();

        var downloaded = pullStagedBlob(ChunkPlan.of(length));

        assertEquals(length, downloaded.length, "the download is a different length than the staged blob");
        assertEquals(expectedHash, HexFormat.of().formatHex(sha256(downloaded)), "the bytes differ from what the server holds");

        execute(Statements.DROP_STAGED_DOWNLOAD);
    }

    @Test
    void losesNoBytesWhenTheLengthIsAnExactMultipleOfTheChunkSize() throws SQLException {
        // The case that used to truncate: with a 0-based offset the final chunk started one byte late, and there was no
        // short last chunk to absorb the shift. Staged directly so the length can be made exact.
        var chunkSize = 1000;
        var chunks = 4;
        stageKnownBlob(chunkSize * chunks);

        var length = stagedLength();
        assertEquals((long) chunkSize * chunks, length);

        var downloaded = pullStagedBlob(ChunkPlan.ofChunkSize(length, chunkSize));

        assertEquals(length, downloaded.length);
        assertEquals(serverSideHashOfStagedBlob(), HexFormat.of().formatHex(sha256(downloaded)));
    }

    @Test
    void losesNoBytesWhenTheLastChunkIsShort() throws SQLException {
        stageKnownBlob(3501);

        var downloaded = pullStagedBlob(ChunkPlan.ofChunkSize(stagedLength(), 1000));

        assertEquals(3501, downloaded.length);
        assertEquals(serverSideHashOfStagedBlob(), HexFormat.of().formatHex(sha256(downloaded)));
    }

    @Test
    void keepsZeroBytesInTheMiddleOfTheBlob() throws SQLException {
        // DATALENGTH rather than LEN, and a binary column rather than a character one: a backup file is full of zero
        // bytes, and a character length would stop counting at the first one.
        execute("""
                IF OBJECT_ID('tempdb..#filedownload') IS NOT NULL DROP TABLE #filedownload;
                SELECT 1 AS id, CAST(0 AS bigint) AS fs, CAST(0x41000042000043 AS varbinary(max)) AS f INTO #filedownload;""");
        execute(Statements.measureStagedLength());

        assertEquals(7, stagedLength(), "the length stopped at a zero byte");

        // Chunked at 3, so a zero byte lands on a chunk boundary as well as inside one.
        var downloaded = pullStagedBlob(ChunkPlan.ofChunkSize(stagedLength(), 3));

        assertArrayEquals(new byte[]{0x41, 0x00, 0x00, 0x42, 0x00, 0x00, 0x43}, downloaded, "zero bytes were dropped");
    }

    @Test
    void compressesTheStagedCopyWhenAskedAndDecompressesBackToTheOriginal() throws SQLException {
        var database = createDatabase(PREFIX + "download_zip", 200);
        var path = backupPath(PREFIX + "download_zip.bak");
        execute(Statements.backup(database, path, false));

        execute(Statements.stageForDownload(path, false));
        execute(Statements.measureStagedLength());
        var plainLength = stagedLength();
        var plainHash = serverSideHashOfStagedBlob();

        execute(Statements.DROP_STAGED_DOWNLOAD);
        execute(Statements.stageForDownload(path, true));
        execute(Statements.measureStagedLength());
        var compressedLength = stagedLength();

        assertTrue(compressedLength < plainLength,
                "COMPRESS made it bigger (" + compressedLength + " vs " + plainLength + ")");
        // The download writes the compressed bytes to a .gzip that the restore later unpacks, so what COMPRESS produced
        // has to decompress back to exactly the backup file.
        var roundTripped = (byte[]) SqlServer.single(connection,
                "SELECT HASHBYTES('SHA2_256', DECOMPRESS(f)) AS h FROM #filedownload", "h");
        assertEquals(plainHash, HexFormat.of().formatHex(roundTripped));

        execute(Statements.DROP_STAGED_DOWNLOAD);
    }

    /** A blob of exactly {@code length} bytes, in the table and column the download statements expect. */
    private void stageKnownBlob(int length) throws SQLException {
        execute("""
                IF OBJECT_ID('tempdb..#filedownload') IS NOT NULL DROP TABLE #filedownload;
                SELECT 1 AS id, CAST(0 AS bigint) AS fs,
                       CAST(REPLICATE(CAST(0x0102030405060708090a AS varbinary(max)), %d) AS varbinary(max)) AS f
                INTO #filedownload;""".formatted(length / 10 + 1));
        // REPLICATE overshoots, so cut it back to the exact length the test wants.
        execute("UPDATE #filedownload SET f = SUBSTRING(f, 1, %d);".formatted(length));
        execute(Statements.measureStagedLength());
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
