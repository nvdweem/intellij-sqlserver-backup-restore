package dev.niels.sqlbackuprestore.query;

import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.dialects.mssql.model.MsDatabase;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbNamespaceImpl;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

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

    public static Client client(@NotNull AnActionEvent e) {
        return new Client(e.getProject(), (LocalDataSource) (getSource(e).orElseThrow()).getDelegate());
    }
}
