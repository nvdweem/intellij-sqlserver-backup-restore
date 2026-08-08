package dev.niels.sqlbackuprestore.query;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stopping a statement that is already running.
 * <p>
 * There is no way to cancel a statement over the connection that is running it - that connection is busy waiting for
 * the server to finish. The only thing that works is KILL from a second session, which means the session id has to be
 * captured up front, before the long statement starts.
 */
@Slf4j
public class Interrupter {
    private final Client connection;
    private final AtomicReference<Integer> sessionId = new AtomicReference<>();
    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    public Interrupter(@NotNull Client connection) {
        this.connection = connection;
    }

    /**
     * Asks the server which session this connection is, so that it can be killed later. Chain the long statement
     * onto this: without a session id there is nothing to interrupt, and cancelling would do nothing at all.
     */
    public CompletableFuture<?> rememberSession() {
        return connection.getSingle(Statements.CURRENT_SESSION_ID, "spid", Number.class)
                .thenAccept(spid -> sessionId.set(spid == null ? null : spid.intValue()));
    }

    /**
     * Kills the session, once. Safe to call from the progress indicator's thread; the KILL runs on a connection of
     * its own and is not waited on.
     */
    public void interrupt() {
        var session = sessionId.get();
        if (session == null || !interrupted.compareAndSet(false, true)) {
            return;
        }

        var killer = QueryHelper.sibling(connection);
        killer.execute(Statements.kill(session))
                .whenComplete((x, error) -> {
                    if (error != null) {
                        // Nothing useful to tell the user here: the operation is being abandoned either way, and the
                        // notification they get says the server may have been left mid-way.
                        log.warn("Unable to kill session {}", session, error);
                    }
                    killer.release();
                });
    }

    /**
     * Whether {@link #interrupt()} was called - which is how a failure gets reported as "cancelled" rather than as
     * an error the user did not cause.
     */
    public boolean wasInterrupted() {
        return interrupted.get();
    }
}
