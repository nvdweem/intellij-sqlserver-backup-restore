package dev.niels.sqlbackuprestore.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import dev.niels.sqlbackuprestore.query.Connection;
import dev.niels.sqlbackuprestore.query.ProgressTask;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import dev.niels.sqlbackuprestore.ui.FileDialog;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

@Slf4j
public class Backup extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try (var c = QueryHelper.connection(e)) {
                backup(e, c).thenApply(Pair::getLeft).thenAccept(Connection::close);
            }
        });
    }

    protected CompletableFuture<Pair<Connection, String>> backup(@NotNull AnActionEvent e, Connection c) {
        var database = QueryHelper.getDatabase(e);
        var target = FileDialog.chooseFile(e.getProject(), c, "Backup to file", "Select a file to backup '" + database + "' to");
        if (StringUtils.isEmpty(target)) {
            return CompletableFuture.completedFuture(Pair.of(c, null));
        }

        var future = new CompletableFuture<Pair<Connection, String>>();
        var newC = c.takeOver();
        database.ifPresent(db -> new ProgressTask(e.getProject(), "Creating Backup", false,
                consumer -> newC.withMessages(consumer).execute("BACKUP DATABASE [" + db.getName() + "] TO  DISK = N'" + target + "' WITH NOFORMAT, INIT, SKIP, NOREWIND, NOUNLOAD, STATS = 10"))
                .afterFinish(() -> future.complete(Pair.of(newC, target))).queue());
        return future;
    }
}
