package dev.niels.sqlbackuprestore.action;

import com.intellij.database.model.DasObject;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.util.concurrency.annotations.RequiresEdt;
import dev.niels.sqlbackuprestore.AppSettingsState;
import dev.niels.sqlbackuprestore.Notifier;
import dev.niels.sqlbackuprestore.ServerPath;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.query.Interrupter;
import dev.niels.sqlbackuprestore.query.ProgressTask;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import dev.niels.sqlbackuprestore.query.Statements;
import dev.niels.sqlbackuprestore.ui.filedialog.FileDialog;
import dev.niels.sqlbackuprestore.ui.filedialog.RemoteFile;
import lombok.extern.slf4j.Slf4j;
import one.util.streamex.StreamEx;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

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
                var databases = QueryHelper.getDatabases(e);
                if (databases.size() > 1) {
                    backupAll(e, c, StreamEx.of(databases).map(DasObject::getName).toList());
                } else {
                    c.setTitle("Backup database");
                    backup(e, c);
                }
            } finally {
                // The methods above take a hold of their own when they have something to do; this one is ours.
                c.release();
            }
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        var databases = QueryHelper.getDatabases(e);
        e.getPresentation().setEnabled(!databases.isEmpty());
        e.getPresentation().setText(databases.size() > 1 ? "Backup " + databases.size() + " Databases" : "Backup");
    }

    /**
     * Backs several databases up into one directory, each to {@code <name>.bak}. Asking for a filename per database
     * would be the obvious extension of the single-database flow and also the most tedious thing imaginable.
     */
    @RequiresEdt
    private void backupAll(@NotNull AnActionEvent e, Client c, List<String> databases) {
        var folder = FileDialog.chooseFolder(e.getProject(), c, "Back Up " + databases.size() + " Databases To Folder");
        if (folder == null) {
            return;
        }

        c.acquire();
        c.setTitle("Backup " + databases.size() + " databases");
        var interrupter = new Interrupter(c);
        var done = new AtomicInteger();

        new ProgressTask(e.getProject(), "Creating backups", interrupter::interrupt, consumer -> {
            c.addWarningConsumer(consumer);
            try {
                backupEachTo(c, folder.getPath(), databases, interrupter, done).join();
                Notifier.information("Backup finished", "Backed up " + databases.size() + " databases to " + folder.getPath() + ".");
            } catch (Exception ex) {
                reportMultiBackupFailure(interrupter.wasInterrupted(), done.get(), databases.size(), folder.getPath(), ex);
            } finally {
                c.removeWarningConsumer(consumer);
                c.release();
            }
        }).queue();
    }

    /**
     * One database at a time: they share a connection, and running them together would only make them compete for the
     * same disk while making the progress percentages meaningless.
     */
    private CompletableFuture<?> backupEachTo(Client c, String folder, List<String> databases, Interrupter interrupter, AtomicInteger done) {
        return interrupter.rememberSession()
                .thenCompose(x -> determineCompression(c))
                .thenCompose(compress -> {
                    CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
                    for (var database : databases) {
                        chain = chain.thenCompose(x -> {
                            c.setTitle("Backup " + database);
                            return backupTo(c, database, ServerPath.join(folder, database + ".bak"), compress)
                                    .thenRun(done::incrementAndGet);
                        });
                    }
                    return chain;
                });
    }

    private CompletableFuture<?> backupTo(Client c, String database, String path, boolean compress) {
        return c.execute(Statements.backup(database, path, compress));
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

        var interrupter = new Interrupter(c);
        var future = interrupter.rememberSession()
                .thenCompose(x -> determineCompression(c))
                .thenCompose(compress -> backupTo(c, name, target.getPath(), compress))
                // The size is only used to decide whether to offer extra compression when downloading, so a database
                // that won't report it shouldn't fail a backup that already succeeded.
                .thenCompose(x -> databaseSize(c, name).exceptionally(t -> null))
                .thenApply(size -> size == null ? target : target.setLength(size))
                .whenComplete((result, error) -> c.release());

        new ProgressTask(e.getProject(), "Creating backup", interrupter::interrupt, consumer -> {
            c.addWarningConsumer(consumer);
            try {
                future.join();
            } catch (Exception ex) {
                reportBackupFailure(interrupter.wasInterrupted(), name, target.getPath(), ex);
            } finally {
                c.removeWarningConsumer(consumer);
            }
        }).queue();
        return future;
    }

    /**
     * A killed BACKUP leaves whatever it had already written behind, so the file on the server exists but is not a
     * usable backup. Saying so is the difference between the user deleting it and the user trusting it.
     *
     * @param interrupted whether the user cancelled, which is the difference between a warning and an error - a
     *                    cancellation is not a failure and must not be reported as one.
     */
    static void reportBackupFailure(boolean interrupted, String name, String path, Exception ex) {
        if (interrupted) {
            Notifier.warning("Backup cancelled", "Backing up " + name + " was stopped.\n"
                    + path + " is incomplete and should be deleted.");
        } else {
            Notifier.error("Backup failed", "Unable to back up " + name, ex);
        }
    }

    /**
     * The multi-database equivalent, which also has to say how far it got: the databases already finished are complete
     * and usable, and only the one that was running is not.
     */
    static void reportMultiBackupFailure(boolean interrupted, int done, int total, String folder, Exception ex) {
        if (interrupted) {
            Notifier.warning("Backup cancelled", done + " of " + total
                    + " databases were backed up to " + folder + ".\nThe one that was running when you cancelled is incomplete.");
        } else {
            Notifier.error("Backup failed", "Unable to back up to " + folder, ex);
        }
    }

    /**
     * Size of the database itself (not of the backup file), used to decide whether extra compression is worth offering.
     */
    private CompletableFuture<Long> databaseSize(Client c, String name) {
        return c.getSingle(Statements.spaceUsed(name), "reserved", String.class)
                .thenApply(reserved -> Long.parseLong(Strings.CS.removeEnd(StringUtils.trimToEmpty(reserved), " KB").trim()) * 1024);
    }

    private CompletableFuture<Boolean> determineCompression(Client c) {
        if (!AppSettingsState.getInstance().isUseCompressedBackup()) {
            return CompletableFuture.completedFuture(false);
        }
        return c.getSingle(Statements.EDITION_ID, "edition", String.class)
                .thenApply(id -> {
                    var result = supportsCompression(id);
                    log.info("Version {} does {}support compression", id, result ? "" : "not ");
                    return result;
                });
    }

    /**
     * Express, 'Express with Advanced Services' and Web don't support backup compression, and asking them for it fails
     * the whole backup rather than being ignored.
     */
    public static boolean supportsCompression(String editionId) {
        return !editionIdsWithoutCompressionSupport.contains(editionId);
    }
}
