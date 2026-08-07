package dev.niels.sqlbackuprestore.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilTest {
    @Test
    @DisplayName("bytes below a kilobyte are reported as-is")
    void reportsSmallSizesInBytes() {
        assertEquals("0 B", Util.humanReadableByteCountSI(0));
        assertEquals("999 B", Util.humanReadableByteCountSI(999));
    }

    @Test
    @DisplayName("larger sizes step up through the SI units")
    void stepsThroughUnits() {
        assertTrue(Util.humanReadableByteCountSI(1_000).endsWith("kB"));
        assertTrue(Util.humanReadableByteCountSI(1_500_000).endsWith("MB"));
        assertTrue(Util.humanReadableByteCountSI(2_000_000_000L).endsWith("GB"));
        assertTrue(Util.humanReadableByteCountSI(3_000_000_000_000L).endsWith("TB"));
    }

    @Test
    @DisplayName("negative sizes keep their sign")
    void keepsTheSign() {
        assertTrue(Util.humanReadableByteCountSI(-1_500_000).startsWith("-"));
    }

    @Test
    @DisplayName("Long.MIN_VALUE doesn't blow up on Math.abs")
    void survivesLongMinValue() {
        assertTrue(Util.humanReadableByteCountSI(Long.MIN_VALUE).startsWith("-"));
    }
}
