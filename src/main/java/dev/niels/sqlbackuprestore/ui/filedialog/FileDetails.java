package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.text.DateFormatUtil;
import dev.niels.sqlbackuprestore.action.Util;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;

/**
 * The size and modification date shown after a filename in the remote picker, so that a directory holding a dozen
 * backups can be told apart by something other than the names.
 * <p>
 * Delivered through {@link FileChooserDescriptor#getComment}, which the chooser's own renderer appends after the name.
 * The first attempt wrapped the tree's cell renderer instead and silently displayed nothing: the chooser builds its
 * tree with a {@code FileTreeModel}, whose nodes are {@code FileNode}s, and the wrapper only knew how to read the
 * {@code FileNodeDescriptor}s of the other, unused tree model. The descriptor hook has no such coupling - it is handed
 * the file itself - and unlike a renderer it can be tested without a tree at all.
 */
final class FileDetails {
    private FileDetails() {
    }

    /**
     * @return {@code null} when there is nothing worth adding, which is what the platform expects for "no comment".
     * Directories get nothing: the listing reports no size for them.
     */
    static @Nullable String commentFor(@NotNull VirtualFile file) {
        if (file.isDirectory()) {
            return null;
        }

        var parts = new ArrayList<String>(2);
        if (file.getLength() > 0) {
            parts.add(Util.humanReadableByteCountSI(file.getLength()));
        }
        if (file.getTimeStamp() > 0) {
            parts.add(DateFormatUtil.formatDateTime(file.getTimeStamp()));
        }

        // Servers old enough to need the xp_dirtree fallback report neither a size nor a date.
        return parts.isEmpty() ? null : "  " + String.join(", ", parts);
    }
}
