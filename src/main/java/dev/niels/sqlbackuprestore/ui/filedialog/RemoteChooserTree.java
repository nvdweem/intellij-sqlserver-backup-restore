package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.openapi.fileChooser.ex.FileSystemTreeImpl;
import com.intellij.openapi.vfs.VirtualFile;

/**
 * The remote tree loads children lazily, so the chooser's own one-shot selectInTree() can't reach a file whose
 * ancestors aren't expanded yet. Expand from the root down, one level per callback, then select the file.
 */
final class RemoteChooserTree {
    private RemoteChooserTree() {
    }

    static void restoreSelection(FileSystemTreeImpl tree, VirtualFile toSelect) {
        if (toSelect != null) {
            expandThenSelect(tree, toSelect, () -> tree.expand(toSelect, null));
        }
    }

    private static void expandThenSelect(FileSystemTreeImpl tree, VirtualFile toSelect, Runnable andThen) {
        if (toSelect == null) {
            andThen.run();
        } else {
            expandThenSelect(tree, toSelect.getParent(), () -> tree.select(toSelect, andThen));
        }
    }
}
