package dev.niels.sqlbackuprestore.query;

import com.intellij.database.console.session.DatabaseSessionManager;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.dataSource.connection.DatabaseDepartment;
import com.intellij.database.dialects.mssql.model.MsDatabase;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbNamespaceImpl;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.util.Optional;

/**
 * Helper to go from actions to table names or connections.
 */
@Slf4j
public abstract class QueryHelper {
    private QueryHelper() {
    }

    private static Optional<DbNamespaceImpl> getNamespace(@NotNull AnActionEvent e) {
        var element = e.getData(CommonDataKeys.PSI_ELEMENT);
        while (element != null && (!(element instanceof DbNamespaceImpl) || !(((DbNamespaceImpl) element).getDelegate() instanceof MsDatabase))) {
            element = element.getParent();
        }
        return Optional.ofNullable(element == null ? null : (DbNamespaceImpl) element);
    }

    private static Optional<DbDataSource> getSource(@NotNull AnActionEvent e) {
        var element = e.getData(CommonDataKeys.PSI_ELEMENT);
        while (element != null && (!(element instanceof DbDataSource))) {
            element = element.getParent();
        }
        return Optional.ofNullable(element == null ? null : (DbDataSource) element);
    }

    public static Optional<MsDatabase> getDatabase(@NotNull AnActionEvent e) {
        return getNamespace(e).map(d -> (MsDatabase) d.getDelegate());
    }

    @SneakyThrows
    public static Connection connection(@NotNull AnActionEvent e) {
        var element = getSource(e);
        if (element.isEmpty()) {
            throw new IllegalStateException("There should be a datasource");
        }

        var dataSource = (LocalDataSource) element.get().getDelegate();
        var facade = DatabaseSessionManager.facade(e.getProject(), dataSource, null, null, null, databaseDepartment);
        facade.getDataSource().setAutoClose(true);
        var ref = facade.connect();
        return new Connection(ref, ref.get().getRemoteConnection());
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
