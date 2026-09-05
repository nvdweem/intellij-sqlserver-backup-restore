package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.ex.FileChooserDialogImpl;
import com.intellij.openapi.fileChooser.ex.FileLookup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Since 2026.2 {@code FileChooserFactory} yields the NIO-based {@code UniversalFileChooser}, which can't render our
 * non-local {@link DatabaseFileSystem} roots. The classic {@code FileChooserDialogImpl} still can, so we build it here.
 */
class RemoteFileChooser extends FileChooserDialogImpl {
    private static final String REFRESH_ACTION_ID = "FileChooser.Refresh";
    private final List<VirtualFile> roots;

    RemoteFileChooser(@NotNull FileChooserDescriptor descriptor, @Nullable Project project) {
        super(descriptor, project);
        this.roots = List.copyOf(descriptor.getRoots());
    }

    @Override
    protected @NotNull String getRecentPathsStorageKey() {
        return RemoteChooserRecents.STORAGE_KEY;
    }

    @Override
    protected @NotNull String getPresentableUrl(@NotNull VirtualFile virtualFile) {
        return RemoteChooserRecents.presentableUrl(virtualFile);
    }

    /** OK checks the path field through this finder; the stock one only accepts paths that exist on the IDE's machine. */
    @Override
    protected FileLookup.@NotNull Finder createFinder() {
        return new RemoteFsFinder(roots);
    }

    @Override
    protected void restoreSelection(@Nullable VirtualFile toSelect) {
        RemoteChooserTree.restoreSelection(myFileSystemTree, toSelect);
    }

    /**
     * The stock refresh only reloads the persistent VFS, so it does nothing for our remote roots. Swap it for one
     * that re-queries the server.
     */
    @Override
    protected DefaultActionGroup createActionGroup() {
        var group = super.createActionGroup();
        var actionManager = ActionManager.getInstance();
        for (var action : group.getChildActionsOrStubs()) {
            if (REFRESH_ACTION_ID.equals(actionManager.getId(action))) {
                group.remove(action);
                break;
            }
        }
        group.addAction(new RefreshAction());
        return group;
    }

    /** Drop the cached children under the selected directory (or every root) and repaint from the server. */
    private void reload() {
        var selected = myFileSystemTree.getSelectedFile();
        var directory = selected instanceof RemoteFile file
                ? (file.isDirectory() ? file : file.getParent() instanceof RemoteFile parent ? parent : null)
                : null;
        if (directory != null) {
            directory.invalidateChildren();
        } else {
            roots.forEach(root -> ((RemoteFile) root).invalidateChildren());
        }
        myFileSystemTree.updateTree();
    }

    private class RefreshAction extends DumbAwareAction {
        RefreshAction() {
            super(() -> "Refresh", () -> "Reload the file list from the server", AllIcons.Actions.Refresh);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            reload();
        }
    }
}
