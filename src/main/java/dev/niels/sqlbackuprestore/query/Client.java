package dev.niels.sqlbackuprestore.query;

import com.intellij.database.console.client.DatabaseSessionClient;
import com.intellij.database.console.session.DatabaseSessionManager;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.datagrid.DataRequest.Disconnect;
import com.intellij.database.datagrid.GridColumn;
import com.intellij.database.datagrid.GridRow;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.query.Auditor.MessageType;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * A session on the server, shared by everything that is using it: a backup hands the same client to the download that
 * follows it, and every query takes a hold of its own for as long as it runs. The session is disconnected when the last
 * holder lets go.
 * <p>
 * Deliberately not {@link AutoCloseable}. It used to be, and {@code try (var c = QueryHelper.client(e))} reads as "this
 * connection is closed at the end of the block" when what it really did was drop one of several holds - which is how
 * the same client ended up being released twice down one path and never down another.
 */
public class Client {
    private final DatabaseSessionClient dbClient;
    private final Auditor auditor;
    private final Project project;
    private final LocalDataSource dataSource;
    @Getter
    private final String dbName;
    // Acquired/released from the EDT, from background tasks and from the database thread that completes a query.
    private final AtomicInteger holds = new AtomicInteger(1);
    private final AtomicBoolean disconnected = new AtomicBoolean(false);

    public Client(Project project, LocalDataSource dataSource) {
        this.project = project;
        this.dataSource = dataSource;
        dbClient = DatabaseSessionManager.getFacade(project, dataSource, null, null, null, Constants.databaseDepartment).client();
        dbName = dataSource.getName();
        auditor = new Auditor();
        dbClient.getMessageBus().addAuditor(auditor);
    }

    /**
     * A second, independent session on the same server. Needed for the one thing that cannot be done over this
     * connection: interrupting whatever this connection is currently busy with.
     */
    Client newSession() {
        return new Client(project, dataSource);
    }

    public void setTitle(String title) {
        dbClient.getSession().setTitle(title);
    }

    public Client addWarningConsumer(BiConsumer<MessageType, String> consumer) {
        auditor.addWarningConsumer(consumer);
        return this;
    }

    public void removeWarningConsumer(BiConsumer<MessageType, String> consumer) {
        auditor.removeWarningConsumer(consumer);
    }

    private CompletableFuture<List<Map<String, Object>>> getResult(String query, BiConsumer<List<GridColumn>, List<GridRow>> consumer) {
        var table = new Query(this, dbClient, query, consumer);
        dbClient.getMessageBus().getDataProducer().processRequest(table);
        return table.getFuture();
    }

    public CompletableFuture<List<Map<String, Object>>> getResult(String query) {
        return getResult(query, null);
    }

    /**
     * The value of {@code column} in the first row. The type is checked here rather than left to an unchecked cast
     * that would blow up somewhere further down the chain, with nothing to say which query produced it.
     */
    public <T> CompletableFuture<T> getSingle(String query, String column, @NotNull Class<T> clazz) {
        return getResult(query).thenApply(rows -> {
            if (rows.isEmpty()) {
                throw new IllegalStateException("Expected at least one result for " + query);
            }
            var value = rows.getFirst().get(column);
            if (value != null && !clazz.isInstance(value)) {
                throw new IllegalStateException("Expected " + column + " to be a " + clazz.getSimpleName() + " but got a " + value.getClass().getSimpleName());
            }
            return clazz.cast(value);
        });
    }

    public CompletableFuture<List<Map<String, Object>>> withRows(String query, BiConsumer<List<GridColumn>, List<GridRow>> consumer) {
        return getResult(query, consumer);
    }

    public CompletableFuture<List<Map<String, Object>>> execute(String query) {
        return getResult(query);
    }

    /**
     * Takes a hold on this client, keeping the session alive until the matching {@link #release()}. The client comes
     * back from {@link QueryHelper#client} with one hold already taken, belonging to whoever asked for it.
     */
    public void acquire() {
        if (holds.getAndIncrement() == 0) {
            // Picked up again after everyone let go; it will need disconnecting once more.
            disconnected.set(false);
        }
    }

    /**
     * Releases one hold, disconnecting the session when it was the last one. Releasing more often than acquiring used
     * to push the count below zero, after which the session was never disconnected at all.
     */
    public void release() {
        if (holds.updateAndGet(count -> Math.max(0, count - 1)) == 0 && !disconnected.getAndSet(true)) {
            dbClient.getMessageBus().getDataProducer().processRequest(new Disconnect(dbClient));
        }
    }

    /**
     * Disposes the underlying session once it has disconnected.
     *
     * @return {@code true} when this client is finished with and can be forgotten about.
     */
    public boolean cleanIfDone() {
        var session = dbClient.getSession();
        // A remaining hold means an action still has this client - it may simply not have connected yet.
        if (holds.get() > 0 || session.isConnected()) {
            return false;
        }

        for (DatabaseSessionClient client : session.getClients()) {
            session.detach(client);
        }
        Disposer.dispose(session);
        return true;
    }
}
