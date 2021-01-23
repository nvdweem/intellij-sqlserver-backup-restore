package dev.niels.sqlbackuprestore.action;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.query.Auditor;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.query.ProgressTask;
import dev.niels.sqlbackuprestore.query.QueryHelper;
import dev.niels.sqlbackuprestore.ui.FileDialog;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Backup database to a file
 */
@Slf4j
public class Backup extends AnAction implements DumbAware {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try (var c = QueryHelper.client(e)) {
                c.setTitle("Backup database");
                backup(e, c);
            }
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(QueryHelper.getDatabase(e).isPresent());
    }

    /**
     * Asks for a (remote) file and backs the selected database up to that file.
     * Must be called on the event thread.
     *
     * @param e the event that triggered the action (the database is retrieved from the action)
     * @param c the connection that should be used for backing up (will be taken over if a backup is being made, close it from the future as well).
     * @return a pair of the connection that should be closed and the file that was being selected. The original connection and null if no file was selected.
     */
    protected CompletableFuture<FileDialog.RemoteFile> backup(@NotNull AnActionEvent e, Client c) {
        var database = QueryHelper.getDatabase(e);
        if (database.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        var name = database.get().getName();
        var target = FileDialog.chooseFile(name + ".bak", e.getProject(), c, "Backup to file", "Select a file to backup '" + database.get() + "' to", FileDialog.DialogType.SAVE);
        if (target == null) {
            return CompletableFuture.completedFuture(null);
        }

        c.open();
        c.setTitle("Backup " + name);
        var future = c.execute("BACKUP DATABASE [" + name + "] TO  DISK = N'" + target.getPath() + "' WITH COPY_ONLY, NOFORMAT, INIT, SKIP, NOREWIND, NOUNLOAD, STATS = 10")
                      .thenApply(c::closeAndReturn)
                      .exceptionally(c::close)
                      .thenCompose(x -> c.getSingle(String.format("USE [%s] exec sp_spaceused @oneresultset = 1", name), "reserved", String.class)
                                         .thenApply(kb -> Long.parseLong(StringUtils.removeEnd(kb, " KB")) * 1024)
                                         .thenApply(target::setLength));

        c.addWarningConsumer(p -> {
            if (p.getLeft() == Auditor.MessageType.ERROR) {
                Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Error occurred", p.getRight(), NotificationType.ERROR));
            }
        });

        new ProgressTask(e.getProject(), "Creating Backup", false, consumer -> {
            c.addWarningConsumer(consumer);
            try {
                future.get();
            } catch (Exception ex) {
                // Don't really care ;)
            }
        }).queue();
        return future;
    }
}
