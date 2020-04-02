package dev.niels.sqlbackuprestore.action;

import com.intellij.database.model.DasObject;
import com.intellij.database.remote.jdbc.RemoteBlob;
import com.intellij.ide.util.PropertiesComponent;
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
import com.intellij.openapi.vfs.LocalFileSystem;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import dev.niels.sqlbackuprestore.ui.FileDialog;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Triggers backup and then allows downloading the result
 */
public class Download extends AnAction implements DumbAware {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        try (var c = QueryHelper.client(e)) {
            c.open();

            ApplicationManager.getApplication().invokeLater(() ->
                    new Backup().backup(e, c).thenAcceptAsync(source -> {
                        if (StringUtils.isEmpty(source)) {
                            c.close();
                            return;
                        }

                        AtomicBoolean compressed = new AtomicBoolean(false);

                        c.execute("SELECT CAST(0 as bigint) AS fs, BulkColumn AS f into #filedownload FROM OPENROWSET(BULK N'" + source + "', SINGLE_BLOB) x;")
                                .thenCompose(x -> c.execute("update #filedownload set fs = LEN(f);"))
                                .thenCompose(x -> c.getSingle("select fs from #filedownload", "fs", Long.class))
                                .thenCompose(size -> {
                                    ApplicationManager.getApplication().invokeAndWait(() -> compressed.set(askCompress(e.getProject(), size)));

                                    if (compressed.get()) {
                                        // Compress blob and update filesize
                                        return c.execute("update #filedownload set f = COMPRESS(f);")
                                                .thenCompose(x -> c.execute("update #filedownload set fs = LEN(f);"));
                                    }
                                    return CompletableFuture.completedFuture(null);
                                })
                                .thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
                                    File target = getFile(e);
                                    if (target == null) {
                                        return;
                                    }

                                    if (compressed.get() && !StringUtils.endsWithIgnoreCase(target.getAbsolutePath(), ".gzip")) {
                                        target = new File(target.getAbsolutePath() + ".gzip");
                                    }

                                    new DownloadTask(e.getProject(), c, source, target).queue();
                                }));
                    })
            );
        }
    }

    @Nullable
    private File getFile(@NotNull AnActionEvent e) {
        var property = PropertiesComponent.getInstance(e.getProject()).getValue(FileDialog.KEY_PREFIX + "download");
        var path = property == null ? null : LocalFileSystem.getInstance().findFileByPath(property);
        var fileName = QueryHelper.getDatabase(e).map(DasObject::getName).orElse(null) + ".bak";
        var wrapper = FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor("Choose local file", "Where to store the downloaded file"), e.getProject()).save(path, fileName);
        if (wrapper == null) {
            return null;
        }

        var result = wrapper.getFile();
        PropertiesComponent.getInstance(e.getProject()).getValue(FileDialog.KEY_PREFIX + "download", result.getParent());
        return result;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(QueryHelper.getDatabase(e).isPresent());
    }

    private boolean askCompress(Project project, Long size) {
        return Messages.YES == Messages.showYesNoDialog(project,
                String.format("The backup size is %s, do you want to compress the file before downloading?", size == null ? "?" : Util.humanReadableByteCountSI(size)),
                "Compress?",
                Messages.getQuestionIcon());
    }

    @Slf4j
    private static class DownloadTask extends Task.Backgroundable {
        private static final int CHUNK_SIZE = 1024 * 1024;
        private final Client connection;
        private final String path;
        private final File target;

        public DownloadTask(@Nullable Project project, Client connection, String path, File target) {
            super(project, "Downloading " + path);
            this.connection = connection;
            this.path = path;
            this.target = target;
        }

        @Override
        public void run(@NotNull ProgressIndicator indicator) {
            try (var fos = new FileOutputStream(target)) {
                indicator.setIndeterminate(false);
                indicator.setFraction(0.0);

                connection.getSingle("SELECT fs FROM #filedownload", "fs", Long.class)
                        .thenCompose(s -> download(indicator, fos, s))
                        .exceptionally(connection::close)
                        .thenRun(connection::close)
                        .thenRun(() -> cleanIfCancelled(indicator))
                        .get();
            } catch (Exception e) {
                Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Unable to write", "Unable to write to " + path + ":\n" + e.getMessage(), NotificationType.ERROR));
            }
        }

        private CompletableFuture<?> download(@NotNull ProgressIndicator indicator, FileOutputStream fos, Long s) {
            // Split into 100 parts unless the parts are smaller than 1MB
            var part = Math.max(1_000_000, (long) Math.ceil(s / 100d));
            var parts = Math.ceil((double) s / part);

            CompletableFuture<?> chain = CompletableFuture.completedFuture(null);

            // Build a chain of part downloads that are executed sequentially
            AtomicBoolean error = new AtomicBoolean(false);
            for (var i = 0; i < parts; i++) {
                var current = i;
                chain = chain.thenCompose(x -> {
                    // Allow cancelling and don't proceed if there was an error
                    if (error.get() || indicator.isCanceled()) {
                        return CompletableFuture.completedFuture(null);
                    }

                    // Get the next part and store it
                    return connection.withRows(String.format("select substring(f, %s, %s) AS part from #filedownload", current * part, part), cr -> {
                        try {
                            write(fos, cr.getRight().get(0).getValue(0));
                            indicator.setFraction(current / parts);
                            indicator.setText(String.format("%s: %s/%s", getTitle(), Util.humanReadableByteCountSI(Math.min(s, (current + 1) * part)), Util.humanReadableByteCountSI(s)));
                        } catch (Exception e) {
                            Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Unable to write", "Unable to write to " + target + ":\n" + e.getMessage(), NotificationType.ERROR));
                            error.set(true);
                        }
                    });
                });
            }
            return chain;
        }

        /**
         * Write a single part to the file stream
         */
        private void write(FileOutputStream fos, Object blob) throws IOException, SQLException {
            if (blob instanceof RemoteBlob) {
                saveBlob(fos, (RemoteBlob) blob);
            } else if (blob instanceof byte[]) {
                saveBlob(fos, (byte[]) blob);
            } else if (blob instanceof String) {
                saveBlob(fos, (String) blob); // Haven't actually seen this happen...
            } else {
                throw new IllegalArgumentException("Unable to download column of type " + blob.getClass().getName());
            }
        }

        /**
         * Check if the indicator was cancelled, if so delete the target file.
         */
        private void cleanIfCancelled(ProgressIndicator indicator) {
            if (indicator.isCanceled()) {
                try {
                    Files.delete(target.toPath());
                } catch (IOException e) {
                    Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Delete failure", "Unable to delete " + path + " after cancel:\n" + e.getMessage(), NotificationType.WARNING));
                }
            }
        }

        /**
         * Write byte array to file
         */
        private void saveBlob(FileOutputStream fos, byte[] blob) throws IOException {
            fos.write(blob);
        }

        /**
         * Write RemoteBlob to file
         */
        private void saveBlob(FileOutputStream fos, RemoteBlob blob) throws IOException, SQLException {
            long position = 1;
            while (position < blob.length()) {
                fos.write(blob.getBytes(position, CHUNK_SIZE));
                fos.flush();
                position += CHUNK_SIZE;
                position = Math.min(blob.length(), position);
            }
        }

        /**
         * Write string to file
         */
        private void saveBlob(FileOutputStream fos, String blob) throws IOException {
            saveBlob(fos, blob.getBytes());
        }
    }
}
