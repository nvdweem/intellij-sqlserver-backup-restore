package dev.niels.sqlbackuprestore.action;

import com.intellij.database.datagrid.DataConsumer;
import com.intellij.database.remote.jdbc.RemoteBlob;
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
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.List;

/**
 * Triggers backup and then allows downloading the result
 */
public class Download extends AnAction implements DumbAware {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        try (var c = QueryHelper.client(e)) {
            c.open();
            ApplicationManager.getApplication().invokeLater(() ->
                    new Backup().backup(e, c).thenAccept(source -> {
                        if (StringUtils.isEmpty(source)) {
                            c.close();
                            return;
                        }

                        ApplicationManager.getApplication().invokeLater(() -> {
                            var target = FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor("Choose local file", "Where to store the downloaded file"), e.getProject()).save(null, null).getFile();
                            var compress = askCompress(e.getProject());
                            if (compress) {
                                target = new File(target.getAbsolutePath() + ".gzip");
                            }

                            new DownloadTask(e.getProject(), c, source, target, compress).queue();
                        });
                    })
            );
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(QueryHelper.getDatabase(e).isPresent());
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
        private final Client connection;
        private final String path;
        private final File target;
        private final boolean compress;

        public DownloadTask(@Nullable Project project, Client connection, String path, File target, boolean compress) {
            super(project, "Downloading " + path);
            this.connection = connection;
            this.path = path;
            this.target = target;
            this.compress = compress;
        }

        @Override
        public void run(@NotNull ProgressIndicator indicator) {
            try (var fos = new FileOutputStream(target)) {
                indicator.setIndeterminate(false);
                indicator.setFraction(0.0);

                var column = "BulkColumn";
                if (compress) {
                    column = "COMPRESS(BulkColumn) as BulkColumn";
                    indicator.setText(String.format("Compressing '%s'", path));
                } else {
                    indicator.setText(getTitle());
                }

                connection.withRows("SELECT BulkColumn, LEN(BulkColumn) AS size FROM (SELECT " + column + " FROM OPENROWSET(BULK N'" + path + "', SINGLE_BLOB) rs) r", r -> readBlobToFile(indicator, fos, r))
                        .thenRun(connection::close)
                        .exceptionally(connection::close)
                        .thenRun(() -> cleanIfCancelled(indicator))
                        .get();
            } catch (Exception e) {
                Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Unable to write", "Unable to write to " + path + ":\n" + e.getMessage(), NotificationType.ERROR));
            }
        }

        private void cleanIfCancelled(ProgressIndicator indicator) {
            if (indicator.isCanceled()) {
                try {
                    Files.delete(target.toPath());
                } catch (IOException e) {
                    Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Delete failure", "Unable to delete " + path + " after cancel:\n" + e.getMessage(), NotificationType.WARNING));
                }
            }
        }

        private void readBlobToFile(@NotNull ProgressIndicator indicator, FileOutputStream fos, Pair<List<DataConsumer.Column>, List<DataConsumer.Row>> rows) {
            try {
                var blob = rows.getRight().get(0).getValue(0);
                var size = ((Long) rows.getRight().get(0).getValue(1));

                if (blob instanceof RemoteBlob) {
                    saveBlob(indicator, fos, (RemoteBlob) blob, size);
                } else if (blob instanceof byte[]) {
                    saveBArr(indicator, fos, (byte[]) blob);
                } else {
                    Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Failed to download", "Unable to download column of type " + blob.getClass().getName(), NotificationType.ERROR));
                }
                indicator.setFraction(1);
            } catch (Exception e) {
                log.error("Error occurred while downloading", e);
                Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Failed to download", "The download failed with message:\n" + e.getMessage(), NotificationType.ERROR));
            }
        }

        private void saveBArr(ProgressIndicator indicator, FileOutputStream fos, byte[] blob) throws IOException {
            indicator.setIndeterminate(false);
            fos.write(blob);
        }

        private void saveBlob(ProgressIndicator indicator, FileOutputStream fos, RemoteBlob blob, long size) throws IOException, SQLException {
            long position = 1;
            while (position < size && !indicator.isCanceled()) {
                fos.write(blob.getBytes(position, CHUNK_SIZE));
                fos.flush();
                position += CHUNK_SIZE;
                position = Math.min(size, position);
                indicator.setFraction((double) position / size);
                indicator.setText(String.format("%s: %s/%s", getTitle(), Util.humanReadableByteCountSI(position), Util.humanReadableByteCountSI(size)));
            }
        }
    }
}
