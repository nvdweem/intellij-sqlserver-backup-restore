package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications.Bus;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.ui.SQLHelper;
import lombok.RequiredArgsConstructor;
import one.util.streamex.StreamEx;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import static dev.niels.sqlbackuprestore.ui.filedialog.DialogType.LOAD;
import static dev.niels.sqlbackuprestore.ui.filedialog.DialogType.SAVE;

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
    public static RemoteFile[] chooseFiles(String fileName, Project project, Client c, String title) {
        return new FileDialog(project, c, title).choose(LOAD, fileName);
    }

    public static RemoteFile saveFile(String fileName, Project project, Client c, String title) {
        return ArrayUtils.get(new FileDialog(project, c, title).choose(SAVE, fileName), 0);
    }

    private RemoteFile[] choose(DialogType type, String fileName) {
        var listed = listRoots();
        if (listed == null) {
            return null;
        }

        if (type == LOAD) {
            return loadFile(listed.roots(), listed.initial());
        } else {
            return saveFile(fileName, listed.roots(), listed.initial());
        }
    }

    /**
     * Queries the drives and the directory to open under a cancellable modal progress, off the EDT. The first
     * statement on a fresh session also connects, which may take a while or need the EDT itself (a password prompt),
     * so doing this on the EDT froze the IDE and could only end in a timeout (#23). Errors become a notification;
     * {@code null} means there is nothing to show.
     */
    private @Nullable Listed listRoots() {
        var fs = new DatabaseFileSystem(connection);
        Listed listed;
        try {
            listed = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                var roots = fs.getRoots();
                return new Listed(roots, roots.length == 0 ? null : getInitial(roots));
            }, "Listing files on " + connection.getDbName(), true, project);
        } catch (ProcessCanceledException e) {
            return null;
        } catch (Exception e) {
            var cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, Constants.ERROR,
                    "Unable to list the files on " + connection.getDbName() + ": " + StringUtils.defaultIfBlank(cause.getMessage(), cause.toString()),
                    NotificationType.ERROR));
            return null;
        }

        if (listed.roots().length == 0) {
            Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, Constants.ERROR, "The database user for this connection is not allowed to read drives.", NotificationType.ERROR));
            return null;
        }
        return listed;
    }

    private record Listed(VirtualFile[] roots, @Nullable RemoteFile initial) {
    }

    private @NotNull RemoteFile @NotNull [] loadFile(VirtualFile[] roots, RemoteFile initial) {
        var descriptor = new FileChooserDescriptor(true, false, false, false, false, true)
                .withRoots(roots);
        descriptor.setTitle(title);
        descriptor.setDescription(DESCRIPTION);
        descriptor.setForcedToUseIdeaFileChooser(true);

        var chooser = new RemoteFileChooser(descriptor, project);
        var choice = initial == null ? chooser.choose(project) : chooser.choose(project, initial);

        var result = StreamEx.of(choice).select(RemoteFile.class).toArray(RemoteFile[]::new);
        if (result.length > 0) {
            PropertiesComponent.getInstance(project).setValue(getSelectionKeyName(result[0].getConnection()), result[0].getPath());
        }
        return result;
    }

    private @NotNull RemoteFile @NotNull [] saveFile(String fileName, VirtualFile[] roots, RemoteFile initial) {
        var descriptor = (FileSaverDescriptor) new FileSaverDescriptor(title, DESCRIPTION).withRoots(roots);
        descriptor.setForcedToUseIdeaFileChooser(true);
        var file = new Chooser(descriptor, project).choose(initial, fileName);
        if (file != null) {
            return new RemoteFile[]{file};
        }
        return new RemoteFile[0];
    }

    private RemoteFile getInitial(VirtualFile[] roots) {
        var path = PropertiesComponent.getInstance(project).getValue(getSelectionKeyName(connection));
        RemoteFile current = getRemoteFile(roots, path);
        if (current != null && current.exists()) {
            return current;
        }
        if (current != null && current.getParent() != null && current.getParent().exists()) {
            return (RemoteFile) current.getParent();
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
        return RemoteFile.resolve(Arrays.asList(roots), StringUtils.defaultIfBlank(path, ""));
    }

    @NotNull
    static String getSelectionKeyName(@NotNull Client connection) {
        return KEY_PREFIX + connection.getDbName();
    }
}
