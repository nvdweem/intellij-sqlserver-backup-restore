package dev.niels.sqlbackuprestore.action;

import java.text.CharacterIterator;
import java.text.StringCharacterIterator;

public class Util {
    private Util() {
    }

    /**
     * A size for the user to read: SI units, so 1 kB is 1000 bytes, with one decimal.
     * <p>
     * The threshold is 999_950 rather than a round million because the next step down is printed with one decimal:
     * 999_950 bytes would otherwise read as "1000.0 kB".
     */
    public static String humanReadableByteCountSI(long bytes) {
        if (-1000 < bytes && bytes < 1000) {
            return bytes + " B";
        }

        CharacterIterator unit = new StringCharacterIterator("kMGTPE");
        var value = bytes;
        while (value <= -999_950 || value >= 999_950) {
            value /= 1000;
            unit.next();
        }
        // Dividing the long and formatting the remainder as a fraction, rather than working in doubles throughout,
        // is what keeps Long.MIN_VALUE from needing a special case.
        return String.format("%.1f %cB", value / 1000.0, unit.current());
    }
}
