package dev.niels.sqlbackuprestore.action;

import com.intellij.database.model.DasObject;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.query.Connection;
import dev.niels.sqlbackuprestore.query.ProgressTask;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import dev.niels.sqlbackuprestore.ui.FileDialog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLWarning;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Restore a database from a (remote) file. Cannot be a gzipped file.
 */
@Slf4j
public class Restore extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try (var c = QueryHelper.connection(e)) {
                var target = QueryHelper.getDatabase(e).map(DasObject::getName).orElse(null);
                var file = FileDialog.chooseFile(e.getProject(), c, "Restore database", "Select a file to restore to '" + target + "'");
                if (StringUtils.isBlank(file)) {
                    return;
                }

                var newC = c.takeOver();
                new ProgressTask(e.getProject(), "Restore backup", false, consumer -> {
                    try (var helper = new RestoreHelper(e, target, file, consumer)) {
                        helper.restore();
                    } catch (Exception ex) {
                        log.error("Unable to restore database", ex);
                    }
                }).afterFinish(newC::close).queue();
            }
        });

    }

    @RequiredArgsConstructor
    @Slf4j
    private static class RestoreHelper implements AutoCloseable {
        private final AnActionEvent e;
        private final String target;
        private final String file;
        private final Consumer<SQLWarning> progressConsumer;
        private final Map<String, Integer> uniqueNames = new HashMap<>();
        private Connection connection;

        @Override
        public void close() {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ex) {
                    log.error("Unable to close connection", ex);
                }
            }
        }

        public void restore() {
            connection = QueryHelper.connection(e);

            connection.getResult("RESTORE FILELISTONLY FROM DISK = N'" + file + "';")
                    .flatMap(this::createRestoreQuery)
                    .ifPresent(sql -> connection.withMessages(this::progress).execute(sql));
        }

        private Optional<String> createRestoreQuery(List<Map<String, Object>> maps) {
            return determineTargetPath().map(path -> maps.stream().map(v -> determineFileName(path, v)).collect(Collectors.joining(",")))
                    .map(moves -> String.format("RESTORE DATABASE [%s] FROM DISK = N'%s' WITH file = 1, %s, NOUNLOAD, STATS = 5, REPLACE", target, file, moves));
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

        private Optional<String> determineTargetPath() {
            var path = "LEFT(physical_name,LEN(physical_name)-CHARINDEX('\\',REVERSE(physical_name))+1)";
            var pathQuery = "SELECT top 1 " + path + " path, count(*)\n" +
                    "    FROM sys.master_files mf\n" +
                    "    INNER JOIN sys.[databases] d ON mf.[database_id] = d.[database_id]  \n" +
                    "group by " + path + "\n" +
                    "order by count(*) desc;";

            return connection.getSingle(String.class, pathQuery, "path");
        }

        private void progress(SQLWarning warning) {
            if (warning.getErrorCode() == -1) {
                Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Unable to restore " + target, warning.getCause().getMessage(), NotificationType.ERROR));
            } else {
                progressConsumer.accept(warning);
            }
        }
    }
}
