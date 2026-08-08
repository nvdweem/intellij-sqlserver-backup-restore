package dev.niels.sqlbackuprestore.ui.filedialog;

import com.intellij.openapi.fileChooser.tree.FileNode;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileSystem;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The size and date shown after a filename in the picker.
 * <p>
 * This shipped once already, doing nothing at all: the details were appended by a wrapped tree cell renderer that only
 * understood {@code FileNodeDescriptor} nodes, while the chooser actually builds its tree from a {@code FileTreeModel}
 * whose nodes are {@code FileNode}s. Nothing threw - the wrapper just never matched, and the picker looked exactly as
 * it had before. A test that only checked the server returns a size and a date, as one did, sees none of that.
 * <p>
 * So this drives the descriptor the picker really builds, through the same {@code getComment} the chooser's renderer
 * calls.
 */
@TestApplication
class FileDetailsTest {
    /** A file with whatever size and date the test wants; nothing here needs a server or a tree. */
    private static VirtualFile file(String name, long length, long timeStamp, boolean directory) {
        return new VirtualFile() {
            @Override public @NotNull String getName() { return name; }
            @Override public @NotNull VirtualFileSystem getFileSystem() { throw new UnsupportedOperationException(); }
            @Override public @NotNull String getPath() { return "C:\\Backups\\" + name; }
            @Override public boolean isWritable() { return true; }
            @Override public boolean isDirectory() { return directory; }
            @Override public boolean isValid() { return true; }
            @Override public VirtualFile getParent() { return null; }
            @Override public VirtualFile[] getChildren() { return new VirtualFile[0]; }
            @Override public @NotNull OutputStream getOutputStream(Object o, long l, long t) { throw new UnsupportedOperationException(); }
            @Override public byte @NotNull [] contentsToByteArray() { return new byte[0]; }
            @Override public long getTimeStamp() { return timeStamp; }
            @Override public long getLength() { return length; }
            @Override public void refresh(boolean a, boolean r, Runnable p) { /* nothing to refresh */ }
            @Override public @NotNull InputStream getInputStream() { throw new UnsupportedOperationException(); }
        };
    }

    private static final long A_DATE = 1_754_659_320_000L;

    @Test
    void showsSizeAndDateForAFile() {
        var comment = FileDetails.commentFor(file("shop.bak", 1_400_000_000L, A_DATE, false));

        assertNotNull(comment, "no details at all - this is the failure that shipped");
        assertTrue(comment.contains("1.4 GB"), comment);
        // Not asserting the exact rendering: DateFormatUtil follows the IDE's locale and date settings.
        assertTrue(comment.length() > "  1.4 GB".length(), "the date is missing: " + comment);
    }

    @Test
    void showsNothingForADirectory() {
        assertNull(FileDetails.commentFor(file("Backups", 0, A_DATE, true)));
    }

    @Test
    void showsNothingWhenTheServerReportsNeither() {
        // The xp_dirtree fallback, on servers without sys.dm_os_enumerate_filesystem, reports no size and no date.
        assertNull(FileDetails.commentFor(file("shop.bak", 0, 0, false)));
    }

    @Test
    void showsWhicheverOfTheTwoTheServerDidReport() {
        var sizeOnly = FileDetails.commentFor(file("shop.bak", 2048, 0, false));
        var dateOnly = FileDetails.commentFor(file("shop.bak", 0, A_DATE, false));

        assertEquals("  2.0 kB", sizeOnly);
        assertNotNull(dateOnly);
        assertFalse(dateOnly.contains("B"), "a size was shown for a file with no size: " + dateOnly);
    }

    @Test
    void separatesTheDetailsFromTheFilename() {
        // The chooser appends this straight after the name with nothing in between.
        assertTrue(FileDetails.commentFor(file("shop.bak", 2048, 0, false)).startsWith("  "));
    }

    @Test
    void readsTheFileOutOfTheNodeShapeTheChooserActuallyUses() throws Exception {
        // FileChooserDialogImpl builds a FileTreeModel, whose nodes are FileNodes. Reading only the other model's
        // FileNodeDescriptor is what made this feature display nothing at all, without ever throwing.
        var file = file("shop.bak", 2048, 0, false);
        var constructor = FileNode.class.getDeclaredConstructor(VirtualFile.class);
        constructor.setAccessible(true);

        assertSame(file, FileDetailsRenderer.fileOf(constructor.newInstance(file)));
    }

    @Test
    void readsABareFileToo() {
        var file = file("shop.bak", 2048, 0, false);

        assertSame(file, FileDetailsRenderer.fileOf(file));
    }

    @Test
    void ignoresAnythingThatIsNotAFileNode() {
        assertNull(FileDetailsRenderer.fileOf(null));
        assertNull(FileDetailsRenderer.fileOf("just a string"));
        assertNull(FileDetailsRenderer.fileOf(new DefaultMutableTreeNode("not a descriptor")));
    }

    @Test
    void appendsTheDetailsInGreySoTheyReadAsMetadata() {
        var delegate = new ColoredTreeCellRenderer() {
            @Override
            public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean s, boolean e, boolean l, int r, boolean f) {
                append("shop.bak");
            }
        };
        var renderer = new FileDetailsRenderer(delegate);

        var component = (SimpleColoredComponent) renderer.getTreeCellRendererComponent(
                new JTree(), file("shop.bak", 2048, 0, false), false, false, true, 0, false);

        var fragments = new ArrayList<String>();
        var greyed = new ArrayList<Boolean>();
        for (var it = component.iterator(); it.hasNext(); ) {
            fragments.add(it.next());
            greyed.add(SimpleTextAttributes.GRAYED_ATTRIBUTES.equals(it.getTextAttributes()));
        }

        assertEquals(List.of("shop.bak", "  2.0 kB"), fragments);
        // The point of the grey: detail rendered in the filename's own colour reads as part of the filename.
        assertEquals(List.of(false, true), greyed);
    }

    @Test
    void leavesADirectoryAlone() {
        var delegate = new ColoredTreeCellRenderer() {
            @Override
            public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean s, boolean e, boolean l, int r, boolean f) {
                append("Backups");
            }
        };

        var component = (SimpleColoredComponent) new FileDetailsRenderer(delegate).getTreeCellRendererComponent(
                new JTree(), file("Backups", 0, 0, true), false, false, false, 0, false);

        assertEquals("Backups", component.getCharSequence(false).toString());
    }

    @Test
    void recognisesEveryExtensionSqlServerWrites() {
        assertTrue(FileDialog.isBackup("shop.bak"));
        assertTrue(FileDialog.isBackup("shop.trn"));
        assertTrue(FileDialog.isBackup("shop.dif"));
        assertTrue(FileDialog.isBackup("shop.gzip"), "this plugin's own compressed download");
        assertTrue(FileDialog.isBackup("SHOP.BAK"), "the server's filesystem may be case-insensitive");

        assertFalse(FileDialog.isBackup("shop.mdf"));
        assertFalse(FileDialog.isBackup("shop.ldf"));
        assertFalse(FileDialog.isBackup("notes.txt"));
        assertFalse(FileDialog.isBackup("bak"), "an extension needs its dot");
    }
}
