package dev.niels.sqlbackuprestore.ui;

import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.junit5.RunInEdt;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.ui.table.JBTable;
import dev.niels.sqlbackuprestore.query.RestoreFile;
import org.junit.jupiter.api.Test;

import javax.swing.JScrollPane;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "where should each file go" dialog, built headlessly.
 * <p>
 * A modal dialog is only modal when it is shown, and nothing here shows one: the dialog is constructed, its table is
 * driven directly and its validation asked for an answer. That is enough to cover what actually went wrong with it -
 * a blank target silently producing broken SQL, and the cell the user was still typing in being thrown away.
 */
@TestApplication
@SuppressWarnings("deprecation") // RunInEdt is the class-level one; RunMethodInEdt only applies per method.
@RunInEdt
class RestoreFilenamesDialogTest {
    private static final int RESTORE_AS_COLUMN = 3;

    private static RestoreFile file(String logicalName, String type, String restoreAs) {
        return new RestoreFile(logicalName, "C:\\old\\" + logicalName, type).setRestoreAs(restoreAs);
    }

    /** The dialog's own table, reached the way the dialog builds it. */
    private static JBTable tableOf(RestoreFilenamesDialog dialog) {
        var panel = dialog.createCenterPanel();
        assertNotNull(panel);
        return (JBTable) ((JScrollPane) panel).getViewport().getView();
    }

    @Test
    void acceptsFilesThatAllHaveATarget() {
        var files = List.of(file("shop", "D", "C:\\Data\\shop.mdf"), file("shop_log", "L", "C:\\Data\\shop_log.ldf"));
        var dialog = new RestoreFilenamesDialog(null, files);
        try {
            tableOf(dialog);

            assertNull(dialog.doValidate());
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }
    }

    @Test
    void refusesABlankTargetAndSaysWhichFileItMeans() {
        var files = List.of(file("shop", "D", "C:\\Data\\shop.mdf"), file("shop_log", "L", ""));
        var dialog = new RestoreFilenamesDialog(null, files);
        try {
            tableOf(dialog);

            var validation = dialog.doValidate();

            // Without this the MOVE clause would name an empty path and the restore would fail on the server, if it
            // was lucky - or move a file somewhere unintended, if it was not.
            assertNotNull(validation, "a blank target was accepted");
            assertTrue(validation.message.contains("shop_log"), validation.message);
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }
    }

    @Test
    void refusesATargetThatIsOnlyWhitespace() {
        var dialog = new RestoreFilenamesDialog(null, List.of(file("shop", "D", "   ")));
        try {
            tableOf(dialog);

            assertNotNull(dialog.doValidate());
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }
    }

    @Test
    void writesAnEditedTargetBackToTheFileItBelongsTo() {
        var files = List.of(file("shop", "D", "C:\\Data\\shop.mdf"));
        var dialog = new RestoreFilenamesDialog(null, files);
        try {
            var table = tableOf(dialog);

            table.getModel().setValueAt("D:\\Elsewhere\\shop.mdf", 0, RESTORE_AS_COLUMN);

            // The RESTORE statement is built from the RestoreFile, not from the table, so the two have to stay in step.
            assertEquals("D:\\Elsewhere\\shop.mdf", files.getFirst().getRestoreAs());
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }
    }

    @Test
    void showsTheDefaultsItWasGiven() {
        var files = List.of(file("shop", "D", "C:\\Data\\shop.mdf"), file("shop_log", "L", "C:\\Data\\shop_log.ldf"));
        var dialog = new RestoreFilenamesDialog(null, files);
        try {
            var table = tableOf(dialog);

            assertEquals(2, table.getModel().getRowCount());
            assertEquals("shop", table.getModel().getValueAt(0, 0));
            assertEquals("C:\\Data\\shop_log.ldf", table.getModel().getValueAt(1, RESTORE_AS_COLUMN));
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }
    }

    @Test
    void onlyLetsTheTargetColumnBeEdited() {
        var dialog = new RestoreFilenamesDialog(null, List.of(file("shop", "D", "C:\\Data\\shop.mdf")));
        try {
            var model = tableOf(dialog).getModel();

            // The other three come from the backup itself; editing them would just be lying about what is inside it.
            assertTrue(model.isCellEditable(0, RESTORE_AS_COLUMN));
            assertTrue(!model.isCellEditable(0, 0) && !model.isCellEditable(0, 1) && !model.isCellEditable(0, 2));
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }
    }

    @Test
    void commitsTheCellStillBeingEditedWhenFocusMovesOn() {
        var dialog = new RestoreFilenamesDialog(null, List.of(file("shop", "D", "C:\\Data\\shop.mdf")));
        try {
            var table = tableOf(dialog);

            // A JTable otherwise keeps the value in the editor, and the last thing typed never reaches the model.
            assertEquals(Boolean.TRUE, table.getClientProperty("terminateEditOnFocusLost"));
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }
    }
}
