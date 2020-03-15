package dev.niels.sqlbackuprestore.ui;

import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.ex.FileSaverDialogImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileListener;
import com.intellij.openapi.vfs.VirtualFileSystem;
import com.intellij.ui.UIBundle;
import dev.niels.sqlbackuprestore.query.Client;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * File dialog to show remote files from SQLServer.
 */
@RequiredArgsConstructor
public class FileDialog {
    private final Project project;
    private final Client connection;
    private final String title;
    private final String description;

    /**
     * Open the file dialog to show files from the connection.
     */
    public static String chooseFile(Project project, Client c, String title, String description) {
        return new FileDialog(project, c, title, description).choose();
    }

    private String choose() {
        var fs = new DatabaseFileSystem();
        var roots = fs.getRoots();
        var chosen = new Chooser((FileSaverDescriptor) new FileSaverDescriptor(title, description).withRoots(roots).withDescription(description), project).choose();
        if (chosen != null) {
            return chosen.getPath();
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
            getOKAction().setEnabled(selected != null && !selected.isDirectory());
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
            if (file != null && file.isExists() && Messages.YES != Messages.showYesNoDialog(getRootPane(),
                    UIBundle.message("file.chooser.save.dialog.confirmation", file.getName()),
                    UIBundle.message("file.chooser.save.dialog.confirmation.title"),
                    Messages.getWarningIcon())) {
                return;
            }

            chosen = file;
            close(OK_EXIT_CODE);
        }

        public RemoteFile choose() {
            super.save(null, null);
            return chosen;
        }
    }

    /**
     * Lists files from the connection
     */
    private class DatabaseFileSystem extends VirtualFileSystem {
        @SneakyThrows
        public VirtualFile[] getRoots() {
            return connection.getResult("EXEC master..xp_fixeddrives").get(10, TimeUnit.SECONDS).stream().map(r -> (String) r.get("drive")).map(p -> new RemoteFile(this, null, p, true, true)).toArray(RemoteFile[]::new);
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
    private class RemoteFile extends VirtualFile {
        private final DatabaseFileSystem databaseFileSystem;
        private final RemoteFile parent;
        private final String path;
        private final boolean directory;
        @Getter
        private final boolean exists;
        private VirtualFile[] children;

        public RemoteFile(DatabaseFileSystem databaseFileSystem, RemoteFile parent, String path, boolean directory, boolean exists) {
            this.databaseFileSystem = databaseFileSystem;
            this.parent = parent;
            this.path = path.length() == 1 ? path + ":" : path;
            this.directory = directory;
            this.exists = exists;
        }

        @NotNull
        @Override
        public String getName() {
            var idx = path.lastIndexOf('/');
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
                    children = connection.getResult("EXEC xp_dirtree '" + path + "', 1, 1").get(10, TimeUnit.SECONDS).stream().map(r -> new RemoteFile(databaseFileSystem, this, path + "/" + r.get("subdirectory"), !Integer.valueOf(1).equals(r.get("file")), true)).toArray(RemoteFile[]::new);
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
        public byte[] contentsToByteArray() throws IOException {
            return new byte[0];
        }

        @Override
        public long getTimeStamp() {
            return 0;
        }

        @Override
        public long getLength() {
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
    }
}
