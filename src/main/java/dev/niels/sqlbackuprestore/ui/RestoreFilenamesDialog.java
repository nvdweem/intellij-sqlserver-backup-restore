package dev.niels.sqlbackuprestore.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import dev.niels.sqlbackuprestore.action.Restore.RestoreTemp;
import one.util.streamex.StreamEx;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.table.DefaultTableModel;

public class RestoreFilenamesDialog extends DialogWrapper {
    private final RestoreTemp temp;

    public RestoreFilenamesDialog(@Nullable Project project, RestoreTemp temp) {
        super(project);
        this.temp = temp;

        init();
        setTitle("Files");
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        var model = new DefaultTableModel(new String[]{"Logical file name", "File type", "Original file name", "Restore as"}, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return column == 3;
            }

            @Override public void setValueAt(Object aValue, int row, int column) {
                super.setValueAt(aValue, row, column);
                temp.getFiles().get(row).put("RestoreAs", asString(aValue));
            }
        };
        StreamEx.of(temp.getFiles()).map(f -> new String[]{
                        StringUtils.defaultString(asString(f.get("LogicalName"))),
                        StringUtils.defaultString(asString(f.get("Type"))),
                        StringUtils.defaultString(asString(f.get("PhysicalName"))),
                        StringUtils.defaultString(asString(f.get("RestoreAs")))})
                .forEach(model::addRow);
        var table = new JBTable(model);
        table.getColumnModel().getColumn(0).setPreferredWidth(120);
        table.getColumnModel().getColumn(1).setPreferredWidth(60);
        table.getColumnModel().getColumn(2).setPreferredWidth(260);
        table.getColumnModel().getColumn(3).setPreferredWidth(260);

        return new JBScrollPane(table);
    }

    private String asString(Object o) {
        return o == null ? "" : o.toString();
    }
}
