package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.openapi.vfs.VirtualFile;

/**
 * Since 2026.2 {@code FileChooserDialogImpl}'s recent-paths bar runs {@code Path.of()} over every remembered entry.
 * Its default entry is the file url ({@code mssqldb://C:\...}), which throws {@link java.nio.file.InvalidPathException}
 * - crashing the dialog on the second open. So the choosers remember a plain, parseable path under a private key.
 */
final class RemoteChooserRecents {
    /** Private so a would-be-crashing entry never mixes with the IDE-wide recents. */
    static final String STORAGE_KEY = "dev.niels.sqlbackuprestore.recentFiles";

    private RemoteChooserRecents() {
    }

    /** Plain path (e.g. {@code C:\temp\db.bak}); {@code Path.of()} rejects the {@code mssqldb://} url. */
    static String presentableUrl(VirtualFile file) {
        return file.getPath();
    }
}
