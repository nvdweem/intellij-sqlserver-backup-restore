package dev.niels.sqlbackuprestore.action;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backing several databases up in one go, with the SQL replaced by something that can be made to fail on demand.
 * <p>
 * The reported symptom was that backing several databases up to a folder the server cannot write reported nothing at
 * all, while the same failure on a single database reported it correctly. This is the part of the multi-database flow
 * that a single backup does not have.
 */
class BackupEachTest {
    private static CompletableFuture<?> succeeds() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<?> fails(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    @Test
    void backsUpEveryDatabaseInOrder() {
        var attempted = new ArrayList<String>();
        var done = new AtomicInteger();

        Backup.backupEach(List.of("a", "b", "c"), done, database -> {
            attempted.add(database);
            return succeeds();
        }).join();

        assertEquals(List.of("a", "b", "c"), attempted);
        assertEquals(3, done.get());
    }

    @Test
    void failsWhenADatabaseFails() {
        var done = new AtomicInteger();

        var chain = Backup.backupEach(List.of("a", "b", "c"), done,
                database -> "b".equals(database) ? fails("Cannot open backup device") : succeeds());

        // If this future completes normally, the caller reports a success and the user is told nothing went wrong.
        var thrown = assertThrows(CompletionException.class, chain::join);
        assertTrue(thrown.getMessage().contains("Cannot open backup device"), thrown.getMessage());
    }

    @Test
    void stopsAtTheFirstFailure() {
        var attempted = new ArrayList<String>();
        var done = new AtomicInteger();

        var chain = Backup.backupEach(List.of("a", "b", "c"), done, database -> {
            attempted.add(database);
            return "b".equals(database) ? fails("Access is denied") : succeeds();
        });
        assertThrows(CompletionException.class, chain::join);

        // The usual reason one fails is that the folder cannot be written, which the rest would fail on too.
        assertEquals(List.of("a", "b"), attempted);
        assertEquals(1, done.get(), "the count is what the failure notification uses to say how far it got");
    }

    @Test
    void failsWhenTheVeryFirstDatabaseFails() {
        var done = new AtomicInteger();

        var chain = Backup.backupEach(List.of("a", "b"), done, database -> fails("Access is denied"));

        assertThrows(CompletionException.class, chain::join);
        assertEquals(0, done.get());
    }

    @Test
    void failsWhenTheLastDatabaseFails() {
        var done = new AtomicInteger();

        var chain = Backup.backupEach(List.of("a", "b"), done,
                database -> "b".equals(database) ? fails("Access is denied") : succeeds());

        assertThrows(CompletionException.class, chain::join);
        assertEquals(1, done.get());
    }

    @Test
    void survivesABackupThatThrowsInsteadOfFailingItsFuture() {
        var done = new AtomicInteger();

        var chain = Backup.backupEach(List.of("a"), done, database -> {
            throw new IllegalStateException("thrown, not returned");
        });

        // Building the statement happens on the calling thread, so it can throw outright rather than hand back a
        // failed future. Either way the caller has to hear about it.
        assertThrows(CompletionException.class, chain::join);
        assertEquals(0, done.get());
    }

    @Test
    void doesNothingForAnEmptySelection() {
        var done = new AtomicInteger();

        Backup.backupEach(List.of(), done, database -> fails("should not run")).join();

        assertEquals(0, done.get());
    }
}
