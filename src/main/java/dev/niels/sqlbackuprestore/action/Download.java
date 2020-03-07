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
import com.intellij.openapi.ui.Messages;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.query.Connection;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Triggers backup and then allows downloading the result
 */
public class Download extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try (var c = QueryHelper.connection(e)) {
                var innerConnection = c.getNew();
                new Backup().backup(e, c).thenAccept(source -> {
                    if (StringUtils.isEmpty(source)) {
                        innerConnection.close();
                        return;
                    }

                    var target = FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor("Choose local file", "Where to store the downloaded file"), e.getProject()).save(null, null).getFile();
                    var compress = askCompress(e.getProject());
                    if (compress) {
                        target = new File(target.getAbsolutePath() + ".gzip");
                    }

                    new DownloadTask(e.getProject(), innerConnection, source, target, compress).queue();
                });
            }
        });
    }

    private boolean askCompress(Project project) {
        return Messages.YES == Messages.showYesNoDialog(project,
                "Do you want to compress the file before downloading?",
                "Compress?",
                Messages.getQuestionIcon());
    }

    @Slf4j
    private static class DownloadTask extends Task.Backgroundable {
        private static final int CHUNK_SIZE = 1024 * 1024;
        private final Connection connection;
        private final String path;
        private final File target;
        private final boolean compress;

        public DownloadTask(@Nullable Project project, Connection connection, String path, File target, boolean compress) {
            super(project, "Downloading " + path);
            this.connection = connection;
            this.path = path;
            this.target = target;
            this.compress = compress;
        }

        @Override
        public void run(@NotNull ProgressIndicator indicator) {
            try (connection; var fos = new FileOutputStream(target)) {
                indicator.setIndeterminate(false);
                indicator.setFraction(0.0);

                var column = "BulkColumn";
                if (compress) {
                    column = "COMPRESS(BulkColumn) as BulkColumn";
                    indicator.setText(String.format("Compressing '%s'", path));
                } else {
                    indicator.setText(getTitle());
                }

                connection.getSingle(Connection.BlobWrapper.class,
                        "SELECT " + column + " FROM OPENROWSET(BULK N'" + path + "', SINGLE_BLOB) rs", "BulkColumn")
                        .ifPresent(wrapper -> readBlobToFile(indicator, fos, wrapper));
            } catch (IOException e) {
                Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Unable to write", "Unable to write to " + path + ":\n" + e.getMessage(), NotificationType.ERROR));
            }

            if (indicator.isCanceled()) {
                try {
                    Files.delete(target.toPath());
                } catch (IOException e) {
                    Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Delete failure", "Unable to delete " + path + " after cancel:\n" + e.getMessage(), NotificationType.WARNING));
                }
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
                log.error("Error occurred while downloading", e);
                Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Failed to download", "The download failed with message:\n" + e.getMessage(), NotificationType.ERROR));
            }
        }
    }
}
