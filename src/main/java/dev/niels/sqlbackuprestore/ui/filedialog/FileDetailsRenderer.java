package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.openapi.fileChooser.ex.FileNodeDescriptor;
import com.intellij.openapi.fileChooser.tree.FileNode;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.Nullable;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeCellRenderer;
import java.awt.Component;

/**
 * Appends a file's size and date after its name, in the grey the IDE uses for secondary detail.
 * <p>
 * The chooser's own {@code getComment} hook would be simpler, but it renders the comment in the same attributes as the
 * filename, which reads as part of the name rather than as metadata. Hence a renderer, wrapped rather than replaced, so
 * icons and everything else still come from the platform.
 * <p>
 * The catch, and the reason the first attempt at this displayed nothing at all: which objects arrive as tree nodes
 * depends on which model the chooser built. {@code FileChooserDialogImpl} passes a null renderer to
 * {@code FileSystemTreeImpl}, which takes the branch that builds a {@code FileTreeModel} and leaves the tree structure
 * null - so its nodes are {@link FileNode}s, and the {@link FileNodeDescriptor}s of the other model never appear. All
 * three shapes are handled here, and {@link #fileOf} is the piece that is worth testing.
 */
class FileDetailsRenderer implements TreeCellRenderer {
    private final TreeCellRenderer delegate;

    FileDetailsRenderer(TreeCellRenderer delegate) {
        this.delegate = delegate;
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        var component = delegate.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
        var file = fileOf(value);
        var details = file == null ? null : FileDetails.commentFor(file);
        if (component instanceof SimpleColoredComponent colored && details != null) {
            colored.append(details, SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
        return component;
    }

    /**
     * The file a tree node stands for, whichever way the chooser happens to be modelling its tree.
     *
     * @return {@code null} for anything that is not a file node, which renders exactly as it did before.
     */
    static @Nullable VirtualFile fileOf(@Nullable Object value) {
        return switch (value) {
            // What FileTreeModel produces, which is what this chooser actually uses.
            case FileNode node -> node.getFile();
            // FileRenderer handles bare files too, so they can reach a renderer.
            case VirtualFile file -> file;
            // The other model, via FileTreeStructure. Not used here, but cheap to keep working.
            case DefaultMutableTreeNode node when node.getUserObject() instanceof FileNodeDescriptor descriptor ->
                    descriptor.getElement() == null ? null : descriptor.getElement().getFile();
            case null, default -> null;
        };
    }
}
