package dev.niels.sqlbackuprestore.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UtilTest {
    /**
     * The sizes are formatted for the user, so they follow the default locale - a Dutch IDE shows "1,5 MB". Expected
     * values are built the same way rather than written out, so the assertions say which number and which unit without
     * pinning the decimal separator.
     */
    private static String expect(String format, double value) {
        return String.format(format, value);
    }

    @Test
    @DisplayName("bytes below a kilobyte are reported as-is")
    void reportsSmallSizesInBytes() {
        assertEquals("0 B", Util.humanReadableByteCountSI(0));
        assertEquals("999 B", Util.humanReadableByteCountSI(999));
        assertEquals("-999 B", Util.humanReadableByteCountSI(-999));
    }

    @Test
    @DisplayName("larger sizes step up through the SI units")
    void stepsThroughUnits() {
        assertEquals(expect("%.1f kB", 1.0), Util.humanReadableByteCountSI(1_000));
        assertEquals(expect("%.1f kB", 1.5), Util.humanReadableByteCountSI(1_500));
        assertEquals(expect("%.1f MB", 1.5), Util.humanReadableByteCountSI(1_500_000));
        assertEquals(expect("%.1f GB", 2.0), Util.humanReadableByteCountSI(2_000_000_000L));
        assertEquals(expect("%.1f TB", 3.0), Util.humanReadableByteCountSI(3_000_000_000_000L));
        assertEquals(expect("%.1f PB", 4.0), Util.humanReadableByteCountSI(4_000_000_000_000_000L));
    }

    @Test
    @DisplayName("the unit rolls over just under the next thousand")
    void rollsOverAtTheRoundingBoundary() {
        // 999_950 rounds to 1000.0 kB, which should read as 1.0 MB instead.
        assertEquals(expect("%.1f kB", 999.9), Util.humanReadableByteCountSI(999_949));
        assertEquals(expect("%.1f MB", 1.0), Util.humanReadableByteCountSI(999_950));
    }

    @Test
    @DisplayName("negative sizes keep their sign")
    void keepsTheSign() {
        assertEquals(expect("%.1f MB", -1.5), Util.humanReadableByteCountSI(-1_500_000));
    }

    @Test
    @DisplayName("Long.MIN_VALUE doesn't blow up on Math.abs")
    void survivesLongMinValue() {
        assertEquals(expect("%.1f EB", -9.2), Util.humanReadableByteCountSI(Long.MIN_VALUE));
    }
}
