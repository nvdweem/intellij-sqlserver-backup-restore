package dev.niels.sqlbackuprestore.ui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLHelperTest {
    @Test
    void awaitReturnsTheResultOfACompletedFuture() throws Exception {
        assertEquals("rows", SQLHelper.await(CompletableFuture.completedFuture("rows"), 1));
    }

    @Test
    void awaitTimesOutWithAMessageNamingTheWait() {
        var never = new CompletableFuture<String>();
        var timeout = assertThrows(TimeoutException.class, () -> SQLHelper.await(never, 0));
        assertTrue(timeout.getMessage().contains("0 seconds"), timeout.getMessage());
    }

    @Test
    void awaitUnwrapsAFailedFuture() {
        var cause = new IllegalStateException("boom");
        var failure = assertThrows(ExecutionException.class, () -> SQLHelper.await(CompletableFuture.failedFuture(cause), 1));
        assertSame(cause, failure.getCause());
    }

    @Test
    void literalQuotesAsUnicodeString() {
        assertEquals("N'/var/backups'", SQLHelper.literal("/var/backups"));
    }

    @Test
    void literalDoublesEmbeddedQuotes() {
        assertEquals("N'C:\\it''s here'", SQLHelper.literal("C:\\it's here"));
    }
}
