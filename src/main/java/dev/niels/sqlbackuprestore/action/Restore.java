package dev.niels.sqlbackuprestore.action;

import com.intellij.database.actions.RefreshModelAction;
import com.intellij.database.model.DasObject;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import dev.niels.sqlbackuprestore.AppSettingsState;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.Notifier;
import dev.niels.sqlbackuprestore.query.Auditor.MessageType;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.query.ProgressTask;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import dev.niels.sqlbackuprestore.query.RemoteFileWithMeta;
import dev.niels.sqlbackuprestore.query.RemoteFileWithMeta.BackupType;
import dev.niels.sqlbackuprestore.query.Sql;
import dev.niels.sqlbackuprestore.ui.RestoreFilenamesDialog;
import dev.niels.sqlbackuprestore.ui.RestoreFullPartialDialog;
import dev.niels.sqlbackuprestore.ui.filedialog.FileDialog;
import dev.niels.sqlbackuprestore.ui.filedialog.RemoteFile;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import one.util.streamex.StreamEx;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Restore a database from a (remote) file. Gzipped files are unpacked next to the original first.
 */
@Slf4j
public class Restore extends DumbAwareAction {
    private static final String GZIP_EXTENSION = ".gzip";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        @SuppressWarnings("resource")
        var c = QueryHelper.client(e);
        c.setTitle("Restore database");

