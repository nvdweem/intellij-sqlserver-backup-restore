package dev.niels.sqlbackuprestore.query;

/**
 * How a staged blob is cut up for downloading.
 * <p>
 * Its own type because the arithmetic is where the download went wrong: {@code SUBSTRING} is 1-based in T-SQL, and a
 * 0-based offset made the first chunk a byte short and every later one inherit the shift - so a file whose length was an
 * exact multiple of the chunk size lost its last byte, and nothing else about the download looked wrong.
 *
 * @param totalBytes length of the whole blob.
 * @param chunkSize  bytes per request. The last chunk is whatever is left; {@code SUBSTRING} clamps at the end, so it
 *                   does not need asking for a shorter piece.
 * @param count      number of requests needed.
 */
public record ChunkPlan(long totalBytes, long chunkSize, long count) {
    /** Below this, the extra round trips cost more than the smoother progress bar is worth. */
    private static final long MINIMUM_CHUNK = 1_000_000;

    /** A hundred chunks, so the progress bar moves, unless that would make them smaller than {@link #MINIMUM_CHUNK}. */
    public static ChunkPlan of(long totalBytes) {
        return ofChunkSize(totalBytes, Math.max(MINIMUM_CHUNK, (long) Math.ceil(totalBytes / 100d)));
    }

    public static ChunkPlan ofChunkSize(long totalBytes, long chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Chunk size must be positive but was " + chunkSize);
        }
        return new ChunkPlan(totalBytes, chunkSize, (long) Math.ceil((double) totalBytes / chunkSize));
    }

    /** Where chunk {@code index} starts, counting from 1 as {@code SUBSTRING} does. */
    public long offsetOf(long index) {
        return index * chunkSize + 1;
    }

    /** How far through the download chunk {@code index} leaves us, for the progress indicator. */
    public double fractionAt(long index) {
        return count == 0 ? 1 : (double) index / count;
    }

    /** Bytes transferred once chunk {@code index} has been written, never overstating the total. */
    public long bytesThrough(long index) {
        return Math.min(totalBytes, (index + 1) * chunkSize);
    }
}
