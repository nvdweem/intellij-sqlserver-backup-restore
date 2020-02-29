package dev.niels.sqlbackuprestore.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import dev.niels.sqlbackuprestore.query.ProgressTask;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

@Slf4j
public class Backup extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var database = QueryHelper.getDatabase(e);

        database.ifPresent(db ->
                new ProgressTask(e.getProject(), "Creating Backup", false, consumer -> {
                    try (var c = QueryHelper.connection(e)) {
                        c.withMessages(consumer).execute("BACKUP DATABASE [" + db.getName() + "] TO  DISK = N'c:\\temp\\backup.bak' WITH NOFORMAT, INIT, SKIP, NOREWIND, NOUNLOAD, STATS = 10");
                    }
                }).queue());
    }
}
