package dev.niels.sqlbackuprestore.action;

import com.intellij.database.actions.RefreshSchemaAction;
import com.intellij.database.model.DasObject;
import com.intellij.database.view.DatabaseView;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.query.Auditor;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.query.ProgressTask;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import dev.niels.sqlbackuprestore.ui.FileDialog;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Restore a database from a (remote) file. Cannot be a gzipped file.
 */
@Slf4j
public class Restore extends AnAction implements DumbAware {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var c = QueryHelper.client(e);
        c.setTitle("Restore database");

        CompletableFuture.runAsync(() -> {
            var target = QueryHelper.getDatabase(e).map(DasObject::getName).orElseGet(() -> invokeAndWait(this::promptDatabaseName));
            if (target == null) {
                return;
            }

            c.setTitle("Restore " + target);
            var file = invokeAndWait(() -> FileDialog.chooseFile(null, e.getProject(), c, "Restore database", "Select a file to restore to '" + target + "'", FileDialog.DialogType.LOAD));
            if (file == null) {
                return;
            }

            try {
                checkDatabaseInUse(e.getProject(), c, target);
            } catch (Exception ex) {
                Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, Constants.ERROR, "Unable to determine database usage or close connections: " + ex.getMessage(), NotificationType.ERROR));
            }

            c.open();
            new ProgressTask(e.getProject(), "Restore backup", false, consumer -> {
                try {
                    new RestoreHelper(c, target, file.getPath(), consumer).unzipIfNeeded()
                                                                          .restore()
                                                                          .thenRun(() -> RefreshSchemaAction.refresh(e.getProject(), DatabaseView.getSelectedElementsNoGroups(e.getDataContext(), true)))
                                                                          .thenRun(c::close).exceptionally(c::close)
                                                                          .get();
                } catch (Exception ex) {
                    Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, Constants.ERROR, ex.getMessage(), NotificationType.ERROR));
                }
            }).queue();
        })
                         .thenRun(c::close)
                         .exceptionally(c::close);
    }

    private void checkDatabaseInUse(Project project, Client c, String target) throws ExecutionException, InterruptedException {
        c.withRows(String.format("SELECT\n" +
                "    [Session ID]    = s.session_id,\n" +
                "    [User Process]  = CONVERT(CHAR(1), s.is_user_process),\n" +
                "    [Login]         = s.login_name,\n" +
                "    [Application]   = ISNULL(s.program_name, N''),\n" +
                "    [Open Transactions] = ISNULL(r.open_transaction_count,0),\n" +
                "    [Last Request Start Time] = s.last_request_start_time,\n" +
                "    [Host Name]     = ISNULL(s.host_name, N''),\n" +
                "    [Net Address]   = ISNULL(c.client_net_address, N'')\n" +
                "FROM sys.dm_exec_sessions s\n" +
                "LEFT OUTER JOIN sys.dm_exec_connections c ON (s.session_id = c.session_id)\n" +
                "LEFT OUTER JOIN sys.dm_exec_requests r ON (s.session_id = r.session_id)\n" +
                "LEFT OUTER JOIN sys.sysprocesses p ON (s.session_id = p.spid)\n" +
                "where db_name(p.dbid) = '%s'\n" +
                "ORDER BY s.session_id;", target), x -> {
        })
         .thenCompose(rows -> {
             if (!rows.isEmpty() && Messages.YES == invokeAndWait(() -> Messages.showYesNoDialog(project,
                     String.format("There are %s sessions active on this database, do you want to close those?", rows.size()),
                     "Close connections?",
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

    private String promptDatabaseName() {
        var name = Messages.showInputDialog("Create a new database from backup", "Database name", null);
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
            Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, Constants.ERROR, e.getMessage(), NotificationType.ERROR));
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @AllArgsConstructor
    @Slf4j
    private static class RestoreHelper {
        private final Client connection;
        private final String target;
        private String file;
        private final Consumer<Pair<Auditor.MessageType, String>> progressConsumer;
        private final Map<String, Integer> uniqueNames = new HashMap<>();

        public RestoreHelper unzipIfNeeded() {
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
            return this;
        }

        public CompletableFuture<Void> restore() {
            return connection.getResult("RESTORE FILELISTONLY FROM DISK = N'" + file + "';")
                             .thenCompose(this::createRestoreQuery)
                             .thenCompose(sql -> connection.addWarningConsumer(this::progress).execute(sql))
                             .thenApply(x -> null);
        }

        private CompletableFuture<String> createRestoreQuery(List<Map<String, Object>> maps) {
            return determineTargetPath()
                    .thenApply(path -> maps.stream().map(v -> determineFileName(path, v)).collect(Collectors.joining(",")))
                    .thenApply(moves -> String.format("RESTORE DATABASE [%s] FROM DISK = N'%s' WITH file = 1, %s, NOUNLOAD, STATS = 5, REPLACE", target, file, moves));
        }

        private String determineFileName(String path, Map<String, Object> values) {
            var name = (String) values.get("LogicalName");
            var type = (String) values.get("Type");
            var ext = StringUtils.equalsIgnoreCase(type, "L") ? "_log.ldf" : ".mdf";

            return String.format("MOVE N'%s' TO N'%s'", name, StringUtils.stripEnd(path, "/\\") + '\\' + uniqueName(target, ext));
        }

        private String uniqueName(String target, String ext) {
            int count = uniqueNames.compute(target + ext, (k, v) -> v == null ? 0 : v + 1);
            if (count == 0) {
                return target + ext;
            }
            return target + "_" + count + ext;
        }

        private CompletableFuture<String> determineTargetPath() {
            var path = "LEFT(physical_name,LEN(physical_name)-CHARINDEX('\\',REVERSE(physical_name))+1)";
            var pathQuery = "SELECT top 1 " + path + " path, count(*)\n" +
                    "    FROM sys.master_files mf\n" +
                    "    INNER JOIN sys.[databases] d ON mf.[database_id] = d.[database_id]  \n" +
                    "group by " + path + "\n" +
                    "order by count(*) desc;";

            return connection.getSingle(pathQuery, "path");
        }

        private void progress(Pair<Auditor.MessageType, String> warning) {
            if (warning.getLeft() == Auditor.MessageType.ERROR) {
                Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, Constants.ERROR, warning.getRight(), NotificationType.ERROR));
            }
            progressConsumer.accept(warning);
        }
    }
}
