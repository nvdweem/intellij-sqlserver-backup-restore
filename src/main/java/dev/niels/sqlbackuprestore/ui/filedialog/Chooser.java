package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.ex.FileSaverDialogImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.UIBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

import static dev.niels.sqlbackuprestore.ui.filedialog.DialogType.SAVE;
import static dev.niels.sqlbackuprestore.ui.filedialog.FileDialog.getSelectionKeyName;

/**
 * The regular FileSaverDialogImpl seems to lean a bit too much on regular files and not remote files.
 */
class Chooser extends FileSaverDialogImpl {
    private final DialogType type;
    private final Project project;
    private RemoteFile chosen;

    public Chooser(DialogType type, @NotNull FileSaverDescriptor descriptor, @Nullable Project project) {
        super(descriptor, project);
        this.type = type;
        this.project = project;
    }

    @Override
    public void setOKActionEnabled(boolean isEnabled) {
        var selected = getSelectedFile();
        getOKAction().setEnabled(selected != null && !selected.isDirectory() && (type == SAVE || selected.exists()));
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
            return (RemoteFile) Optional.ofNullable(selected.getChild(fileName)).orElseGet(() -> new RemoteFile((DatabaseFileSystem) parent.getFileSystem(), parent, parent.getPath() + "\\" + fileName, false, false));
        }
        return selected;
    }

    /**
     * Doesn't call the parent doOkAction because that one tries to find the selected file locally.
     */
    @Override
    protected void doOKAction() {
        var file = getSelectedFile();

        if (type == SAVE && file != null && file.isExists() && Messages.YES != Messages.showYesNoDialog(getRootPane(),
                UIBundle.message("file.chooser.save.dialog.confirmation", file.getName()),
                UIBundle.message("file.chooser.save.dialog.confirmation.title"),
                Messages.getWarningIcon()) || (type == DialogType.LOAD && (file == null || !file.exists()))) {
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
