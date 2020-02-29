package dev.niels.sqlbackuprestore.action;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import dev.niels.sqlbackuprestore.query.Connection;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.util.function.Supplier;

public class RetryDownload extends NotificationAction {
    private final Connection connection;
    private final String path;
    private final File target;

    public RetryDownload(Connection connection, String path, File target) {
        super("Retry");
        this.connection = connection;
        this.path = path;
        this.target = target;
    }

    public RetryDownload(Supplier<Connection> connection, String path, File target) {
        super("Retry");
        this.connection = connection.get();
        this.path = path;
        this.target = target;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        actionPerformed(e, e.getData(DataKey.create("Notification")));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e, Notification notification) {
        if (notification != null) {
            notification.expire();
            notification.hideBalloon();
        }
        new DownloadTask(e.getProject()).queue();
    }

    private class DownloadTask extends Task.Backgroundable {
        private static final int CHUNK_SIZE = 1024 * 1024;

        public DownloadTask(@Nullable Project project) {
            super(project, "Downloading " + path);
        }

        @SneakyThrows
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
            try (var fos = new FileOutputStream(target)) {
                indicator.setText(getTitle());
                indicator.setIndeterminate(false);
                indicator.setFraction(0.0);

                var wrapper = connection.getSingle(Connection.BlobWrapper.class, "SELECT BulkColumn FROM OPENROWSET(BULK N'" + path + "', SINGLE_BLOB) rs", "BulkColumn");
                if (wrapper.isEmpty()) {
                    return;
                }
                var blob = wrapper.get().getBlob();

                var size = blob.length();
                long position = 1;
                while (position < size) {
                    fos.write(blob.getBytes(position, CHUNK_SIZE));
                    fos.flush();
                    position += CHUNK_SIZE;
                    position = Math.min(size, position);
                    indicator.setFraction((double) position / size);
                    indicator.setText(String.format("%s: %s/%s", getTitle(), humanReadableByteCountSI(position), humanReadableByteCountSI(size)));
                }

                connection.close();
            } catch (Exception e) {
                Notifications.Bus.notify(
                        new Notification("BackupRestore", "xFailed to download", "The download failed. You could retry, the garbage collector messes it up sometimes...\n" + e.getMessage(), NotificationType.ERROR)
                                .whenExpired(connection::close)
                                .addAction(new RetryDownload(connection::takeOver, path, target))
                );
            }
        }

    }

    public String humanReadableByteCountSI(long bytes) {  // NOSONAR
        String s = bytes < 0 ? "-" : ""; // NOSONAR
        long b = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes); // NOSONAR
        return b < 1000L ? bytes + " B" // NOSONAR
                : b < 999_950L ? String.format("%s%.1f kB", s, b / 1e3) // NOSONAR
                : (b /= 1000) < 999_950L ? String.format("%s%.1f MB", s, b / 1e3) // NOSONAR
                : (b /= 1000) < 999_950L ? String.format("%s%.1f GB", s, b / 1e3) // NOSONAR
                : (b /= 1000) < 999_950L ? String.format("%s%.1f TB", s, b / 1e3) // NOSONAR
                : (b /= 1000) < 999_950L ? String.format("%s%.1f PB", s, b / 1e3) // NOSONAR
                : String.format("%s%.1f EB", s, b / 1e6); // NOSONAR
    }
}
