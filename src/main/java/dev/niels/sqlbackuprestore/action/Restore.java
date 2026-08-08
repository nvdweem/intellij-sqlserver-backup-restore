package dev.niels.sqlbackuprestore.action;

import com.intellij.database.actions.RefreshModelAction;
import com.intellij.database.model.DasObject;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread;
import dev.niels.sqlbackuprestore.AppSettingsState;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.Edt;
import dev.niels.sqlbackuprestore.Notifier;
import dev.niels.sqlbackuprestore.query.Auditor.MessageType;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.query.Interrupter;
import dev.niels.sqlbackuprestore.query.ProgressTask;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import dev.niels.sqlbackuprestore.query.RemoteFileWithMeta;
import dev.niels.sqlbackuprestore.query.RemoteFileWithMeta.BackupType;
import dev.niels.sqlbackuprestore.query.RestoreFile;
import dev.niels.sqlbackuprestore.query.Statements;
import dev.niels.sqlbackuprestore.ui.RestoreFilenamesDialog;
import dev.niels.sqlbackuprestore.ui.SelectBackupDialog;
import dev.niels.sqlbackuprestore.ui.filedialog.FileDialog;
import dev.niels.sqlbackuprestore.ui.filedialog.RemoteFile;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import one.util.streamex.StreamEx;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

/**
 * Restore a database from a (remote) file. Gzipped files are unpacked next to the original first.
 */
