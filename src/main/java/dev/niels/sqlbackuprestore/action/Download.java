package dev.niels.sqlbackuprestore.action;

import com.intellij.database.model.DasObject;
import com.intellij.database.remote.jdbc.RemoteBlob;
import com.intellij.ide.util.PropertiesComponent;
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
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread;
import com.intellij.util.concurrency.annotations.RequiresEdt;
import dev.niels.sqlbackuprestore.AppSettingsState;
import dev.niels.sqlbackuprestore.Edt;
import dev.niels.sqlbackuprestore.Notifier;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import dev.niels.sqlbackuprestore.query.Sql;
import dev.niels.sqlbackuprestore.ui.filedialog.FileDialog;
import dev.niels.sqlbackuprestore.ui.filedialog.RemoteFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;
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
 * Backs the database up to a file on the server, then pulls that file down to the local machine in chunks.
 */
public class Download extends DumbAwareAction {
    private static final String GZIP_EXTENSION = ".gzip";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var c = QueryHelper.client(e);
        try {
            // Held for the asynchronous flow below, which outlives this method. Every path through it releases once.
            c.acquire();
            ApplicationManager.getApplication().invokeLater(() -> takeBackup(e, c));
        } finally {
            c.release();
        }
    }

    /**
     * Step 1, on the event thread because it opens the "where on the server" dialog.
     */
    @RequiresEdt
    private void takeBackup(@NotNull AnActionEvent e, Client c) {
        new Backup().backup(e, c)
                .thenAcceptAsync(backup -> {
                    if (backup == null) {
                        c.release(); // No file was chosen, so nothing was backed up.
                    } else {
                        readIntoTempTable(e, c, backup);
                    }
                })
                // Without this a backup that failed would never release the session it holds.
                .exceptionally(t -> reportAndRelease(c, t));
    }

    /**
     * Step 2, off the event thread: the server reads its own backup file into a temp table, compressing it there if
     * the user wants that, so that the download can then pull it out in chunks.
     */
    @RequiresBackgroundThread
    private void readIntoTempTable(@NotNull AnActionEvent e, Client c, RemoteFile backup) {
        var compressed = Edt.compute(() -> askCompress(e.getProject(), backup.getLength()));
        var column = compressed ? "COMPRESS(BulkColumn)" : "BulkColumn";

        c.execute("SELECT 1 as id, CAST(0 as bigint) AS fs, %s AS f into #filedownload FROM OPENROWSET(BULK N'%s', SINGLE_BLOB) x;"
                        .formatted(column, Sql.literal(backup.getPath())))
                // DATALENGTH, not LEN: LEN is a character function and would stop at the first zero byte.
                .thenCompose(x -> c.execute("update #filedownload set fs = DATALENGTH(f) where id = 1;"))
                .thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> queueDownload(e, c, backup, compressed)))
                .exceptionally(t -> reportAndRelease(c, t));
    }

    /**
     * Step 3, on the event thread because it opens the local save dialog. The download itself runs as a task.
     */
    @RequiresEdt
    private void queueDownload(@NotNull AnActionEvent e, Client c, RemoteFile backup, boolean compressed) {
        var target = chooseLocalFile(e, backup.getName() + (compressed ? GZIP_EXTENSION : ""));
        if (target == null) {
            c.release();
            return;
        }
        if (compressed && !Strings.CI.endsWith(target.getAbsolutePath(), GZIP_EXTENSION)) {
            target = new File(target.getAbsolutePath() + GZIP_EXTENSION);
        }
        new DownloadTask(e.getProject(), c, backup.getPath(), target).queue();
    }

    private static Void reportAndRelease(Client c, Throwable t) {
        Notifier.error("Download failed", Notifier.rootMessage(t));
        c.release();
        return null;
    }

    @Nullable
    private File chooseLocalFile(@NotNull AnActionEvent e, String fileName) {
        var property = PropertiesComponent.getInstance(Objects.requireNonNull(e.getProject())).getValue(FileDialog.KEY_PREFIX + "download");
        var path = property == null ? null : LocalFileSystem.getInstance().findFileByPath(property);

        if (AppSettingsState.getInstance().isUseDbNameOnDownload()) {
            // Fall back to the backup's own name rather than proposing a file literally called "null.bak".
            fileName = QueryHelper.getDatabase(e).map(DasObject::getName).map(name -> name + ".bak").orElse(fileName);
        }
        var wrapper = FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor("Choose Local File", "Where to store the downloaded file"), e.getProject()).save(path, fileName);
        if (wrapper == null) {
            return null;
        }

        var result = wrapper.getFile();
        // setValue, not getValue: reading it back here meant the chosen directory was never actually remembered.
        PropertiesComponent.getInstance(e.getProject()).setValue(FileDialog.KEY_PREFIX + "download", result.getParent());
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
            try {
                // The stream has to be closed before cleanIfCancelled runs: Windows refuses to delete an open file,
                // so a cancelled download used to leave a half-written .bak behind.
                try (var fos = new FileOutputStream(target)) {
                    indicator.setIndeterminate(false);
                    indicator.setFraction(0.0);

                    connection.getSingle("SELECT fs FROM #filedownload", "fs", Long.class)
                            .thenCompose(s -> download(indicator, fos, s))
                            .join();
                } finally {
                    dropTempTable();
                    // Exactly once: the old exceptionally(close).thenRun(close) pair closed twice on failure.
                    connection.release();
                }
                cleanIfCancelled(indicator);
            } catch (Exception e) {
                Notifier.error("Unable to write", "Unable to write to " + path, e);
            }
        }

        /**
         * The blob is staged in a temp table that would otherwise sit in tempdb for as long as the session lives.
         */
        private void dropTempTable() {
            try {
                connection.execute("IF OBJECT_ID('tempdb..#filedownload') IS NOT NULL DROP TABLE #filedownload;").join();
            } catch (Exception e) {
                log.warn("Unable to drop the temporary download table", e);
            }
        }

        private CompletableFuture<?> download(@NotNull ProgressIndicator indicator, FileOutputStream fos, Long s) {
            // Split into 100 parts unless the parts are smaller than 1MB
            var part = Math.max(1_000_000, (long) Math.ceil(s / 100d));
            var parts = Math.ceil((double) s / part);
            // T-SQL SUBSTRING is 1-based. Starting at `current * part` made the first chunk one byte short and, when
            // the total size was an exact multiple of the chunk size, dropped the very last byte of the download.

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
                    return connection.withRows(String.format("select substring(f, %s, %s) AS part from #filedownload", current * part + 1, part), (cols, rows) -> {
                        try {
                            write(fos, rows.getFirst().getValue(0));
                            indicator.setFraction(current / parts);
                            indicator.setText(String.format("%s: %s/%s", getTitle(), Util.humanReadableByteCountSI(Math.min(s, (current + 1) * part)), Util.humanReadableByteCountSI(s)));
                        } catch (Exception e) {
                            Notifier.error("Unable to write", "Unable to write to " + target, e);
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
            switch (blob) {
                case RemoteBlob remoteBlob -> saveBlob(fos, remoteBlob);
                case byte[] bytes -> saveBlob(fos, bytes);
                case String s -> saveBlob(fos, s); // Haven't actually seen this happen...
                default -> throw new IllegalArgumentException("Unable to download column of type " + blob.getClass().getName());
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
                    Notifier.warning("Delete failure", "Unable to delete " + path + " after cancel:\n" + e.getMessage());
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
