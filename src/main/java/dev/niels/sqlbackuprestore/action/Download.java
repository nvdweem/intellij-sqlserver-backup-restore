package dev.niels.sqlbackuprestore.action;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import dev.niels.sqlbackuprestore.query.Connection;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import dev.niels.sqlbackuprestore.ui.FileDialog;
import lombok.SneakyThrows;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

public class Download extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try (var c = QueryHelper.connection(e)) {
                var source = FileDialog.chooseFile(e.getProject(), c, "Choose file", "Choose file to download");
                if (StringUtils.isEmpty(source)) {
                    return;
                }
                var target = FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor("Choose local file", "Where to store the downloaded file"), e.getProject()).save(null, null).getFile();
                new DownloadTask(e.getProject(), c.takeOver(), source, target).queue();
            }
        });
    }

    private static class DownloadTask extends Task.Backgroundable {
        private static final int CHUNK_SIZE = 1024 * 1024;
        private final Connection connection;
        private final String path;
        private final File target;

        public DownloadTask(@Nullable Project project, Connection connection, String path, File target) {
            super(project, "Downloading " + path);
            this.connection = connection;
            this.path = path;
            this.target = target;
        }

        @SneakyThrows
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
            try (connection; var fos = new FileOutputStream(target)) {
                indicator.setText(getTitle());
                indicator.setIndeterminate(false);
                indicator.setFraction(0.0);

                connection.getSingle(Connection.BlobWrapper.class, "SELECT BulkColumn FROM OPENROWSET(BULK N'" + path + "', SINGLE_BLOB) rs", "BulkColumn").ifPresent(wrapper -> readBlobToFile(indicator, fos, wrapper));
            }

            if (indicator.isCanceled()) {
                Files.delete(target.toPath());
            }
        }

        private void readBlobToFile(@NotNull ProgressIndicator indicator, FileOutputStream fos, Connection.BlobWrapper wrapper) {
            try (wrapper) {
                var blob = wrapper.getBlob();
                var size = blob.length();
                long position = 1;
                while (position < size && !indicator.isCanceled()) {
                    fos.write(blob.getBytes(position, CHUNK_SIZE));
                    fos.flush();
                    position += CHUNK_SIZE;
                    position = Math.min(size, position);
                    indicator.setFraction((double) position / size);
                    indicator.setText(String.format("%s: %s/%s", getTitle(), Util.humanReadableByteCountSI(position), Util.humanReadableByteCountSI(size)));
                }
            } catch (Exception e) {
                Notifications.Bus.notify(new Notification("BackupRestore", "Failed to download", "The download failed with message:\n" + e.getMessage(), NotificationType.ERROR));
            }
        }
    }
}
