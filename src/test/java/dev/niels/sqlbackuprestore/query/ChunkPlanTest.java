package dev.niels.sqlbackuprestore.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPlanTest {
    @Test
    void startsAtOneBecauseSubstringIsOneBased() {
        var plan = ChunkPlan.ofChunkSize(3000, 1000);

        assertEquals(1, plan.offsetOf(0));
        assertEquals(1001, plan.offsetOf(1));
        assertEquals(2001, plan.offsetOf(2));
    }

    @Test
    void coversEveryByteWhenTheLengthIsAnExactMultipleOfTheChunkSize() {
        var plan = ChunkPlan.ofChunkSize(3000, 1000);

        assertEquals(3, plan.count());
        // The last chunk has to end exactly on the last byte. Off by one here is the bug that silently truncated files.
        assertEquals(3000, plan.offsetOf(plan.count() - 1) + plan.chunkSize() - 1);
    }

    @Test
    void coversEveryByteWhenTheLastChunkIsShort() {
        var plan = ChunkPlan.ofChunkSize(3001, 1000);

        assertEquals(4, plan.count());
        assertEquals(3001, plan.offsetOf(3));
    }

    @Test
    void asksForAHundredChunksOnceThatKeepsThemAboveAMegabyte() {
        var plan = ChunkPlan.of(500_000_000);

        assertEquals(100, plan.count());
        assertEquals(5_000_000, plan.chunkSize());
    }

    @Test
    void staysAtAMegabyteForSmallFilesRatherThanMakingAHundredTinyRequests() {
        var plan = ChunkPlan.of(2_500_000);

        assertEquals(1_000_000, plan.chunkSize());
        assertEquals(3, plan.count());
    }

    @Test
    void needsNoChunksForAnEmptyBlob() {
        var plan = ChunkPlan.of(0);

        assertEquals(0, plan.count());
        assertEquals(1, plan.fractionAt(0), "an empty download is a finished download");
    }

    @Test
    void reportsProgressThatNeverOverstatesTheTotal() {
        var plan = ChunkPlan.ofChunkSize(2500, 1000);

        assertEquals(1000, plan.bytesThrough(0));
        assertEquals(2000, plan.bytesThrough(1));
        assertEquals(2500, plan.bytesThrough(2), "the last chunk is short, so 3000 would be a lie");
        assertTrue(plan.fractionAt(plan.count() - 1) <= 1);
    }

    @Test
    void refusesAChunkSizeThatWouldNeverFinish() {
        assertThrows(IllegalArgumentException.class, () -> ChunkPlan.ofChunkSize(1000, 0));
    }
}