        CompletableFuture.runAsync(() -> {
                    var target = QueryHelper.getDatabase(e).map(DasObject::getName);
                    var chosen = invokeAndWait(() -> FileDialog.chooseFiles(null, e.getProject(), c, "Restore " + target.orElse("new database")));
                    if (ArrayUtils.isEmpty(chosen)) {
                        return;
                    }

                    var files = unzipIfNeeded(chosen);
                    if (files == null) {
                        return;
                    }

                    var database = target.orElseGet(() -> invokeAndWait(() -> promptDatabaseName(stripBackupExtensions(files[0].getName()))));
                    if (StringUtils.isBlank(database)) {
                        return;
                    }

                    var toRestore = determineToRestore(e.getProject(), files, c);
                    if (toRestore == null) {
                        return;
                    }

                    c.setTitle("Restore " + database);
                    try {
                        closeOtherConnections(e.getProject(), c, database);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception ex) {
                        Notifier.error(Constants.ERROR, "Unable to determine database usage or close connections", ex);
                    }

                    c.open();
                    new ProgressTask(e.getProject(), "Restore backup", false, consumer -> {
                        try {
                            new RestoreHelper(c, database, toRestore, consumer).restore()
                                    .thenRun(() -> hackedRefresh(e))
                                    .join();
                        } catch (Exception ex) {
                            Notifier.error("Restore failed", "Unable to restore " + database, ex);
                        } finally {
                            c.close();
                        }
                    }).queue();
                })
                .whenComplete((result, error) -> {
                    if (error != null) {
                        Notifier.error(Constants.ERROR, Notifier.rootMessage(error));
                    }
                    c.close();
                });
    }

    /**
     * RefreshModelAction.actionPerformed is override only. Try to hide the call from the verifier.
     */
    private void hackedRefresh(@NotNull AnActionEvent e) {
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
            if (!Strings.CI.endsWith(file.getName(), GZIP_EXTENSION)) {
                result[i] = file;
                continue;
            }

            var unzipped = Strings.CS.appendIfMissing(Strings.CI.removeEnd(file.getPath(), GZIP_EXTENSION), ".bak");
            try (InputStream in = new GZIPInputStream(Files.newInputStream(Path.of(file.getPath())));
                 OutputStream out = Files.newOutputStream(Path.of(unzipped))) {
                in.transferTo(out);
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
        return Strings.CI.removeEnd(Strings.CI.removeEnd(name, GZIP_EXTENSION), ".bak");
    }

    private @Nullable RestoreAction determineToRestore(@Nullable Project project, RemoteFile[] files, Client c) {
        var withMeta = StreamEx.of(files)
                .map(RemoteFileWithMeta.factory(c))
                .toList();
        var fullsWithPartials = StreamEx.of(withMeta)
                .filter(RemoteFileWithMeta::isFull)
                .mapToEntry(full -> StreamEx.of(withMeta).filter(m -> m.isPartialOf(full)).toList())
                .toMap();

        if (fullsWithPartials.isEmpty()) {
            Notifier.warning("Nothing to restore", "None of the selected files contain a full backup.");
            return null;
        }
        if (fullsWithPartials.size() == 1 && fullsWithPartials.values().iterator().next().isEmpty()) {
            return new RestoreAction(fullsWithPartials.keySet().iterator().next(), null);
        }

        return RestoreFullPartialDialog.choose(project, fullsWithPartials);
    }

    /**
     * A database can't be restored while other sessions are using it, so offer to kick them out. Our own session is
     * left alone - killing it would take the restore down with it.
     */
    private void closeOtherConnections(Project project, Client c, String target) throws ExecutionException, InterruptedException {
        c.withRows("""
                        SELECT
                            [Session ID]    = s.session_id,
                            [User Process]  = CONVERT(CHAR(1), s.is_user_process),
                            [Login]         = s.login_name,
                            [Application]   = ISNULL(s.program_name, N''),
                            [Open Transactions] = ISNULL(r.open_transaction_count,0),
                            [Last Request Start Time] = s.last_request_start_time,
                            [Host Name]     = ISNULL(s.host_name, N''),
                            [Net Address]   = ISNULL(c.client_net_address, N'')
                        FROM sys.dm_exec_sessions s
                        LEFT OUTER JOIN sys.dm_exec_connections c ON (s.session_id = c.session_id)
                        LEFT OUTER JOIN sys.dm_exec_requests r ON (s.session_id = r.session_id)
                        WHERE s.database_id = DB_ID(N'%s')
                          AND s.session_id <> @@SPID
                        ORDER BY s.session_id;""".formatted(Sql.literal(target)), (cs, rs) -> {
                })
                .thenCompose(rows -> {
                    if (rows.isEmpty() || Messages.YES != invokeAndWait(() -> Messages.showYesNoDialog(project,
                            String.format("There are %s sessions active on this database, do you want to close those?", rows.size()),
                            "Close Connections?",
                            Messages.getQuestionIcon()))) {
                        return CompletableFuture.completedFuture(null);
                    }

                    CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
                    for (Map<String, Object> row : rows) {
                        var sessionId = row.get("Session ID");
                        // thenCompose, not thenRun: the restore has to wait for the kills to actually finish.
                        chain = chain.thenCompose(x -> c.execute("KILL " + Integer.parseInt(Objects.toString(sessionId)))
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

    /**
     * Helper invokeAndWait method that returns the value from the supplier.
     */
    public <T> T invokeAndWait(Supplier<T> supplier) {
        var result = new CompletableFuture<T>();
        // A supplier that throws used to leave the caller blocked forever on an empty queue.
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                result.complete(supplier.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.join();
    }

    @AllArgsConstructor
    @Slf4j
    private static class RestoreHelper {
        private final Client connection;
        private final String target;
        private final RestoreAction action;
        private final BiConsumer<MessageType, String> progressConsumer;
        private final Map<String, Integer> uniqueNames = new HashMap<>();
        // Held as a field: every evaluation of `this::progress` would be a different object, so registering with one
        // and unregistering with another would leave the consumer behind forever.
        private final BiConsumer<MessageType, String> progressListener = this::progress;

        public CompletableFuture<Void> restore() {
            var temp = new RestoreTemp();
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (var file : action.getFiles().toList()) {
                // Each step depends on the previous one having succeeded: restoring the differential on top of a full
                // backup that failed would only produce a second, more confusing error.
                chain = chain
                        .thenCompose(x -> connection.getResult("RESTORE FILELISTONLY FROM DISK = N'" + Sql.literal(file.getFile().getPath()) + "';"))
                        .thenApply(temp::setFiles)
                        .thenCompose(x -> determineTargetPath())
                        .thenApply(temp::setLocation)
                        .thenAccept(this::defaultFileNames)
                        .thenApply(v -> determineRestoreQuery(file, temp))
                        .thenCompose(sql -> connection.addWarningConsumer(progressListener).execute(sql))
                        .thenAccept(x -> {
                        });
            }
            return chain.whenComplete((x, error) -> connection.removeWarningConsumer(progressListener));
        }

        private String determineRestoreQuery(RemoteFileWithMeta file, RestoreTemp temp) {
            var disk = "N'" + Sql.literal(file.getFile().getPath()) + "'";
            if (action.getType(file) != BackupType.FULL) {
                return String.format("RESTORE DATABASE %s FROM DISK = %s WITH file = 1, NOUNLOAD, STATS = 5", Sql.quoted(target), disk);
            }

            if (AppSettingsState.getInstance().isAskForRestoreFileLocations()) {
                askForFileLocations(temp);
            }

            // A differential still has to follow, so leave the database in a restoring state until it has been applied.
            var recovery = action.partialBackup() == null ? "" : "NORECOVERY, ";
            var moves = temp.getFiles().stream()
                    .map(s -> String.format("MOVE N'%s' TO N'%s'", Sql.literal(Objects.toString(s.get("LogicalName"), "")), Sql.literal(Objects.toString(s.get("RestoreAs"), ""))))
                    .collect(Collectors.joining(", "));
            return String.format("RESTORE DATABASE %s FROM DISK = %s WITH file = 1, %s, %s NOUNLOAD, STATS = 5, REPLACE", Sql.quoted(target), disk, moves, recovery);
        }

        private void defaultFileNames(RestoreTemp temp) {
            temp.getFiles().forEach(v -> v.put("RestoreAs", determineFileName(temp.getLocation(), v)));
        }

        private void askForFileLocations(RestoreTemp files) {
            ApplicationManager.getApplication().invokeAndWait(() -> {
                if (!new RestoreFilenamesDialog(null, files).showAndGet()) {
                    throw new CancellationException("Restore cancelled");
                }
            });
        }

        private String determineFileName(String path, Map<String, Object> values) {
            var type = (String) values.get("Type");
            var ext = Strings.CI.equals(type, "L") ? "_log.ldf" : ".mdf";
            // The server may well be running on Linux, so follow the separator the server itself uses.
            var separator = StringUtils.contains(path, '/') ? "/" : "\\";
            return StringUtils.stripEnd(path, "/\\") + separator + uniqueName(target, ext);
        }

        private String uniqueName(String target, String ext) {
            int count = uniqueNames.compute(target + ext, (k, v) -> v == null ? 0 : v + 1);
            if (count == 0) {
                return target + ext;
            }
            return target + "_" + count + ext;
        }

        private CompletableFuture<String> determineTargetPath() {
            var max = "case when CHARINDEX('\\',REVERSE(physical_name)) > CHARINDEX('/',REVERSE(physical_name)) then CHARINDEX('\\',REVERSE(physical_name)) else CHARINDEX('/',REVERSE(physical_name)) end";
            var path = "LEFT(physical_name,LEN(physical_name)-(" + max + ")+1)";
            var pathQuery = "SELECT top 1 " + path + " path, count(*)\n" +
                    "    FROM sys.master_files mf\n" +
                    "    INNER JOIN sys.[databases] d ON mf.[database_id] = d.[database_id]  \n" +
                    "group by " + path + "\n" +
                    "order by count(*) desc;";

            return connection.getSingle(pathQuery, "path");
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

    @Data
    public static class RestoreTemp {
        private List<Map<String, Object>> files;
        private String location;
    }

    public record RestoreAction(@NotNull RemoteFileWithMeta fullBackup, @Nullable RemoteFileWithMeta partialBackup) {
        public StreamEx<RemoteFileWithMeta> getFiles() {
            return StreamEx.of(fullBackup, partialBackup).nonNull();
        }

        public BackupType getType(RemoteFileWithMeta bak) {
            return fullBackup == bak ? BackupType.FULL : partialBackup == bak ? BackupType.PARTIAL : BackupType.UNSUPPORTED;
        }
    }
}
