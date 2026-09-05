package dev.niels.sqlbackuprestore.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SQLHelperTest {
    @Test
    void literalQuotesAsUnicodeString() {
        assertEquals("N'/var/backups'", SQLHelper.literal("/var/backups"));
    }

    @Test
    void literalDoublesEmbeddedQuotes() {
        assertEquals("N'C:\\it''s here'", SQLHelper.literal("C:\\it's here"));
    }
}
