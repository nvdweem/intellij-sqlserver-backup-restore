package dev.niels.sqlbackuprestore.ui;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.ex.FileSaverDialogImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileListener;
import com.intellij.openapi.vfs.VirtualFileSystem;
import com.intellij.ui.UIBundle;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.query.Client;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;

/**
 * File dialog to show remote files from SQLServer.
 */
@RequiredArgsConstructor
public class FileDialog {
    public static final String KEY_PREFIX = "sqlserver_backup_path_";
    private final Project project;
    private final Client connection;
    private final String title;
    private final String description;
    private final DialogType type;

    public enum DialogType {
        SAVE, LOAD
    }

    /**
     * Open the file dialog to show files from the connection.
     */
    public static RemoteFile chooseFile(String fileName, Project project, Client c, String title, String description, DialogType type) {
        return new FileDialog(project, c, title, description, type).choose(fileName);
    }

    private RemoteFile choose(String fileName) {
        var fs = new DatabaseFileSystem();
        var roots = fs.getRoots();

        if (roots.length == 0) {
            Notifications.Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, "Error occurred", "The database user for this connection is not allowed to read drives.", NotificationType.ERROR));
            return null;
        }

        var initial = getInitial(roots);
        return new Chooser((FileSaverDescriptor) new FileSaverDescriptor(title, description).withRoots(roots).withDescription(description), project).choose(initial, fileName);
    }

    private RemoteFile getInitial(VirtualFile[] roots) {
        var path = PropertiesComponent.getInstance(project).getValue(getSelectionKeyName());
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

        for (VirtualFile root : roots) {
            if (!root.getName().equals(parts[0])) {
                continue;
            }

            var current = Optional.of(root);
            for (int i = 1; i < parts.length && current.isPresent(); i++) {
                var ic = i;
                current = current.map(c -> c.findChild(parts[ic]));
            }

            if (current.isPresent()) {
                return (RemoteFile) current.get();
            }
        }
        return null;
    }

    /**
     * The regular FileSaverDialogImpl seems to lean a bit too much on regular files and not remote files.
     */
    private class Chooser extends FileSaverDialogImpl {
        private RemoteFile chosen;

        public Chooser(@NotNull FileSaverDescriptor descriptor, @Nullable Project project) {
            super(descriptor, project);
        }

        @Override
        public void setOKActionEnabled(boolean isEnabled) {
            var selected = getSelectedFile();
            getOKAction().setEnabled(selected != null && !selected.isDirectory() && (type == DialogType.SAVE || selected.exists()));
        }

        /**
         * Retrieve the selected file as a RemoteFile.
         */
        private RemoteFile getSelectedFile() {
            RemoteFile selected = (RemoteFile) myFileSystemTree.getSelectedFile();
            if (selected == null) {
                return null;
            }

            var fileName = myFileName.getText();
            if (!selected.getPath().endsWith(fileName)) {
                var parent = selected.isDirectory() ? selected : (RemoteFile) selected.getParent();
                return (RemoteFile) Optional.ofNullable(selected.getChild(fileName)).orElseGet(() -> new RemoteFile(((DatabaseFileSystem) parent.getFileSystem()), parent, parent.getPath() + "\\" + fileName, false, false));
            }
            return selected;
        }

        /**
         * Doesn't call the parent doOkAction because that one tries to find the selected file locally.
         */
        @Override
        protected void doOKAction() {
            var file = getSelectedFile();

            if (type == DialogType.SAVE && file != null && file.isExists() && Messages.YES != (Messages.showYesNoDialog(getRootPane(),
                    UIBundle.message("file.chooser.save.dialog.confirmation", file.getName()),
                    UIBundle.message("file.chooser.save.dialog.confirmation.title"),
                    Messages.getWarningIcon())) || (type == DialogType.LOAD && (file == null || !file.exists))) {
                return;
            }

            chosen = file;
            saveSelection(chosen);
            close(OK_EXIT_CODE);
        }

        private void saveSelection(RemoteFile file) {
            if (file != null) {
                if (!file.isDirectory()) {
                    file = (RemoteFile) file.getParent();
                }
                PropertiesComponent.getInstance(project).setValue(getSelectionKeyName(), file.getPath());
            }
        }

        public RemoteFile choose(RemoteFile initial, String fileName) {
            super.save(initial, fileName);
            return chosen;
        }

        @Override
        protected void restoreSelection(@Nullable VirtualFile toSelect) {
            if (toSelect == null) {
                return;
            }
            restoreSelection(toSelect, () -> myFileSystemTree.expand(toSelect, null));
        }

        /**
         * We must be doing something wrong but this is apparently needed, just selecting the file we want to select
         * isn't enough. We need to open the tree one by one :(
         */
        private void restoreSelection(@Nullable VirtualFile toSelect, Runnable andThen) {
            if (toSelect == null) {
                if (andThen != null) {
                    andThen.run();
                }
            } else {
                restoreSelection(toSelect.getParent(), () -> myFileSystemTree.select(toSelect, andThen));
            }
        }
    }

    /**
     * Lists files from the connection
     */
    private class DatabaseFileSystem extends VirtualFileSystem {
        @SneakyThrows
        public VirtualFile[] getRoots() {
            return SQLHelper.getDrives(connection).stream().map(r -> (String) r.get("Name")).map(p -> new RemoteFile(this, null, p, true, true)).toArray(RemoteFile[]::new);
        }

        @NotNull
        @Override
        public String getProtocol() {
            return "mssqlDb";
        }

        @Nullable
        @Override
        public VirtualFile findFileByPath(@NotNull String path) {
            return null;
        }

        @Override
        public void refresh(boolean asynchronous) {
            // Refreshing not needed
        }

        @Nullable
        @Override
        public VirtualFile refreshAndFindFileByPath(@NotNull String path) {
            return null;
        }

        @Override
        public void addVirtualFileListener(@NotNull VirtualFileListener listener) {
            // Not needed
        }

        @Override
        public void removeVirtualFileListener(@NotNull VirtualFileListener listener) {
            // Not needed
        }

        @Override
        protected void deleteFile(Object requestor, @NotNull VirtualFile vFile) {
            // Not needed
        }

        @Override
        protected void moveFile(Object requestor, @NotNull VirtualFile vFile, @NotNull VirtualFile newParent) {
            // Not needed
        }

        @Override
        protected void renameFile(Object requestor, @NotNull VirtualFile vFile, @NotNull String newName) {
            // Not needed
        }

        @Override
        protected VirtualFile createChildFile(Object requestor, @NotNull VirtualFile vDir, @NotNull String fileName) {
            return null;
        }

        @Override
        protected VirtualFile createChildDirectory(Object requestor, @NotNull VirtualFile vDir, @NotNull String dirName) {
            return null;
        }

        @Override
        protected VirtualFile copyFile(Object requestor, @NotNull VirtualFile virtualFile, @NotNull VirtualFile newParent, @NotNull String copyName) {
            return null;
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }
    }

    /**
     * Tree navigation from the connection
     */
    public class RemoteFile extends VirtualFile {
        private final DatabaseFileSystem databaseFileSystem;
        private final RemoteFile parent;
        private final String path;
        private final boolean directory;
        @Getter
        private final boolean exists;
        private VirtualFile[] children;
        @Getter
        @Setter
        @Accessors(chain = true)
        private long length = 0;

        public RemoteFile(DatabaseFileSystem databaseFileSystem, RemoteFile parent, String path, boolean directory, boolean exists) {
            this.databaseFileSystem = databaseFileSystem;
            this.parent = parent;
            this.path = path;
            this.directory = directory;
            this.exists = exists;
        }

        @NotNull
        @Override
        public String getName() {
            var idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            if (idx == -1) {
                return path;
            }
            return path.substring(idx + 1);
        }

        @NotNull
        @Override
        public VirtualFileSystem getFileSystem() {
            return databaseFileSystem;
        }

        @NotNull
        @Override
        public String getPath() {
            return path;
        }

        @Override
        public boolean isWritable() {
            return true;
        }

        @Override
        public boolean isDirectory() {
            return directory;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public VirtualFile getParent() {
            return parent;
        }

        @SneakyThrows
        @Override
        public VirtualFile[] getChildren() {
            if (children == null) {
                if (isDirectory()) {
                    children = SQLHelper.getSQLPathChildren(connection, path).stream().map(r -> new RemoteFile(databaseFileSystem, this, r.get("FullName").toString(), !Integer.valueOf(1).equals(r.get("IsFile")), true)).toArray(RemoteFile[]::new);
                } else {
                    children = new VirtualFile[]{};
                }
            }
            return children;
        }

        public VirtualFile getChild(String name) {
            for (VirtualFile child : getChildren()) {
                if (name.equals(child.getName())) {
                    return child;
                }
            }
            return null;
        }

        @Override
        public OutputStream getOutputStream(Object requestor, long newModificationStamp, long newTimeStamp) throws IOException {
            return null;
        }

        @NotNull
        @Override
        public byte[] contentsToByteArray() {
            return new byte[0];
        }

        @Override
        public long getTimeStamp() {
            return 0;
        }

        @Override
        public void refresh(boolean asynchronous, boolean recursive, @Nullable Runnable postRunnable) {
            if (children != null) {
                children = null;
                getChildren();
                if (postRunnable != null) {
                    postRunnable.run();
                }
            }
        }

        @Override
        public InputStream getInputStream() {
            return null;
        }

        @Override
        public boolean exists() {
            return isExists();
        }
    }

    @NotNull
    private String getSelectionKeyName() {
        return KEY_PREFIX + connection.getDbName();
    }
}
