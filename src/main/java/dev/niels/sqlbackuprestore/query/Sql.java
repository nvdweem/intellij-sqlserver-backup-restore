package dev.niels.sqlbackuprestore.query;

import org.jetbrains.annotations.NotNull;

/**
 * The backup/restore statements can't be parameterized (T-SQL doesn't accept a variable where {@code BACKUP DATABASE}
 * wants an identifier, and {@code DISK = N'...'} only takes a literal), so the values are pasted into the statement.
 * These helpers make that pasting safe: a database named {@code o'brien} or a directory called {@code it's here} would
 * otherwise produce a syntax error, and anything nastier would run as SQL.
 */
public final class Sql {
    private Sql() {
    }

    /**
     * Escape a value for use inside a single-quoted string literal, e.g. {@code DISK = N'} + literal(path) + {@code '}.
     */
    public static @NotNull String literal(@NotNull String value) {
        return value.replace("'", "''");
    }

    /**
     * Escape a value for use inside a bracketed identifier, e.g. {@code [} + identifier(database) + {@code ]}.
     */
    public static @NotNull String identifier(@NotNull String value) {
        return value.replace("]", "]]");
    }

    /**
     * A bracket-quoted identifier, ready to be pasted into a statement.
     */
    public static @NotNull String quoted(@NotNull String value) {
        return "[" + identifier(value) + "]";
    }
}
