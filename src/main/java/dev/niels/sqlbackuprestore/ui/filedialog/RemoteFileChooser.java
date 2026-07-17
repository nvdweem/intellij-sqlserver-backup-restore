package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.ex.FileChooserDialogImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Since 2026.2 {@code FileChooserFactory} yields the NIO-based {@code UniversalFileChooser}, which can't render our
 * non-local {@link DatabaseFileSystem} roots. The classic {@code FileChooserDialogImpl} still can, so we build it here.
 */
class RemoteFileChooser extends FileChooserDialogImpl {
    RemoteFileChooser(@NotNull FileChooserDescriptor descriptor, @Nullable Project project) {
        super(descriptor, project);
    }

    @Override
    protected @NotNull String getRecentPathsStorageKey() {
        return RemoteChooserRecents.STORAGE_KEY;
    }

    @Override
    protected @NotNull String getPresentableUrl(@NotNull VirtualFile virtualFile) {
        return RemoteChooserRecents.presentableUrl(virtualFile);
    }
}
