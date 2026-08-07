package dev.niels.sqlbackuprestore.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import dev.niels.sqlbackuprestore.action.Restore.RestoreTemp;
import one.util.streamex.StreamEx;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.table.DefaultTableModel;

public class RestoreFilenamesDialog extends DialogWrapper {
    private static final int LOGICAL_NAME_COLUMN = 0;
    private static final int RESTORE_AS_COLUMN = 3;

    private final RestoreTemp temp;
    private DefaultTableModel model;
    private JBTable table;

    public RestoreFilenamesDialog(@Nullable Project project, RestoreTemp temp) {
        super(project);
        this.temp = temp;

        init();
        setTitle("Files");
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        model = new DefaultTableModel(new String[]{"Logical file name", "File type", "Original file name", "Restore as"}, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return column == RESTORE_AS_COLUMN;
            }

            @Override public void setValueAt(Object aValue, int row, int column) {
                super.setValueAt(aValue, row, column);
                temp.getFiles().get(row).put("RestoreAs", asString(aValue));
            }
        };
        StreamEx.of(temp.getFiles()).map(f -> new String[]{
                        asString(f.get("LogicalName")),
                        asString(f.get("Type")),
                        asString(f.get("PhysicalName")),
                        asString(f.get("RestoreAs"))})
                .forEach(model::addRow);
        table = new JBTable(model);
        table.getColumnModel().getColumn(0).setPreferredWidth(120);
        table.getColumnModel().getColumn(1).setPreferredWidth(60);
        table.getColumnModel().getColumn(2).setPreferredWidth(260);
        table.getColumnModel().getColumn(3).setPreferredWidth(260);
        // Without this a cell that still has focus keeps its value in the editor and never reaches the model.
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        return new JBScrollPane(table);
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (model == null) {
            return null;
        }
        for (var row = 0; row < model.getRowCount(); row++) {
            if (StringUtils.isBlank(asString(model.getValueAt(row, RESTORE_AS_COLUMN)))) {
                var logicalName = asString(model.getValueAt(row, LOGICAL_NAME_COLUMN));
                return new ValidationInfo("Enter a target location for file '" + logicalName + "'", table);
            }
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        stopEditing();

        var validation = doValidate();
        if (validation != null) {
            setErrorText(validation.message, validation.component);
            return;
        }
        super.doOKAction();
    }

    /**
     * Commits the value of the cell that is currently being edited; a {@link javax.swing.JTable} otherwise keeps it in
     * the editor and the last thing the user typed would be silently dropped.
     */
    private void stopEditing() {
        if (table == null || !table.isEditing()) {
            return;
        }
        var editor = table.getCellEditor();
        if (editor != null && !editor.stopCellEditing()) {
            editor.cancelCellEditing();
        }
    }

    private String asString(Object o) {
        return o == null ? "" : o.toString();
    }
}
