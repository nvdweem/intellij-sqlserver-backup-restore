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

public class Client implements AutoCloseable {
    private final DatabaseSessionClient dbClient;
    private final Auditor auditor;
    @Getter
    private final String dbName;
    // Opened/closed from the EDT, from background tasks and from the database thread that completes a query.
    private final AtomicInteger useCount = new AtomicInteger(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public Client(Project project, LocalDataSource dataSource) {
        dbClient = DatabaseSessionManager.getFacade(project, dataSource, null, null, null, Constants.databaseDepartment).client();
        dbName = dataSource.getName();
        auditor = new Auditor();
        dbClient.getMessageBus().addAuditor(auditor);
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

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> getSingle(String query, String column) {
        return getResult(query).thenApply(r -> {
            if (r.isEmpty()) {
                throw new IllegalStateException("Expected at least one result for " + query);
            }
            return (T) r.getFirst().get(column);
        });
    }

    /**
     * Same as {@link #getSingle(String, String)}, but checks the value really is a {@code clazz} instead of letting an
     * unchecked cast blow up somewhere down the chain.
     */
    public <T> CompletableFuture<T> getSingle(String query, String column, @NotNull Class<T> clazz) {
        return this.<Object>getSingle(query, column).thenApply(value -> {
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

    public void done() {
        dbClient.getMessageBus().getDataProducer().processRequest(new Disconnect(dbClient));
    }

    public void open() {
        if (useCount.getAndIncrement() == 0) {
            // Picked up again after everyone let go; it will need disconnecting once more.
            closed.set(false);
        }
    }

    /**
     * Releases one use of this client and disconnects once nobody holds it any more. Closing more often than opening
     * used to push the counter below zero, which meant the session was never disconnected afterwards.
     */
    @Override
    public void close() {
        if (useCount.updateAndGet(count -> Math.max(0, count - 1)) == 0 && !closed.getAndSet(true)) {
            done();
        }
    }

    /**
     * Disposes the underlying session once it has disconnected.
     *
     * @return {@code true} when this client is finished with and can be forgotten about.
     */
    public boolean cleanIfDone() {
        var session = dbClient.getSession();
        // useCount > 0 means an action still holds this client - it may simply not have connected yet.
        if (useCount.get() > 0 || session.isConnected()) {
            return false;
        }

        for (DatabaseSessionClient client : session.getClients()) {
            session.detach(client);
        }
        Disposer.dispose(session);
        return true;
    }

    /**
     * Can be used in the exceptionally method of a CompletableFuture
     *
     * @param t The exception that is thrown, will be ignored
     */
    @SuppressWarnings({"unused", "SameReturnValue"}) // param t
    public <T> T close(Throwable t) {
        close();
        return null;
    }

    public <T> T closeAndReturn(T t) {
        close();
        return t;
    }
}
