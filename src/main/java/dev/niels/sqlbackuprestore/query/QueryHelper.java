package dev.niels.sqlbackuprestore.query;

import com.intellij.database.dialects.mssql.model.MsDatabase;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbElement;
import com.intellij.database.psi.DbNamespaceImpl;
import com.intellij.database.util.DbImplUtil;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.intellij.openapi.actionSystem.PlatformCoreDataKeys.PSI_ELEMENT_ARRAY;

/**
 * Helper to go from actions to table names or connections.
 */
public abstract class QueryHelper {
    // Finished clients are removed again: keeping every client ever opened would pin its session (and through it the
    // project) for as long as the IDE runs. Copy-on-write because actions run on several threads.
    private static final List<Client> clients = new CopyOnWriteArrayList<>();

    private QueryHelper() {
    }

    public static boolean isMssql(@NotNull AnActionEvent e) {
        return QueryHelper.getSource(e).map(ds -> ds.getDbms().isMicrosoft()).orElse(false);
    }

    private static Optional<DbNamespaceImpl> getNamespace(@NotNull AnActionEvent e) {
        var element = getPsiElement(e);
        while (element != null && (!(element instanceof DbNamespaceImpl) || !(((DbElement) element).getDelegate() instanceof MsDatabase))) {
            element = element.getParent();
        }
        return Optional.ofNullable(element == null ? null : (DbNamespaceImpl) element);
    }

    private static Optional<DbDataSource> getSource(@NotNull AnActionEvent e) {
        var element = getPsiElement(e);
        while (element != null && !(element instanceof DbDataSource)) {
            element = element.getParent();
        }
        return Optional.ofNullable(element == null ? null : (DbDataSource) element);
    }

    private static @Nullable PsiElement getPsiElement(@NotNull AnActionEvent e) {
        var element = e.getData(CommonDataKeys.PSI_ELEMENT);
        if (element == null) {
            var arr = e.getData(PSI_ELEMENT_ARRAY);
            if (arr != null && arr.length > 0) {
                element = arr[0];
            }
        }
        return element;
    }

    public static Optional<MsDatabase> getDatabase(@NotNull AnActionEvent e) {
        return getNamespace(e).map(d -> (MsDatabase) d.getDelegate());
    }

    public static Client client(@NotNull AnActionEvent e) {
        cleanOldClients();
        var dataSource = getSource(e).map(DbImplUtil::getMaybeLocalDataSource)
                .orElseThrow(() -> new IllegalStateException("No SQL Server data source found for this selection"));
        var client = new Client(Objects.requireNonNull(e.getProject(), "No project for this action"), dataSource);
        clients.add(client);
        return client;
    }

    public static void cleanOldClients() {
        clients.removeIf(Client::cleanIfDone);
    }
}
