package dev.niels.sqlbackuprestore.query;

import com.intellij.database.dialects.mssql.model.MsDatabase;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbElement;
import com.intellij.database.psi.DbNamespaceImpl;
import com.intellij.database.util.DbImplUtil;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.psi.PsiElement;
import one.util.streamex.StreamEx;
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
        return Optional.ofNullable(toDatabaseNamespace(getPsiElement(e)));
    }

    /**
     * Walks up from {@code element} to the database it sits in, or null when it isn't in one.
     */
    private static @Nullable DbNamespaceImpl toDatabaseNamespace(@Nullable PsiElement element) {
        var current = element;
        while (current != null && (!(current instanceof DbNamespaceImpl) || !(((DbElement) current).getDelegate() instanceof MsDatabase))) {
            current = current.getParent();
        }
        return (DbNamespaceImpl) current;
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

    /**
     * Every database the selection covers, in the order the Database view lists them. The view supports multi-select
     * and the actions used to take the first element and quietly ignore the rest.
     */
    public static List<MsDatabase> getDatabases(@NotNull AnActionEvent e) {
        var elements = e.getData(PSI_ELEMENT_ARRAY);
        if (elements == null || elements.length == 0) {
            return getDatabase(e).map(List::of).orElseGet(List::of);
        }

        return StreamEx.of(elements)
                .map(QueryHelper::toDatabaseNamespace)
                .nonNull()
                .map(d -> (MsDatabase) d.getDelegate())
                .distinct(MsDatabase::getName)
                .toList();
    }

    public static Client client(@NotNull AnActionEvent e) {
        cleanOldClients();
        var dataSource = getSource(e).map(DbImplUtil::getMaybeLocalDataSource)
                .orElseThrow(() -> new IllegalStateException("No SQL Server data source found for this selection"));
        var client = new Client(Objects.requireNonNull(e.getProject(), "No project for this action"), dataSource);
        clients.add(client);
        return client;
    }

    /**
     * Another connection to the same server as {@code of}, tracked for cleanup like any other.
     */
    public static Client sibling(@NotNull Client of) {
        cleanOldClients();
        var client = of.newSession();
        clients.add(client);
        return client;
    }

    public static void cleanOldClients() {
        clients.removeIf(Client::cleanIfDone);
    }
}
