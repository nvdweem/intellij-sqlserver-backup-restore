package dev.niels.sqlbackuprestore.query;

import com.intellij.database.console.client.DatabaseSessionClient;
import com.intellij.database.console.session.DatabaseSessionManager;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.dataSource.connection.DatabaseDepartment;
import com.intellij.database.datagrid.DataConsumer;
import com.intellij.database.datagrid.DataRequest;
import com.intellij.openapi.project.Project;
import org.apache.commons.lang3.tuple.Pair;

import javax.swing.Icon;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class Client implements AutoCloseable {
    private final DatabaseSessionClient dbClient;
    private final Auditor auditor;
    private int useCount = 1;

    public Client open() {
        ++useCount;
        return this;
    }

    @Override
    public void close() {
        if (--useCount == 0) {
            done();
        }
    }

    /**
     * Can be used in the exceptionally method of a CompletableFuture
     *
     * @param t The exception that is thrown, will be ignored
     */
    public <T> T close(Throwable t) {
        close();
        return null;
    }

    public <T> T closeAndReturn(T t) {
        close();
        return t;
    }

    public Client(Project project, LocalDataSource dataSource) {
        dbClient = DatabaseSessionManager.facade(project, dataSource, null, null, null, databaseDepartment).client();
        auditor = new Auditor();
        dbClient.getMessageBus().addAuditor(auditor);
    }

    public Client addWarningConsumer(Consumer<Pair<Auditor.MessageType, String>> consumer) {
        auditor.addWarningConsumer(consumer);
        return this;
    }

    private CompletableFuture<List<Map<String, Object>>> getResult(String query, Consumer<Pair<List<DataConsumer.Column>, List<DataConsumer.Row>>> consumer) {
        var table = new Query(dbClient, query, consumer);
        dbClient.getMessageBus().getDataProducer().processRequest(table);
        return table.getFuture();
    }

    public CompletableFuture<List<Map<String, Object>>> getResult(String query) {
        return getResult(query, null);
    }

    public <T> CompletableFuture<T> getSingle(String query, String column) {
        return getResult(query).thenApply(r -> {
            if (r.isEmpty()) {
                throw new IllegalStateException("Expected at least one result for " + query);
            }
            return (T) r.get(0).get(column);
        });
    }

    public CompletableFuture<List<Map<String, Object>>> withRows(String query, Consumer<Pair<List<DataConsumer.Column>, List<DataConsumer.Row>>> consumer) {
        return getResult(query, consumer);
    }

    public CompletableFuture<List<Map<String, Object>>> execute(String query) {
        return getResult(query);
    }

    public void done() {
        dbClient.getMessageBus().getDataProducer().processRequest(new DataRequest.Disconnect(dbClient));
    }

    private static DatabaseDepartment databaseDepartment = new DatabaseDepartment() {
        @Override
        public String getDepartmentName() {
            return "MSSQL Department name";
        }

        @Override
        public String getCommonName() {
            return "MSSQL Common name";
        }

        @Override
        public Icon getIcon() {
            return null;
        }

        @Override
        public boolean isInternal() {
            return false;
        }

        @Override
        public boolean isService() {
            return false;
        }
    };
}
