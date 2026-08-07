package dev.niels.sqlbackuprestore.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import dev.niels.sqlbackuprestore.action.Restore.RestoreAction;
import dev.niels.sqlbackuprestore.query.RemoteFileWithMeta;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RestoreFullPartialDialog extends DialogWrapper {
    private final Map<RemoteFileWithMeta, List<RemoteFileWithMeta>> fullsWithPartials;
    @Getter private @Nullable RestoreAction result;

    private RestoreFullPartialDialog(@Nullable Project project, Map<RemoteFileWithMeta, List<RemoteFileWithMeta>> fullsWithPartials) {
        super(project);
        this.fullsWithPartials = fullsWithPartials;

        init();
        setTitle("Select Backup");
        setOKActionEnabled(false);
    }

    public static @Nullable RestoreAction choose(@Nullable Project project, Map<RemoteFileWithMeta, List<RemoteFileWithMeta>> fullsWithPartials) {
        var dialog = new RestoreFullPartialDialog[1];
        var confirmed = new boolean[1];
        ApplicationManager.getApplication().invokeAndWait(() -> {
            dialog[0] = new RestoreFullPartialDialog(project, fullsWithPartials);
            confirmed[0] = dialog[0].showAndGet();
        });
        // Only honour the selection when the dialog was actually closed with OK; cancelling must not restore anything.
        return dialog[0] != null && confirmed[0] ? dialog[0].result : null;
    }

    private String[] fileToStringArr(RestoreAction action) {
        var sub = action.partialBackup() != null;
        var file = sub ? action.partialBackup() : action.fullBackup();
        return new String[]{
                (sub ? "- " : "") + file.getFile().getPath(),
                file.getType().toString(),
                file.getBackupFinishDate(),
                file.getMachineName()
        };
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        var model = new DefaultTableModel(new String[]{"File", "Backup type", "Backup date", "Machine name"}, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        var actions = new ArrayList<RestoreAction>();
        fullsWithPartials.forEach((key, value) -> {
            actions.add(new RestoreAction(key, null));
            value.forEach(f -> actions.add(new RestoreAction(key, f)));
        });
        actions.forEach(a -> model.addRow(fileToStringArr(a)));

        var table = new JBTable(model);
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(75);
        table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            updateSelection(table, actions);
        });

        new DoubleClickListener() {
            @Override protected boolean onDoubleClick(@NotNull MouseEvent event) {
                if (table.rowAtPoint(event.getPoint()) < 0) {
                    return false;
                }
                updateSelection(table, actions);
                if (result == null) {
                    return false;
                }
                doOKAction();
                return true;
            }
        }.installOn(table);

        return new JBScrollPane(table);
    }

    /**
     * Resolves the row the user actually selected (the selection event only reports the changed range, not the
     * selection) and keeps the OK action in sync with it.
     */
    private void updateSelection(JBTable table, List<RestoreAction> actions) {
        var viewRow = table.getSelectedRow();
        var modelRow = viewRow < 0 ? -1 : table.convertRowIndexToModel(viewRow);
        result = modelRow >= 0 && modelRow < actions.size() ? actions.get(modelRow) : null;
        setOKActionEnabled(result != null);
    }
}
