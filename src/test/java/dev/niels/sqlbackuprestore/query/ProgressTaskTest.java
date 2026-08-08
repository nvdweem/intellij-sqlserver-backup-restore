package dev.niels.sqlbackuprestore.query;

import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.testFramework.junit5.TestApplication;
import dev.niels.sqlbackuprestore.query.Auditor.MessageType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cancellation half of a long backup or restore.
 * <p>
 * SQL Server gives no way to cancel a statement over the connection running it, and the platform cannot interrupt a
 * thread blocked on the database - it marks the indicator and then waits for {@code run} to return. So the flag is
 * polled on a timer and acting on it is this class's job. That polling is new code, and the failure modes are quiet
 * ones: never firing, or firing repeatedly and issuing a KILL per tick.
 */
@TestApplication
class ProgressTaskTest {
    private static final long TIMEOUT_MS = 10_000;

    /** Spins until {@code condition} holds, so the test does not depend on the 250ms poll landing at a fixed moment. */
    private static boolean waitFor(java.util.function.BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    @Test
    void interruptsTheOperationWhenTheIndicatorIsCancelled() {
        var cancels = new AtomicInteger();
        var indicator = new ProgressIndicatorBase();

        new ProgressTask(null, "Creating backup", cancels::incrementAndGet, consumer -> {
            // Stands in for the statement the connection is blocked on: still running when the user hits Cancel.
            indicator.cancel();
            waitFor(() -> cancels.get() > 0);
        }).run(indicator);

        assertEquals(1, cancels.get(), "cancelling did not interrupt the operation");
    }

    @Test
    void interruptsOnlyOnceHoweverLongTheOperationKeepsRunning() {
        var cancels = new AtomicInteger();
        var indicator = new ProgressIndicatorBase();

        new ProgressTask(null, "Creating backup", cancels::incrementAndGet, consumer -> {
            indicator.cancel();
            waitFor(() -> cancels.get() > 0);
            // Several more polls' worth. Each one firing would issue another KILL against the same session.
            waitFor(() -> cancels.get() > 1);
        }).run(indicator);

        assertEquals(1, cancels.get(), "the poll fired more than once");
    }

    @Test
    void leavesTheOperationAloneWhenNobodyCancels() {
        var cancels = new AtomicInteger();

        new ProgressTask(null, "Creating backup", cancels::incrementAndGet, consumer -> {
            try {
                Thread.sleep(3 * 250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).run(new ProgressIndicatorBase());

        assertEquals(0, cancels.get());
    }

    @Test
    void stopsPollingOnceTheOperationHasFinished() throws InterruptedException {
        var cancels = new AtomicInteger();
        var indicator = new ProgressIndicatorBase();

        new ProgressTask(null, "Creating backup", cancels::incrementAndGet, consumer -> {
            // Finishes without being cancelled.
        }).run(indicator);

        // Cancelling afterwards must do nothing: the task is over, and the watcher should no longer be scheduled.
        indicator.cancel();
        Thread.sleep(3 * 250);

        assertEquals(0, cancels.get(), "the watcher outlived the task and fired after it had finished");
    }

    @Test
    void showsOnlyACancelButtonForSomethingItCanActuallyCancel() {
        assertTrue(new ProgressTask(null, "Creating backup", () -> {
        }, consumer -> {
        }).isCancellable());
        // A button that did nothing would be worse than no button.
        assertFalse(new ProgressTask(null, "Creating backup", null, consumer -> {
        }).isCancellable());
    }

    @Test
    void reportsTheServersProgressPercentage() {
        var indicator = new ProgressIndicatorBase();

        new ProgressTask(null, "Creating backup", null, consumer ->
                // What SQL Server sends for STATS = 10, as the auditor hands it over.
                consumer.accept(MessageType.WARN, "[3211] 40 percent processed.")).run(indicator);

        assertEquals(0.4, indicator.getFraction(), 0.0001);
        assertEquals("40% processed", indicator.getText2());
    }

    @Test
    void tracksProgressAllTheWayToTheEnd() {
        var indicator = new ProgressIndicatorBase();

        new ProgressTask(null, "Creating backup", null, consumer -> {
            consumer.accept(MessageType.WARN, "[3211] 10 percent processed.");
            assertEquals(0.1, indicator.getFraction(), 0.0001);
            consumer.accept(MessageType.WARN, "[3211] 100 percent processed.");
        }).run(indicator);

        assertEquals(1.0, indicator.getFraction(), 0.0001);
    }

    @Test
    void ignoresMessagesThatAreNotProgress() {
        var indicator = new ProgressIndicatorBase();

        new ProgressTask(null, "Creating backup", null, consumer -> {
            consumer.accept(MessageType.WARN, "Processed 12345 pages for database 'shop'.");
            consumer.accept(MessageType.ERROR, "[3211] 50 percent processed.");
        }).run(indicator);

        // Neither is a progress report: the first has no 3211, the second is not a warning.
        assertEquals(0.0, indicator.getFraction(), 0.0001);
    }
}
