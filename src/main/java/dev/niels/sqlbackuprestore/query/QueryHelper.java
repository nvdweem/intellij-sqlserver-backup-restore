package dev.niels.sqlbackuprestore.query;

import com.intellij.database.dialects.mssql.model.MsDatabase;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbNamespaceImpl;
import com.intellij.database.util.DbImplUtil;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Helper to go from actions to table names or connections.
 */
public abstract class QueryHelper {
    private static final List<Client> clients = new ArrayList<>();

    private QueryHelper() {
    }

    public static boolean isMssql(@NotNull AnActionEvent e) {
        return QueryHelper.getSource(e).map(ds -> ds.getDbms().isMicrosoft()).orElse(false);
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
        cleanOldClients();
        var client = new Client(e.getProject(), getSource(e).map(DbImplUtil::getMaybeLocalDataSource).orElseThrow());
        clients.add(client);
        return client;
    }

    public static void cleanOldClients() {
        clients.forEach(Client::cleanIfDone);
    }
}
