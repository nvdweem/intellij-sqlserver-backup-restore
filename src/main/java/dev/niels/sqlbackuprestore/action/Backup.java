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

/**
 * Backup database to a file
 */
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

    /**
     * Asks for a (remote) file and backs the selected database up to that file.
     *
     * @param e the event that triggered the action (the database is retrieved from the action)
     * @param c the connection that should be used for backing up (will be taken over if a backup is being made, close it from the future as well).
     * @return a pair of the connection that should be closed and the file that was being selected. The original connection and null if no file was selected.
     */
    protected CompletableFuture<Pair<Connection, String>> backup(@NotNull AnActionEvent e, Connection c) {
        var database = QueryHelper.getDatabase(e);
        if (database.isEmpty()) {
            return CompletableFuture.completedFuture(Pair.of(c, null));
        }

        var target = FileDialog.chooseFile(e.getProject(), c, "Backup to file", "Select a file to backup '" + database.get() + "' to");
        if (StringUtils.isEmpty(target)) {
            return CompletableFuture.completedFuture(Pair.of(c, null));
        }

        var future = new CompletableFuture<Pair<Connection, String>>();
        var newC = c.takeOver();
        database.ifPresent(db -> new ProgressTask(e.getProject(), "Creating Backup", false,
                consumer -> newC.withMessages(consumer).execute("BACKUP DATABASE [" + db.getName() + "] TO  DISK = N'" + target + "' WITH COPY_ONLY, NOFORMAT, INIT, SKIP, NOREWIND, NOUNLOAD, STATS = 10"))
                .afterFinish(() -> future.complete(Pair.of(newC, target))).queue());
        return future;
    }
}
