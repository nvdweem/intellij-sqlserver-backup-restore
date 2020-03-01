package dev.niels.sqlbackuprestore.action;

public class Util {
    private Util() {
    }

    public static String humanReadableByteCountSI(long bytes) {  // NOSONAR
        String s = bytes < 0 ? "-" : ""; // NOSONAR
        long b = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes); // NOSONAR
        return b < 1000L ? bytes + " B" // NOSONAR
                : b < 999_950L ? String.format("%s%.1f kB", s, b / 1e3) // NOSONAR
                : (b /= 1000) < 999_950L ? String.format("%s%.1f MB", s, b / 1e3) // NOSONAR
                : (b /= 1000) < 999_950L ? String.format("%s%.1f GB", s, b / 1e3) // NOSONAR
                : (b /= 1000) < 999_950L ? String.format("%s%.1f TB", s, b / 1e3) // NOSONAR
                : (b /= 1000) < 999_950L ? String.format("%s%.1f PB", s, b / 1e3) // NOSONAR
                : String.format("%s%.1f EB", s, b / 1e6); // NOSONAR
    }
}
