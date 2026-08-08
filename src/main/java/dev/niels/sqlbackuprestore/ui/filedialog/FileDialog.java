package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import dev.niels.sqlbackuprestore.AppSettingsState;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.Notifier;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.ui.SQLHelper;
import lombok.RequiredArgsConstructor;
import one.util.streamex.StreamEx;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

import static dev.niels.sqlbackuprestore.ui.filedialog.DialogType.FOLDER;
import static dev.niels.sqlbackuprestore.ui.filedialog.DialogType.LOAD;
import static dev.niels.sqlbackuprestore.ui.filedialog.DialogType.SAVE;

/**
 * File dialog to show remote files from SQLServer.
 */
@RequiredArgsConstructor
public class FileDialog {
    public static final String KEY_PREFIX = "sqlserver_backup_path_";
    private static final String DESCRIPTION = "This file picker shows files from the SQLServer instance, this might not be your local filesystem.";
    /** What SQL Server writes: full/differential backups, transaction logs, and this plugin's own compressed download. */
    private static final List<String> BACKUP_EXTENSIONS = List.of(".bak", ".trn", ".dif", ".gzip");
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

    /**
     * Picks a directory on the server rather than a file, for backing several databases up at once.
     */
    public static RemoteFile chooseFolder(Project project, Client c, String title) {
        return ArrayUtils.get(new FileDialog(project, c, title).choose(FOLDER, null), 0);
    }

    private RemoteFile[] choose(DialogType type, String fileName) {
        var fs = new DatabaseFileSystem(connection);
        var roots = fs.getRoots();

        if (roots.length == 0) {
            Notifier.error(Constants.ERROR, "The database user for this connection is not allowed to read drives.");
            return null;
        }

        var initial = getInitial(roots);

        return switch (type) {
            case LOAD -> pick(backupFileDescriptor(roots, true), initial);
            case FOLDER -> pick(detailedDescriptor(false, true, false).withRoots(roots), initial);
            case SAVE -> saveFile(fileName, roots, initial);
        };
    }

    /**
     * A picker for choosing existing backups. Filtering to the extensions SQL Server writes keeps a directory full of
     * data files navigable; it can be turned off for backups that are named some other way.
     */
    private FileChooserDescriptor backupFileDescriptor(VirtualFile[] roots, boolean multiple) {
        var descriptor = detailedDescriptor(true, false, multiple).withRoots(roots);
        if (AppSettingsState.getInstance().isOnlyShowBackupFiles()) {
            descriptor = descriptor.withFileFilter(file -> file.isDirectory() || isBackup(file.getName()));
        }
        return descriptor;
    }

    /**
     * The size and date are appended by {@link FileDetailsRenderer} rather than through this descriptor's
     * {@code getComment}, which the chooser renders in the same attributes as the filename - detail that looks like
     * part of the name is worse than no detail.
     */
    static FileChooserDescriptor detailedDescriptor(boolean chooseFiles, boolean chooseFolders, boolean multiple) {
        return new FileChooserDescriptor(chooseFiles, chooseFolders, false, false, false, multiple);
    }

    static boolean isBackup(String name) {
        return StreamEx.of(BACKUP_EXTENSIONS).anyMatch(extension -> Strings.CI.endsWith(name, extension));
    }

    private @NotNull RemoteFile @NotNull [] pick(FileChooserDescriptor descriptor, RemoteFile initial) {
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
        var parts = StringUtils.defaultIfBlank(path, "").split("[\\\\/]");
        var finalParts = parts.length > 0 ? parts : new String[]{"/"};

        for (VirtualFile root : roots) {
            if (!root.getName().equals(finalParts[0])) {
                continue;
            }

            var current = Optional.of(root);
            for (var i = 1; i < finalParts.length && current.isPresent(); i++) {
                var ic = i;
                current = current.map(c -> c instanceof RemoteFile rf ? rf.getChild(finalParts[ic], true) : c.findChild(finalParts[ic]));
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
