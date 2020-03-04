package dev.niels.sqlbackuprestore.ui;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.ex.FileChooserDialogImpl;
import com.intellij.openapi.fileChooser.impl.FileChooserUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileListener;
import com.intellij.openapi.vfs.VirtualFileSystem;
import dev.niels.sqlbackuprestore.query.Connection;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * File dialog to show remote files from SQLServer.
 */
@RequiredArgsConstructor
public class FileDialog {
    private final Project project;
    private final Connection connection;
    private final String title;
    private final String description;

    /**
     * Open the file dialog to show files from the connection.
     */
    public static String chooseFile(Project project, Connection c, String title, String description) {
        return new FileDialog(project, c, title, description).choose();
    }

    private String choose() {
        var fs = new DatabaseFileSystem();
        var roots = fs.getRoots();
        var chosen = new Chooser(new FileChooserDescriptor(true, false, false, false, false, false)
                .withTitle(title).withRoots(roots).withDescription(description), project).choose(project);

        if (chosen.length > 0) {
            return chosen[0].getPath();
        }
        return null;
    }

    /**
     * Custom file chooser to work around the original class using the doOKAction check.
     */
    private class Chooser extends FileChooserDialogImpl {
        private VirtualFile[] myChosenFiles = VirtualFile.EMPTY_ARRAY;
        private final FileChooserDescriptor myChooserDescriptor;

        public Chooser(@NotNull FileChooserDescriptor descriptor, @Nullable Project project) {
            super(descriptor, project);
            myChooserDescriptor = descriptor;
        }

        /**
         * Blegh
         */
        @Override
        protected void doOKAction() {
            if (!isOKActionEnabled()) {
                return;
            }

            // Changed to not care about the file existing.
            if (myPathTextField.getField().getRootPane() != null) {
                String text = myPathTextField.getTextFieldText();
                myChosenFiles = new VirtualFile[]{new RemoteFile(null, null, StringUtils.removeStartIgnoreCase(text, "mssqlDb://"), false)};
                close(OK_EXIT_CODE);
                return;
            }

            List<VirtualFile> selectedFiles = Arrays.asList(myFileSystemTree.getSelectedFiles());
            VirtualFile[] files = VfsUtilCore.toVirtualFileArray(FileChooserUtil.getChosenFiles(myChooserDescriptor, selectedFiles));
            if (files.length == 0) {
                myChosenFiles = VirtualFile.EMPTY_ARRAY;
                close(CANCEL_EXIT_CODE);
                return;
            }

            try {
                myChooserDescriptor.validateSelectedFiles(files);
            } catch (Exception e) {
                Messages.showErrorDialog(getContentPane(), e.getMessage(), getTitle());
                return;
            }

            myChosenFiles = files;

            super.doOKAction();
        }

        @NotNull
        public VirtualFile[] choose(@Nullable Project project) {
            super.choose(project);
            return myChosenFiles;
        }
    }

    /**
     * Lists files from the connection
     */
    private class DatabaseFileSystem extends VirtualFileSystem {
        public VirtualFile[] getRoots() {
            return connection.getResult("EXEC master..xp_fixeddrives").map(rs -> rs.stream().map(r -> (String) r.get("drive")).map(p -> new RemoteFile(this, null, p, true)).toArray(RemoteFile[]::new)).orElse(new RemoteFile[0]);
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
        protected void deleteFile(Object requestor, @NotNull VirtualFile vFile) throws IOException {
            // Not needed
        }

        @Override
        protected void moveFile(Object requestor, @NotNull VirtualFile vFile, @NotNull VirtualFile newParent) throws IOException {
            // Not needed
        }

        @Override
        protected void renameFile(Object requestor, @NotNull VirtualFile vFile, @NotNull String newName) throws IOException {
            // Not needed
        }

        @Override
        protected VirtualFile createChildFile(Object requestor, @NotNull VirtualFile vDir, @NotNull String fileName) throws IOException {
            return null;
        }

        @Override
        protected VirtualFile createChildDirectory(Object requestor, @NotNull VirtualFile vDir, @NotNull String dirName) throws IOException {
            return null;
        }

        @Override
        protected VirtualFile copyFile(Object requestor, @NotNull VirtualFile virtualFile, @NotNull VirtualFile newParent, @NotNull String copyName) throws IOException {
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
        private VirtualFile[] children;

        public RemoteFile(DatabaseFileSystem databaseFileSystem, RemoteFile parent, String path, boolean directory) {
            this.databaseFileSystem = databaseFileSystem;
            this.parent = parent;
            this.path = path.length() == 1 ? path + ":" : path;
            this.directory = directory;
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

        @Override
        public VirtualFile[] getChildren() {
            if (children == null) {
                children = connection.getResult("EXEC xp_dirtree '" + path + "', 1, 1").map(rs -> rs.stream().map(r -> new RemoteFile(databaseFileSystem, this, path + "/" + r.get("subdirectory"), !Integer.valueOf(1).equals(r.get("file")))).toArray(RemoteFile[]::new)).orElse(new RemoteFile[0]);
            }
            return children;
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
            // Refreshing not needed
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return null;
        }
    }
}
