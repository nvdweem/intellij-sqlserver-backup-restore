package dev.niels.sqlbackuprestore.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import dev.niels.sqlbackuprestore.ui.FileDialog;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

public class Download extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try (var c = QueryHelper.connection(e)) {
                var target = FileDialog.chooseFile(e.getProject(), c, "Choose file", "Choose file to download");
                if (StringUtils.isEmpty(target)) {
                    return;
                }
                var file = FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor("Choose local file", "Where to store the downloaded file"), e.getProject()).save(null, null).getFile();
                new RetryDownload(c.takeOver(), target, file).actionPerformed(e);
            }
        });
    }
}
