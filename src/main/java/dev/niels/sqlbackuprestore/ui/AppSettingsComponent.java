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
    private final JBCheckBox useDbNameOnDownload = new JBCheckBox();

    public AppSettingsComponent() {
        mainPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Ask for compression when file is bigger than (MB)", compressionSize)
                .addTooltip("0 or empty to always ask")
                .addLabeledComponent("Use DB name on backup and download", useDbNameOnDownload)
                .addTooltip("By default the name of the backup filename will be used")
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    public boolean isModified() {
        var current = AppSettingsState.getInstance();
        var modified = !parse(compressionSize.getText()).equals(current.getCompressionSize());
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
        current.setUseDbNameOnDownload(useDbNameOnDownload.isSelected());
    }

    public void reset() {
        var current = AppSettingsState.getInstance();
        compressionSize.setText(current.getCompressionSize() == 0L ? "" : "" + current.getCompressionSize());
        useDbNameOnDownload.setSelected(current.isUseDbNameOnDownload());
    }
}
