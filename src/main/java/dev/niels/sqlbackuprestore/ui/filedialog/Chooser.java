package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.ex.FileLookup;
import com.intellij.openapi.fileChooser.ex.FileSaverDialogImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.UIBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static dev.niels.sqlbackuprestore.ui.filedialog.FileDialog.getSelectionKeyName;

/**
 * The regular FileSaverDialogImpl seems to lean a bit too much on regular files and not remote files.
 */
class Chooser extends FileSaverDialogImpl {
    private final Project project;
    private final List<VirtualFile> roots;
    private RemoteFile chosen;

    public Chooser(@NotNull FileSaverDescriptor descriptor, @Nullable Project project) {
        super(descriptor, project);
        this.project = project;
        this.roots = List.copyOf(descriptor.getRoots());
    }

    @Override
    protected @NotNull String getRecentPathsStorageKey() {
        return RemoteChooserRecents.STORAGE_KEY;
    }

    /** Resolve typed paths on the server, like the load dialog does. */
    @Override
    protected FileLookup.@NotNull Finder createFinder() {
        return new RemoteFsFinder(roots);
    }

    @Override
    protected @NotNull String getPresentableUrl(@NotNull VirtualFile virtualFile) {
        return RemoteChooserRecents.presentableUrl(virtualFile);
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
            return (RemoteFile) selected.getChild(fileName, true);
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
        saveSelection(chosen);
        close(OK_EXIT_CODE);
    }

    private void saveSelection(RemoteFile file) {
        if (file != null) {
            if (!file.isDirectory()) {
                file = (RemoteFile) file.getParent();
            }
            PropertiesComponent.getInstance(project).setValue(getSelectionKeyName(file.getConnection()), file.getPath());
        }
    }

    public RemoteFile choose(RemoteFile initial, String fileName) {
        save(initial, fileName);
        return chosen;
    }

    @Override
    protected void restoreSelection(@Nullable VirtualFile toSelect) {
        RemoteChooserTree.restoreSelection(myFileSystemTree, toSelect);
    }
}
