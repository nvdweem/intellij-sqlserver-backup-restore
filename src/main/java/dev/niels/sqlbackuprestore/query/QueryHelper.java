package dev.niels.sqlbackuprestore.query;

import com.intellij.database.console.session.DatabaseSessionManager;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.dataSource.connection.DatabaseDepartment;
import com.intellij.database.dialects.mssql.model.MsDatabase;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbNamespaceImpl;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import dev.niels.sqlbackuprestore.action.Query;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.util.Optional;

@Slf4j
public abstract class QueryHelper {
    private static DatabaseDepartment databaseDepartment;

    private QueryHelper() {
    }

    private static Optional<DbNamespaceImpl> getNamespace(@NotNull AnActionEvent e) {
        var element = e.getData(CommonDataKeys.PSI_ELEMENT);
        while (element != null && (!(element instanceof DbNamespaceImpl) || !(((DbNamespaceImpl) element).getDelegate() instanceof MsDatabase))) {
            element = element.getParent();
        }
        return Optional.ofNullable(element == null ? null : (DbNamespaceImpl) element);
    }

    public static Optional<MsDatabase> getDatabase(@NotNull AnActionEvent e) {
        return getNamespace(e).map(d -> (MsDatabase) d.getDelegate());
    }

    @SneakyThrows
    public static Connection connection(@NotNull AnActionEvent e) {
        var element = getNamespace(e);
        if (element.isEmpty()) {
            throw new IllegalStateException("There should be an element");
        }

        var facade = DatabaseSessionManager.facade(e.getProject(), (LocalDataSource) ((DbDataSource) element.get().getParent()).getDelegate(), null, null, null, databaseDepartment);
        var ref = facade.connect();
        return new Connection(ref, ref.get().getRemoteConnection());
    }

    public static Query query(@NotNull AnActionEvent e, String query) {
        var element = getNamespace(e);
        if (element.isEmpty()) {
            return null;
        }

        log.info("Running query {}", query);
        var facade = DatabaseSessionManager.facade(e.getProject(), (LocalDataSource) ((DbDataSource) element.get().getParent()).getDelegate(), null, null, null, databaseDepartment);
        var client = facade.client();
        var bus = client.getMessageBus();
        var producer = bus.getDataProducer();
        var result = new Query(e.getProject(), query);
        producer.processRequest(result);

        result.getFuture().thenAccept(x -> client.dispose());
        return result;
    }

    static {
        databaseDepartment = new DatabaseDepartment() {
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
}
