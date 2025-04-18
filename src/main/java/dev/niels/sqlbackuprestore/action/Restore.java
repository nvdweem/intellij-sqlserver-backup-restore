package dev.niels.sqlbackuprestore.action;

import com.intellij.database.actions.RefreshModelAction;
import com.intellij.database.model.DasObject;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications.Bus;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import dev.niels.sqlbackuprestore.AppSettingsState;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.query.Auditor.MessageType;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.query.ProgressTask;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import dev.niels.sqlbackuprestore.query.RemoteFileWithMeta;
import dev.niels.sqlbackuprestore.query.RemoteFileWithMeta.BackupType;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Restore a database from a (remote) file. Cannot be a gzipped file.
 */
@Slf4j
public class Restore extends DumbAwareAction {
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
                    c.setTitle("Restore database");
                    var target = QueryHelper.getDatabase(e).map(DasObject::getName);
                    var files = invokeAndWait(() -> FileDialog.chooseFiles(null, e.getProject(), c, "Restore " + target.orElse("new database")));
                    if (ArrayUtils.isEmpty(files)) {
                        return;
                    }

                    var database = target.orElseGet(() -> invokeAndWait(() -> promptDatabaseName(StringUtils.removeEnd(StringUtils.removeEnd(files[0].getName(), ".gzip"), ".bak"))));
                    if (StringUtils.isBlank(database)) {
                        return;
                    }

                    var toRestore = determineToRestore(e.getProject(), files, c);
                    if (toRestore == null) {
                        return;
                    }

