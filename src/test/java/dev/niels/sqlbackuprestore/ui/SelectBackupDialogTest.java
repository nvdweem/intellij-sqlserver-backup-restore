package dev.niels.sqlbackuprestore.ui;

import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.junit5.RunInEdt;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.ui.table.JBTable;
import dev.niels.sqlbackuprestore.query.RemoteFileWithMeta;
import dev.niels.sqlbackuprestore.query.RemoteFileWithMeta.BackupType;
import dev.niels.sqlbackuprestore.ui.filedialog.DatabaseFileSystem;
import dev.niels.sqlbackuprestore.ui.filedialog.RemoteFile;
import org.junit.jupiter.api.Test;

import javax.swing.JScrollPane;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Choosing which backup to restore.
 * <p>
 * The bug this guards against restored a different backup than the highlighted one: the selection listener read the
 * first row of the *changed range* rather than the selected row, so clicking around could quietly swap the choice. That
 * is a wrong database, restored over a real one, reported as a success.
 */
@TestApplication
@SuppressWarnings("deprecation") // RunInEdt is the class-level one; RunMethodInEdt only applies per method.
@RunInEdt
class SelectBackupDialogTest {
    private static final DatabaseFileSystem FILE_SYSTEM = new DatabaseFileSystem();

    private static RemoteFileWithMeta backup(String name, BackupType type, long firstLsn, long databaseBackupLsn) {
        var file = new RemoteFile(FILE_SYSTEM, null, "C:\\Backups\\" + name, false, true);
        return new RemoteFileWithMeta(file, type, BigDecimal.valueOf(firstLsn), BigDecimal.valueOf(databaseBackupLsn),
                "2026-08-08 12:00:00", "NIELSPC");
    }

    private static JBTable tableOf(SelectBackupDialog dialog) {
        var panel = dialog.createCenterPanel();
        assertNotNull(panel);
        return (JBTable) ((JScrollPane) panel).getViewport().getView();
    }

    private static SelectBackupDialog dialogFor(Map<RemoteFileWithMeta, List<RemoteFileWithMeta>> choices) {
        // Built directly rather than through choose(), which shows the dialog and waits for a person.
        return new SelectBackupDialog(null, choices);
    }

    @Test
    void restoresTheRowThatIsActuallySelected() {
        var full = backup("full.bak", BackupType.FULL, 100, 0);
        var differential = backup("diff.bak", BackupType.DIFFERENTIAL, 200, 100);
        var dialog = dialogFor(new LinkedHashMap<>(Map.of(full, List.of(differential))));
        try {
            var table = tableOf(dialog);
            assertEquals(2, table.getRowCount(), "expected the full backup on its own and the differential under it");

            table.setRowSelectionInterval(1, 1);

            var result = dialog.getResult();
            assertNotNull(result, "selecting a row chose nothing");
            assertSame(differential, result.differentialBackup(), "a different backup than the selected row");
            assertSame(full, result.fullBackup(), "the differential must be restored on top of its own full backup");
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }
    }

    @Test
    void choosesTheFullBackupOnItsOwnWhenThatRowIsSelected() {
        var full = backup("full.bak", BackupType.FULL, 100, 0);
        var differential = backup("diff.bak", BackupType.DIFFERENTIAL, 200, 100);
        var dialog = dialogFor(new LinkedHashMap<>(Map.of(full, List.of(differential))));
        try {
            var table = tableOf(dialog);

            table.setRowSelectionInterval(0, 0);

            assertSame(full, Objects.requireNonNull(dialog.getResult()).fullBackup());
            assertNull(dialog.getResult().differentialBackup(), "restoring the full backup alone must not drag the differential in");
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }
    }

    @Test
    void followsTheSelectionWhenItMoves() {
        var full = backup("full.bak", BackupType.FULL, 100, 0);
        var differential = backup("diff.bak", BackupType.DIFFERENTIAL, 200, 100);
        var dialog = dialogFor(new LinkedHashMap<>(Map.of(full, List.of(differential))));
        try {
            var table = tableOf(dialog);

            table.setRowSelectionInterval(1, 1);
            table.setRowSelectionInterval(0, 0);

            // Reading the changed range rather than the selection is what made this land on the wrong row.
            assertNull(Objects.requireNonNull(dialog.getResult()).differentialBackup());
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }
    }

    @Test
    void refusesToConfirmUntilSomethingIsSelected() {
        var full = backup("full.bak", BackupType.FULL, 100, 0);
        var dialog = dialogFor(new LinkedHashMap<>(Map.of(full, List.of())));
        try {
            tableOf(dialog);

            assertNull(dialog.getResult());
            assertFalse(dialog.isOKActionEnabled(), "OK was clickable with nothing chosen");
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }
    }

    @Test
    void enablesConfirmingOnceARowIsChosen() {
        var full = backup("full.bak", BackupType.FULL, 100, 0);
        var dialog = dialogFor(new LinkedHashMap<>(Map.of(full, List.of())));
        try {
            tableOf(dialog).setRowSelectionInterval(0, 0);

            assertTrue(dialog.isOKActionEnabled());
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }
    }

    @Test
    void listsEveryFullBackupAndIndentsTheDifferentialsUnderIt() {
        var a = backup("a_full.bak", BackupType.FULL, 100, 0);
        var b = backup("b_full.bak", BackupType.FULL, 300, 0);
        var choices = new LinkedHashMap<RemoteFileWithMeta, List<RemoteFileWithMeta>>();
        choices.put(a, List.of(backup("a_diff.bak", BackupType.DIFFERENTIAL, 200, 100)));
        choices.put(b, List.of());
        var dialog = dialogFor(choices);
        try {
            var table = tableOf(dialog);

            assertEquals(3, table.getRowCount());
            // The indent is what makes a differential read as belonging to the full backup above it.
            assertFalse(table.getModel().getValueAt(0, 0).toString().startsWith("- "));
            assertTrue(table.getModel().getValueAt(1, 0).toString().startsWith("- "));
            assertFalse(table.getModel().getValueAt(2, 0).toString().startsWith("- "));
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }
    }
}
