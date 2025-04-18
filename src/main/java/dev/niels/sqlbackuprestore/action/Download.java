package dev.niels.sqlbackuprestore.action;

import com.intellij.database.model.DasObject;
import com.intellij.database.remote.jdbc.RemoteBlob;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications.Bus;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task.Backgroundable;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import dev.niels.sqlbackuprestore.AppSettingsState;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import dev.niels.sqlbackuprestore.ui.filedialog.FileDialog;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Triggers backup and then allows downloading the result
 */
public class Download extends DumbAwareAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        try (var c = QueryHelper.client(e)) {
            c.open();

            ApplicationManager.getApplication().invokeLater(() ->
                    new Backup().backup(e, c).thenAcceptAsync(source -> {
                        if (source == null) {
                            c.close();
                            return;
                        }

                        AtomicBoolean compressed = new AtomicBoolean(false);
                        ApplicationManager.getApplication().invokeAndWait(() -> compressed.set(askCompress(e.getProject(), source.getLength())));
                        var col = compressed.get() ? "COMPRESS(BulkColumn)" : "BulkColumn";

                        c.execute("SELECT 1 as id, CAST(0 as bigint) AS fs, " + col + " AS f into #filedownload FROM OPENROWSET(BULK N'" + source.getPath() + "', SINGLE_BLOB) x;")
                                .thenCompose(x -> c.execute("update #filedownload set fs = LEN(f) where id = 1;"))
                                .thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
                                    var name = source.getName() + (compressed.get() ? ".gzip" : "");
                                    var target = getFile(e, name);
                                    if (target == null) {
                                        c.close();
                                        return;
                                    }
                                    if (compressed.get() && !StringUtils.endsWithIgnoreCase(target.getAbsolutePath(), ".gzip")) {
                                        target = new File(target.getAbsolutePath() + ".gzip");
                                    }
                                    new DownloadTask(e.getProject(), c, source.getPath(), target).queue();
                                }));
                    })
            );
        }
    }

    @Nullable
    private File getFile(@NotNull AnActionEvent e, String fileName) {
        var property = PropertiesComponent.getInstance(Objects.requireNonNull(e.getProject())).getValue(FileDialog.KEY_PREFIX + "download");
        var path = property == null ? null : LocalFileSystem.getInstance().findFileByPath(property);

        if (AppSettingsState.getInstance().isUseDbNameOnDownload()) {
            fileName = QueryHelper.getDatabase(e).map(DasObject::getName).orElse(null) + ".bak";
        }
        var wrapper = FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor("Choose Local File", "Where to store the downloaded file"), e.getProject()).save(path, fileName);
        if (wrapper == null) {
            return null;
        }

        var result = wrapper.getFile();
        PropertiesComponent.getInstance(e.getProject()).getValue(FileDialog.KEY_PREFIX + "download", result.getParent());
        return result;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        var isVisible = AppSettingsState.getInstance().isEnableDownloadOption();
        e.getPresentation().setVisible(isVisible);
        if (isVisible) {
            e.getPresentation().setEnabled(QueryHelper.getDatabase(e).isPresent());
        }
    }

    private boolean askCompress(Project project, Long size) {
        var compressed = AppSettingsState.getInstance().isUseCompressedBackup();
        var askWhen = AppSettingsState.getInstance().getCompressionSize() * 1024 * 1024;
        if (size == null || askWhen <= size) {
            var message = compressed ? "The original database was %s before compression. Do you want to apply additional compression before downloading?"
                    : "The database size is %s, do you want to compress the file before downloading?";
            return Messages.YES == Messages.showYesNoDialog(project,
                    String.format(message, size == null ? "?" : Util.humanReadableByteCountSI(size)),
                    "Compress?",
                    Messages.getQuestionIcon());
        }
        return false;
    }

    @Slf4j
    private static class DownloadTask extends Backgroundable {
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
                Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Unable to write", "Unable to write to " + path + ":\n" + e.getMessage(), NotificationType.ERROR));
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
                    return connection.withRows(String.format("select substring(f, %s, %s) AS part from #filedownload", current * part, part), (cols, rows) -> {
                        try {
                            write(fos, rows.get(0).getValue(0));
                            indicator.setFraction(current / parts);
                            indicator.setText(String.format("%s: %s/%s", getTitle(), Util.humanReadableByteCountSI(Math.min(s, (current + 1) * part)), Util.humanReadableByteCountSI(s)));
                        } catch (Exception e) {
                            Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Unable to write", "Unable to write to " + target + ":\n" + e.getMessage(), NotificationType.ERROR));
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
                    Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Delete failure", "Unable to delete " + path + " after cancel:\n" + e.getMessage(), NotificationType.WARNING));
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
