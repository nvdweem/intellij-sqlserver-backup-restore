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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

public class Client implements AutoCloseable {
    private final DatabaseSessionClient dbClient;
    private final Auditor auditor;
    @Getter
    private final String dbName;
    private int useCount = 1;

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
            return (T) r.get(0).get(column);
        });
    }

    public <T> CompletableFuture<T> getSingle(String query, String column, Class<T> clazz) {
        if (clazz == null) {
            return null;
        }
        return getSingle(query, column);
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
        ++useCount;
    }

    @Override
    public void close() {
        if (--useCount == 0) {
            done();
        }
    }

    public void cleanIfDone() {
        if (!dbClient.getSession().isConnected()) {
            var session = dbClient.getSession();
            DatabaseSessionClient[] clients = session.getClients();
            for (DatabaseSessionClient client : clients) {
                session.detach(client);
            }
            Disposer.dispose(session);
        }
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