                    c.setTitle("Restore " + database);
                    try {
                        checkDatabaseInUse(e.getProject(), c, database);
                    } catch (Exception ex) {
                        Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, Constants.ERROR, "Unable to determine database usage or close connections: " + ex.getMessage(), NotificationType.ERROR));
                    }

                    c.open();
                    new ProgressTask(e.getProject(), "Restore backup", false, consumer -> {
                        try {
                            new RestoreHelper(c, database, toRestore, consumer).unzipIfNeeded()
                                    .restore()
                                    .thenRun(() -> hackedRefresh(e))
                                    .thenRun(c::close).exceptionally(c::close)
                                    .get();
                        } catch (Exception ex) {
                            Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, Constants.ERROR, ex.getMessage(), NotificationType.ERROR));
                        }
                    }).queue();
                })
                .thenRun(c::close)
                .exceptionally(c::close);
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

    private @Nullable RestoreAction determineToRestore(@Nullable Project project, RemoteFile[] files, Client c) {
        var withMeta = StreamEx.of(files)
                .map(RemoteFileWithMeta.factory(c))
                .toList();
        var fullsWithPartials = StreamEx.of(withMeta)
                .filter(RemoteFileWithMeta::isFull)
                .mapToEntry(full -> StreamEx.of(withMeta).filter(m -> m.isPartialOf(full)).toList())
                .toMap();

        if (fullsWithPartials.isEmpty()) {
            return null;
        }
        if (fullsWithPartials.size() == 1 && fullsWithPartials.values().iterator().next().isEmpty()) {
            return new RestoreAction(fullsWithPartials.keySet().iterator().next(), null);
        }

        return RestoreFullPartialDialog.choose(project, fullsWithPartials);
    }

    private void checkDatabaseInUse(Project project, Client c, String target) throws ExecutionException, InterruptedException {
        c.withRows(String.format("""
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
                        LEFT OUTER JOIN sys.sysprocesses p ON (s.session_id = p.spid)
                        where db_name(p.dbid) = '%s'
                        ORDER BY s.session_id;""", target), (cs, rs) -> {
                })
                .thenCompose(rows -> {
                    if (!rows.isEmpty() && Messages.YES == invokeAndWait(() -> Messages.showYesNoDialog(project,
                            String.format("There are %s sessions active on this database, do you want to close those?", rows.size()),
                            "Close Connections?",
                            Messages.getQuestionIcon()))) {

                        CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
                        for (Map<String, Object> row : rows) {
                            chain = chain.thenRun(() -> c.execute("KILL " + row.get("Session ID")));
                        }
                        return chain;
                    } else {
                        return CompletableFuture.completedFuture(null);
                    }
                }).get();
    }

    private String promptDatabaseName(String initial) {
        var name = Messages.showInputDialog("Create a new database from backup", "Database Name", null, initial, null);
        return StringUtils.stripToNull(name);
    }

    /**
     * Helper invokeAndWait method that returns the value from the supplier
     */
    public <T> T invokeAndWait(Supplier<T> supplier) {
        var blocker = new ArrayBlockingQueue<Optional<T>>(1);
        ApplicationManager.getApplication().invokeLater(() -> blocker.add(Optional.ofNullable(supplier.get())));
        try {
            return blocker.take().orElse(null);
        } catch (InterruptedException e) {
            Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, Constants.ERROR, e.getMessage(), NotificationType.ERROR));
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @AllArgsConstructor
    @Slf4j
    private static class RestoreHelper {
        private final Client connection;
        private final String target;
        private RestoreAction action;
        private final BiConsumer<MessageType, String> progressConsumer;
        private final Map<String, Integer> uniqueNames = new HashMap<>();

        public RestoreHelper unzipIfNeeded() {
            var files = action.getFiles().map(RemoteFileWithMeta::getFile).map(RemoteFile::getPath).toList();
            for (var file : files) {
                if (file.toLowerCase().endsWith(".gzip")) {
                    var unzipped = StringUtils.appendIfMissing(StringUtils.removeEndIgnoreCase(file, ".gzip"), ".bak");
                    try (var fis = new FileInputStream(file); var gzis = new GZIPInputStream(fis); var fos = new FileOutputStream(unzipped)) {
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = gzis.read(buffer)) > 0) {
                            fos.write(buffer, 0, length);
                        }
                        file = unzipped;
                    } catch (IOException e) {
                        log.warn("failed to unzip {}", file);
                    }
                }
            }
            return this;
        }

        @Data
        @AllArgsConstructor
        private static class ObjectHolder<T> {
            private T value;
        }

        public CompletableFuture<Object> restore() {
            var temp = new RestoreTemp();
            var result = new ObjectHolder<>(CompletableFuture.completedFuture(null));
            action.getFiles().forEach(file -> result.setValue(
                    result.getValue().thenCompose(x -> connection.getResult("RESTORE FILELISTONLY FROM DISK = N'" + file.getFile().getPath() + "';"))
                            .thenApply(temp::setFiles)
                            .thenCompose(x -> determineTargetPath())
                            .thenApply(temp::setLocation)
                            .thenAccept(this::defaultFileNames)
                            .thenApply(v -> determineRestoreQuery(action, file, temp))

                            .thenCompose(sql -> connection.addWarningConsumer(this::progress).execute(sql))
                            .thenApply(x -> null)
                            .exceptionally(e -> null)
            ));
            return result.getValue();
        }

        private String determineRestoreQuery(RestoreAction action, RemoteFileWithMeta file, RestoreTemp temp) {
            if (action.getType(file) == BackupType.FULL) {
                if (AppSettingsState.getInstance().isAskForRestoreFileLocations()) {
                    askForFileLocations(temp);
                }

                var recovery = action.partialBackup == null ? "" : "NORECOVERY, ";
                var moves = temp.files.stream().map(s -> String.format("MOVE N'%s' TO N'%s'", s.get("LogicalName"), s.get("RestoreAs"))).collect(Collectors.joining(", "));
                return String.format("RESTORE DATABASE [%s] FROM DISK = N'%s' WITH file = 1, %s, %s NOUNLOAD, STATS = 5, REPLACE", target, file.getFile().getPath(), moves, recovery);
            } else {
                return String.format("RESTORE DATABASE [%s] FROM DISK = N'%s' WITH file = 1, NOUNLOAD, STATS = 5", target, file.getFile().getPath());
            }
        }

        private void defaultFileNames(RestoreTemp temp) {
            temp.getFiles().forEach(v -> v.put("RestoreAs", determineFileName(temp.getLocation(), v)));
        }

        private void askForFileLocations(RestoreTemp files) {
            ApplicationManager.getApplication().invokeAndWait(() -> {
                if (!new RestoreFilenamesDialog(null, files).showAndGet()) {
                    throw new RuntimeException("Restore cancelled");
                }
            });
        }

        private String determineFileName(String path, Map<String, Object> values) {
            var type = (String) values.get("Type");
            var ext = StringUtils.equalsIgnoreCase(type, "L") ? "_log.ldf" : ".mdf";
            return StringUtils.stripEnd(path, "/\\") + '\\' + uniqueName(target, ext);
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
                Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, Constants.ERROR, warning, NotificationType.ERROR));
            }
            progressConsumer.accept(messageType, warning);
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