@Slf4j
public class Restore extends DumbAwareAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var c = QueryHelper.client(e);
        c.setTitle("Restore database");

        CompletableFuture.runAsync(() -> prepare(e, c))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        Notifier.error(Constants.ERROR, Notifier.rootMessage(error));
                    }
                    c.release();
                });
    }

    /**
     * Works out what to restore and where to, then hands over to {@link #queueRestore}. Runs off the event thread
     * because it waits on the server between questions; each question is put back on the event thread individually.
     * Returning early anywhere here means the user backed out, which is not worth reporting.
     */
    @RequiresBackgroundThread
    private void prepare(@NotNull AnActionEvent e, Client c) {
        var existing = QueryHelper.getDatabase(e).map(DasObject::getName);

        var chosen = Edt.compute(() -> FileDialog.chooseFiles(null, e.getProject(), c, "Restore " + existing.orElse("new database")));
        if (ArrayUtils.isEmpty(chosen)) {
            return;
        }

        var files = unzipIfNeeded(chosen);
        if (files == null) {
            return;
        }

        var database = existing.orElseGet(() -> Edt.compute(() -> promptDatabaseName(stripBackupExtensions(files[0].getName()))));
        if (StringUtils.isBlank(database)) {
            return;
        }

        var toRestore = chooseBackup(e.getProject(), files, c);
        if (toRestore == null) {
            return;
        }

        c.setTitle("Restore " + database);
        if (!closeOtherConnections(e.getProject(), c, database)) {
            return;
        }

        queueRestore(e, c, database, toRestore);
    }

    private void queueRestore(@NotNull AnActionEvent e, Client c, String database, RestoreAction toRestore) {
        // Handed to the task, which outlives this method.
        c.acquire();
        var interrupter = new Interrupter(c);

        new ProgressTask(e.getProject(), "Restore backup", interrupter::interrupt, consumer -> {
            try {
                interrupter.rememberSession()
                        .thenCompose(x -> new RestoreHelper(c, database, toRestore, consumer).restore())
                        .thenRun(() -> refreshDatabaseTree(e))
                        .join();
            } catch (Exception ex) {
                reportRestoreFailure(interrupter.wasInterrupted(), database, ex);
            } finally {
                c.release();
            }
        }).queue();
    }

    /**
     * Killing a RESTORE does not undo it. The database is left in the RESTORING state, where it is inaccessible until
     * somebody either finishes the chain or drops it - so the notification has to say what to do about that.
     */
    static void reportRestoreFailure(boolean interrupted, String database, Exception ex) {
        if (interrupted) {
            Notifier.warning("Restore cancelled", "Restoring " + database + " was stopped part-way.\n"
                    + "The database is left in the 'restoring' state; drop it, or restore it again to completion.");
        } else {
            Notifier.error("Restore failed", "Unable to restore " + database, ex);
        }
    }

    /**
     * Makes the Database view pick up the database that was just restored. Called through reflection because
     * RefreshModelAction.actionPerformed is override-only, which the plugin verifier would otherwise object to.
     */
    private void refreshDatabaseTree(@NotNull AnActionEvent e) {
        try {
            var refreshAction = new RefreshModelAction();
            var actionPerformed = RefreshModelAction.class.getMethod("actionPerformed", AnActionEvent.class);
            actionPerformed.invoke(refreshAction, e);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
            log.error("Unable to refresh selected item");
        }
    }

    /**
     * SQL Server can't read a gzipped backup, so unpack it alongside the original and restore that instead. This only
     * works when the chosen path is reachable from this machine as well (a local server, or a share), which is the same
     * situation in which the download action produced the .gzip in the first place.
     *
     * @return the files to restore, or {@code null} when unpacking failed and the user was told about it.
     */
    private RemoteFile @Nullable [] unzipIfNeeded(RemoteFile @NotNull [] files) {
        var result = new RemoteFile[files.length];
        for (var i = 0; i < files.length; i++) {
            var file = files[i];
            if (!Gzip.isGzipped(file.getName())) {
                result[i] = file;
                continue;
            }

            String unzipped;
            try {
                unzipped = Gzip.unpack(file.getPath());
            } catch (IOException | RuntimeException ex) {
                log.warn("Failed to unzip {}", file.getPath(), ex);
                Notifier.error("Unable to unpack backup", "Could not unpack " + file.getPath() + ":\n" + ex.getMessage()
                        + "\nUnpacking happens on this machine, so the file has to be reachable from here.");
                return null;
            }

            var parent = file.getParent();
            result[i] = parent instanceof RemoteFile remoteParent
                    ? (RemoteFile) remoteParent.getChild(Path.of(unzipped).getFileName().toString(), true)
                    : file;
        }
        return result;
    }

    private static String stripBackupExtensions(String name) {
        return Strings.CI.removeEnd(Strings.CI.removeEnd(name, Gzip.EXTENSION), ".bak");
    }

    private @Nullable RestoreAction chooseBackup(@Nullable Project project, RemoteFile[] files, Client c) {
        var withMeta = StreamEx.of(files)
                .map(RemoteFileWithMeta.factory(c))
                .toList();
        var fullsWithDifferentials = StreamEx.of(withMeta)
                .filter(RemoteFileWithMeta::isFull)
                .mapToEntry(full -> StreamEx.of(withMeta).filter(m -> m.isDifferentialOf(full)).toList())
                .toMap();

        if (fullsWithDifferentials.isEmpty()) {
            Notifier.warning("Nothing to restore", "None of the selected files contain a full backup.");
            return null;
        }
        if (fullsWithDifferentials.size() == 1 && fullsWithDifferentials.values().iterator().next().isEmpty()) {
            return new RestoreAction(fullsWithDifferentials.keySet().iterator().next(), null);
        }

        return SelectBackupDialog.choose(project, fullsWithDifferentials);
    }

    /**
     * A database can't be restored while other sessions are using it, so offer to kick them out. Our own session is
     * left alone - killing it would take the restore down with it.
     *
     * @return whether the restore should go ahead. Failing to list or close the sessions is worth telling the user
     * about, but the restore may well still work, so only an interruption stops it.
     */
    private boolean closeOtherConnections(Project project, Client c, String target) {
        try {
            killSessionsOn(project, c, target);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            Notifier.error(Constants.ERROR, "Unable to determine database usage or close connections", ex);
        }
        return true;
    }

    private void killSessionsOn(Project project, Client c, String target) throws ExecutionException, InterruptedException {
        c.withRows(Statements.sessionsOn(target), (cs, rs) -> {
                })
                .thenCompose(rows -> {
                    if (rows.isEmpty() || Messages.YES != Edt.compute(() -> Messages.showYesNoDialog(project,
                            String.format("There are %s sessions active on this database, do you want to close those?", rows.size()),
                            "Close Connections?",
                            Messages.getQuestionIcon()))) {
                        return CompletableFuture.completedFuture(null);
                    }

                    CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
                    for (Map<String, Object> row : rows) {
                        var sessionId = row.get("Session ID");
                        // thenCompose, not thenRun: the restore has to wait for the kills to actually finish.
                        chain = chain.thenCompose(x -> c.execute(Statements.kill(Integer.parseInt(Objects.toString(sessionId))))
                                // A session that disconnected on its own in the meantime is not a problem.
                                .exceptionally(t -> null));
                    }
                    return chain;
                }).get();
    }

    private String promptDatabaseName(String initial) {
        var name = Messages.showInputDialog("Create a new database from backup", "Database Name", null, initial, null);
        return StringUtils.stripToNull(name);
    }

    @AllArgsConstructor
    @Slf4j
    private static class RestoreHelper {
        private final Client connection;
        private final String target;
        private final RestoreAction action;
        private final BiConsumer<MessageType, String> progressConsumer;
        // Held as a field: every evaluation of `this::progress` would be a different object, so registering with one
        // and unregistering with another would leave the consumer behind forever.
        private final BiConsumer<MessageType, String> progressListener = this::progress;

        public CompletableFuture<?> restore() {
            // Registered once for the whole run. Registering per statement (which is what chaining it onto each
            // execute did) left one registration behind for every file after the first.
            connection.addWarningConsumer(progressListener);

            CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
            for (var backup : action.getFiles().toList()) {
                // Each step depends on the previous one having succeeded: restoring the differential on top of a full
                // backup that failed would only produce a second, more confusing error.
                chain = chain.thenCompose(x -> restoreOne(backup));
            }
            return chain.whenComplete((x, error) -> connection.removeWarningConsumer(progressListener));
        }

        private CompletableFuture<?> restoreOne(RemoteFileWithMeta backup) {
            return statementFor(backup).thenCompose(connection::execute);
        }

        /**
         * Only a full backup needs to know what is inside it: it is restored WITH MOVE, to paths the user may want to
         * change first. A differential goes on top of the files the full restore has already put in place and names
         * none of them, so it needs neither the file list nor the data directory.
         */
        private CompletableFuture<String> statementFor(RemoteFileWithMeta backup) {
            var path = backup.getFile().getPath();
            if (action.getType(backup) != BackupType.FULL) {
                return CompletableFuture.completedFuture(Statements.restoreDifferential(target, path));
            }

            return readFileList(backup)
                    .thenCompose(files -> defaultDataDirectory()
                            .thenApply(directory -> RestoreFile.assignDefaultTargets(files, directory, target)))
                    .thenApply(files -> fullRestoreStatement(path, files));
        }

        private CompletableFuture<List<RestoreFile>> readFileList(RemoteFileWithMeta backup) {
            return connection.getResult(Statements.fileListOnly(backup.getFile().getPath()))
                    .thenApply(RestoreFile::from);
        }

        private String fullRestoreStatement(String path, List<RestoreFile> files) {
            if (AppSettingsState.getInstance().isAskForRestoreFileLocations()) {
                askForFileLocations(files);
            }

            // A differential still has to follow, so leave the database in a restoring state until it has been applied.
            return Statements.restoreFull(target, path, files, action.differentialBackup() != null);
        }

        private void askForFileLocations(List<RestoreFile> files) {
            ApplicationManager.getApplication().invokeAndWait(() -> {
                if (!new RestoreFilenamesDialog(null, files).showAndGet()) {
                    throw new CancellationException("Restore cancelled");
                }
            });
        }

        private CompletableFuture<String> defaultDataDirectory() {
            return connection.getSingle(Statements.DEFAULT_DATA_DIRECTORY, "path", String.class);
        }

        private void progress(MessageType messageType, String warning) {
            if (messageType == MessageType.ERROR) {
                Notifier.error(Constants.ERROR, warning);
            }
            progressConsumer.accept(messageType, warning);
        }
    }

    /**
     * Thrown when the user backs out of one of the restore dialogs; nothing to report, just stop.
     */
    private static class CancellationException extends RuntimeException {
        CancellationException(String message) {
            super(message);
        }
    }

    public record RestoreAction(@NotNull RemoteFileWithMeta fullBackup, @Nullable RemoteFileWithMeta differentialBackup) {
        public StreamEx<RemoteFileWithMeta> getFiles() {
            return StreamEx.of(fullBackup, differentialBackup).nonNull();
        }

        public BackupType getType(RemoteFileWithMeta bak) {
            return fullBackup == bak ? BackupType.FULL : differentialBackup == bak ? BackupType.DIFFERENTIAL : BackupType.UNSUPPORTED;
        }
    }
}
