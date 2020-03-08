package dev.niels.sqlbackuprestore.action;

import com.intellij.database.actions.RefreshSchemaAction;
import com.intellij.database.model.DasObject;
import com.intellij.database.view.DatabaseView;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.ui.Messages;
import dev.niels.sqlbackuprestore.query.Auditor;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.query.ProgressTask;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import dev.niels.sqlbackuprestore.ui.FileDialog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Restore a database from a (remote) file. Cannot be a gzipped file.
 */
@Slf4j
public class Restore extends AnAction implements DumbAware {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try (var c = QueryHelper.client(e)) {
                c.setTitle("Restore database");
                var target = QueryHelper.getDatabase(e).map(DasObject::getName).orElseGet(this::promptDatabaseName);
                if (target == null) {
                    return;
                }

                c.setTitle("Restore " + target);
                var file = FileDialog.chooseFile(e.getProject(), c, "Restore database", "Select a file to restore to '" + target + "'");
                if (StringUtils.isBlank(file)) {
                    return;
                }

                c.open();
                new ProgressTask(e.getProject(), "Restore backup", false, consumer ->
                        new RestoreHelper(c, target, file, consumer).restore()
                                .thenRun(() -> RefreshSchemaAction.refresh(e.getProject(), DatabaseView.getSelectedElementsNoGroups(e.getDataContext(), true)))
                                .thenRun(c::close).exceptionally(c::close)
                ).queue();
            }
        });
    }

    private String promptDatabaseName() {
        var name = Messages.showInputDialog("Create a new database from backup", "Database name", null);
        return StringUtils.stripToNull(name);
    }

    @RequiredArgsConstructor
    @Slf4j
    private static class RestoreHelper {
        private final Client connection;
        private final String target;
        private final String file;
        private final Consumer<Pair<Auditor.MessageType, String>> progressConsumer;
        private final Map<String, Integer> uniqueNames = new HashMap<>();

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
            progressConsumer.accept(warning);
        }
    }
}
