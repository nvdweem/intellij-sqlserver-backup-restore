package dev.niels.sqlbackuprestore.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.util.concurrency.annotations.RequiresEdt;
import dev.niels.sqlbackuprestore.AppSettingsState;
import dev.niels.sqlbackuprestore.Notifier;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.query.ProgressTask;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import dev.niels.sqlbackuprestore.query.Sql;
import dev.niels.sqlbackuprestore.ui.filedialog.FileDialog;
import dev.niels.sqlbackuprestore.ui.filedialog.RemoteFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Backup database to a file
 */
@Slf4j
public class Backup extends DumbAwareAction {
    // https://docs.microsoft.com/en-us/sql/t-sql/functions/serverproperty-transact-sql?view=sql-server-ver15
    // https://docs.microsoft.com/en-us/sql/sql-server/editions-and-components-of-sql-server-version-15?view=sql-server-ver15#RDBMSHA
    private static final Set<String> editionIdsWithoutCompressionSupport = Set.of(
            "-1592396055", // Express
            "-133711905", // Express with Advanced Services
            "1293598313" // Web
    );

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ApplicationManager.getApplication().invokeLater(() -> {
            var c = QueryHelper.client(e);
            try {
                c.setTitle("Backup database");
                backup(e, c);
            } finally {
                // backup() takes a hold of its own when it has something to do; this one is ours.
                c.release();
            }
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(QueryHelper.getDatabase(e).isPresent());
    }

    /**
     * Asks for a file on the server and backs the selected database up to it. The dialog is opened synchronously,
     * hence the event thread; the backup itself runs as a task and the future completes when it is done.
     *
     * @param e the event that triggered the action; the database is taken from it
     * @param c the connection to back up over. A hold is taken on it for as long as the backup runs, so the caller
     *          keeps its own hold and releases that separately.
     * @return the file that was written, or {@code null} if the user chose no file or there was no database to back up
     */
    @RequiresEdt
    protected CompletableFuture<RemoteFile> backup(@NotNull AnActionEvent e, Client c) {
        var database = QueryHelper.getDatabase(e);
        if (database.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        var name = database.get().getName();
        var target = FileDialog.saveFile(name + ".bak", e.getProject(), c, "Backup " + database.get() + " to file");
        if (target == null) {
            return CompletableFuture.completedFuture(null);
        }

        c.acquire();
        c.setTitle("Backup " + name);

        var future = determineCompression(c)
                .thenCompose(compress -> c.execute("BACKUP DATABASE " + Sql.quoted(name) + " TO  DISK = N'" + Sql.literal(target.getPath())
                        + "' WITH COPY_ONLY, NOFORMAT, INIT, SKIP, NOREWIND, NOUNLOAD" + compress + ", STATS = 10"))
                // The size is only used to decide whether to offer extra compression when downloading, so a database
                // that won't report it shouldn't fail a backup that already succeeded.
                .thenCompose(x -> databaseSize(c, name).exceptionally(t -> null))
                .thenApply(size -> size == null ? target : target.setLength(size))
                .whenComplete((result, error) -> c.release());

        new ProgressTask(e.getProject(), "Creating backup", false, consumer -> {
            c.addWarningConsumer(consumer);
            try {
                future.join();
            } catch (Exception ex) {
                Notifier.error("Backup failed", "Unable to back up " + name, ex);
            } finally {
                c.removeWarningConsumer(consumer);
            }
        }).queue();
        return future;
    }

    /**
     * Size of the database itself (not of the backup file), used to decide whether extra compression is worth offering.
     */
    private CompletableFuture<Long> databaseSize(Client c, String name) {
        return c.getSingle("USE " + Sql.quoted(name) + " exec sp_spaceused @oneresultset = 1", "reserved", String.class)
                .thenApply(reserved -> Long.parseLong(Strings.CS.removeEnd(StringUtils.trimToEmpty(reserved), " KB").trim()) * 1024);
    }

    private CompletableFuture<String> determineCompression(Client c) {
        if (!AppSettingsState.getInstance().isUseCompressedBackup()) {
            return CompletableFuture.completedFuture("");
        }
        // EditionID is supposed to be a bigint but returns as String. Cast to be super sure.
        return c.getSingle("SELECT cast(SERVERPROPERTY('EditionID') as varchar(20)) AS edition", "edition", String.class)
                .thenApply(id -> {
                    var result = !editionIdsWithoutCompressionSupport.contains(id);
                    log.info("Version {} does {}support compression", id, result ? "" : "not ");
                    return result;
                })
                .thenApply(compress -> compress ? ", COMPRESSION" : "");
    }
}
