package dev.niels.sqlbackuprestore.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import dev.niels.sqlbackuprestore.query.ProgressTask;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import dev.niels.sqlbackuprestore.ui.FileDialog;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.awt.EventQueue;

@Slf4j
public class Backup extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        EventQueue.invokeLater(() -> {
            try (var c = QueryHelper.connection(e)) {
                var database = QueryHelper.getDatabase(e);
                var target = FileDialog.chooseFile(e.getProject(), c, "Backup to file", "Select a file to backup '" + database + "' to");
                if (StringUtils.isEmpty(target)) {
                    return;
                }

                var newC = c.takeOver();
                database.ifPresent(db -> new ProgressTask(e.getProject(), "Creating Backup", false,
                        consumer -> c.withMessages(consumer).execute("BACKUP DATABASE [" + db.getName() + "] TO  DISK = N'" + target + "' WITH NOFORMAT, INIT, SKIP, NOREWIND, NOUNLOAD, STATS = 10")).afterFinish(newC::close).queue());
            }
        });
    }
}
