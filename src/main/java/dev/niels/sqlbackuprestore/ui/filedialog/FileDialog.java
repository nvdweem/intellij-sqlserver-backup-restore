package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications.Bus;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.ui.SQLHelper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * File dialog to show remote files from SQLServer.
 */
@RequiredArgsConstructor
public class FileDialog {
    public static final String KEY_PREFIX = "sqlserver_backup_path_";
    private static final String DESCRIPTION = "This file picker shows files from the SQLServer instance, this might not be your local filesystem.";
    private final Project project;
    private final Client connection;
    private final String title;

    /**
     * Open the file dialog to show files from the connection.
     */
    public static RemoteFile chooseFile(String fileName, Project project, Client c, String title, DialogType type) {
        return new FileDialog(project, c, title).choose(type, fileName);
    }

    private RemoteFile choose(DialogType type, String fileName) {
        var fs = new DatabaseFileSystem(connection);
        var roots = fs.getRoots();

        if (roots.length == 0) {
            Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Error occurred", "The database user for this connection is not allowed to read drives.", NotificationType.ERROR));
            return null;
        }

        var initial = getInitial(roots);
        return new Chooser(type, (FileSaverDescriptor) new FileSaverDescriptor(title, DESCRIPTION).withRoots(roots), project).choose(initial, fileName);
    }

    private RemoteFile getInitial(VirtualFile[] roots) {
        var path = PropertiesComponent.getInstance(project).getValue(getSelectionKeyName(connection));
        RemoteFile current = getRemoteFile(roots, path);
        if (current != null) {
            return current;
        }

        try {
            var backupDirectory = SQLHelper.getDefaultBackupDirectory(connection);
            return getRemoteFile(roots, backupDirectory);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private RemoteFile getRemoteFile(VirtualFile[] roots, String path) {
        var parts = StringUtils.defaultIfBlank(path, "").split("[\\\\/]");
        var finalParts = parts.length > 0 ? parts : new String[]{"/"};

        for (VirtualFile root : roots) {
            if (!root.getName().equals(finalParts[0])) {
                continue;
            }

            var current = Optional.of(root);
            for (var i = 1; i < finalParts.length && current.isPresent(); i++) {
                var ic = i;
                current = current.map(c -> c.findChild(finalParts[ic]));
            }

            if (current.isPresent()) {
                return (RemoteFile) current.get();
            }
        }
        return null;
    }

    @NotNull
    static String getSelectionKeyName(@NotNull Client connection) {
        return KEY_PREFIX + connection.getDbName();
    }
}