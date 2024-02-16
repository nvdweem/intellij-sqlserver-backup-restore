package dev.niels.sqlbackuprestore.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import org.jetbrains.annotations.NotNull;

public class Group extends DefaultActionGroup {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(QueryHelper.isMssql(e));
    }

    @Override
    public boolean isDumbAware() {
        return true;
    }
}
