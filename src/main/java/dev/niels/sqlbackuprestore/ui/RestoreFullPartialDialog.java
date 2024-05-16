package dev.niels.sqlbackuprestore.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import dev.niels.sqlbackuprestore.action.Restore.RestoreAction;
import dev.niels.sqlbackuprestore.query.RemoteFileWithMeta;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RestoreFullPartialDialog extends DialogWrapper {
    private final Map<RemoteFileWithMeta, List<RemoteFileWithMeta>> fullsWithPartials;
    @Getter private RestoreAction result;

    private RestoreFullPartialDialog(@Nullable Project project, Map<RemoteFileWithMeta, List<RemoteFileWithMeta>> fullsWithPartials) {
        super(project);
        this.fullsWithPartials = fullsWithPartials;

        init();
        setTitle("Select Backup");
        setOKActionEnabled(false);
    }

    public static RestoreAction choose(@Nullable Project project, Map<RemoteFileWithMeta, List<RemoteFileWithMeta>> fullsWithPartials) {
        RestoreFullPartialDialog[] dialog = new RestoreFullPartialDialog[1];
        ApplicationManager.getApplication().invokeAndWait(() -> {
            dialog[0] = new RestoreFullPartialDialog(project, fullsWithPartials);
            dialog[0].showAndGet();
        });
        return dialog[0].result;
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

        table.getSelectionModel().addListSelectionListener(i -> {
            result = actions.get(i.getFirstIndex());
            setOKActionEnabled(true);
        });

        return new JBScrollPane(table);
    }
}
