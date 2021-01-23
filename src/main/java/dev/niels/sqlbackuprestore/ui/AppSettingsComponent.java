package dev.niels.sqlbackuprestore.ui;

import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import dev.niels.sqlbackuprestore.AppSettingsState;
import lombok.Getter;
import org.apache.commons.lang.math.NumberUtils;

import javax.swing.JPanel;

public class AppSettingsComponent {
    @Getter
    private final JPanel mainPanel;
    private final JBTextField compressionSize = new JBTextField();
    private final JBCheckBox useCompressedBackup = new JBCheckBox();
    private final JBCheckBox useDbNameOnDownload = new JBCheckBox();

    public AppSettingsComponent() {
        mainPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Use compressed backups", useCompressedBackup)
                .addTooltip("This is supported for SQL2008+")
                .addLabeledComponent("Ask for compression when downloading file bigger than (MB)", compressionSize)
                .addTooltip("0 or empty to always ask.")
                .addTooltip("Compressed backups will be way faster but this might make a little bit")
                .addTooltip("of a difference.")
                .addLabeledComponent("Use DB name on backup and download", useDbNameOnDownload)
                .addTooltip("By default the name of the backup filename will be used")
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    public boolean isModified() {
        var current = AppSettingsState.getInstance();
        var modified = !parse(compressionSize.getText()).equals(current.getCompressionSize());
        modified |= useCompressedBackup.isSelected() != current.isUseCompressedBackup();
        modified |= useDbNameOnDownload.isSelected() != current.isUseDbNameOnDownload();
        return modified;
    }

    private Long parse(String in) {
        try {
            return NumberUtils.createNumber(in).longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public void apply() {
        var current = AppSettingsState.getInstance();
        current.setCompressionSize(parse(compressionSize.getText()));
        current.setUseCompressedBackup(useCompressedBackup.isSelected());
        current.setUseDbNameOnDownload(useDbNameOnDownload.isSelected());
    }

    public void reset() {
        var current = AppSettingsState.getInstance();
        compressionSize.setText(current.getCompressionSize() == 0L ? "" : "" + current.getCompressionSize());
        useCompressedBackup.setSelected(current.isUseCompressedBackup());
        useDbNameOnDownload.setSelected(current.isUseDbNameOnDownload());
    }
}
