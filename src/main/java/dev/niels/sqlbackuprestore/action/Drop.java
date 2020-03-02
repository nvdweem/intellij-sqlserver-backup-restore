package dev.niels.sqlbackuprestore.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import org.jetbrains.annotations.NotNull;

public class Drop extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try (var c = QueryHelper.connection(e)) {
                QueryHelper.getDatabase(e).ifPresent(db -> {
                    if (askDrop(e.getProject(), db.getName())) {
                        c.execute("DROP DATABASE [" + db.getName() + "]");
                    }
                });
            }
        });
    }

    private boolean askDrop(Project project, String name) {
        return Messages.YES == Messages.showYesNoDialog(project,
                "Are you sure you want to drop " + name,
                "Drop database?",
                Messages.getQuestionIcon());
    }
}
