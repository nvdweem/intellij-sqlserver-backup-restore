package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.openapi.fileChooser.ex.FileLookup;
import com.intellij.openapi.fileChooser.ex.LocalFsFinder;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

/**
 * Resolves the chooser's path field against the SQL Server's filesystem instead of the IDE's local one.
 * <p>
 * {@code FileChooserDialogImpl} runs whatever is in the path field through its finder before it accepts OK, and its
 * stock {@code LocalFsFinder} only knows local files. When the server is elsewhere (a Linux container, another
 * machine) the selected path doesn't exist locally, so OK was silently rejected (#88). Wrapping the resolved
 * {@link RemoteFile} in the platform's own {@link LocalFsFinder.VfsFile} keeps the dialog's casts and the path
 * completion working.
 */
final class RemoteFsFinder implements FileLookup.Finder {
    private final List<VirtualFile> roots;
    private final String separator;

    RemoteFsFinder(List<? extends VirtualFile> roots) {
        this.roots = List.copyOf(roots);
        this.separator = roots.isEmpty() ? File.separator : RemotePaths.separator(roots.get(0).getPath());
    }

    @Override
    public FileLookup.@Nullable LookupFile find(@NotNull String path) {
        var file = RemoteFile.resolve(roots, path);
        return file == null ? null : new LocalFsFinder.VfsFile(file);
    }

    @Override
    public String normalize(@NotNull String path) {
        return RemotePaths.normalize(path);
    }

    @Override
    public @NotNull String getSeparator() {
        return separator;
    }
}
