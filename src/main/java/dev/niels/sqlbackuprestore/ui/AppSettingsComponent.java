package dev.niels.sqlbackuprestore.ui;

import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import dev.niels.sqlbackuprestore.AppSettingsState;
import lombok.Getter;
import org.apache.commons.lang.math.NumberUtils;

import javax.swing.JPanel;

public class AppSettingsComponent {
    @Getter
    private final JPanel mainPanel;
    private final JBTextField compressionSize = new JBTextField();
    private final JBCheckBox useCompressedBackup = new JBCheckBox("Use compressed backups");
    private final JBCheckBox useDbNameOnDownload = new JBCheckBox("Use DB name on backup and download");
    private final JBCheckBox askForRestoreFileLocations = new JBCheckBox("Ask for file locations when restoring");

    public AppSettingsComponent() {
        mainPanel = FormBuilder.createFormBuilder()
                .addComponent(useCompressedBackup)
                .addComponent(new JBLabel("This is supported for SQL2008+", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER))
                .addVerticalGap(1)
                .addLabeledComponent("Ask for compression when downloading file bigger than (MB)", compressionSize)
                .addComponent(new JBLabel("0 or empty to always ask.", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER))
                .addComponent(new JBLabel("Compressed backups will be faster but this might make the backup slightly smaller.", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER))
                .addVerticalGap(1)
                .addComponent(useDbNameOnDownload)
                .addComponent(new JBLabel("By default the name of the backup filename will be used", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER))
                .addVerticalGap(1)
                .addComponent(askForRestoreFileLocations)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    public boolean isModified() {
        var current = AppSettingsState.getInstance();
        var modified = !parse(compressionSize.getText()).equals(current.getCompressionSize());
        modified |= useCompressedBackup.isSelected() != current.isUseCompressedBackup();
        modified |= useDbNameOnDownload.isSelected() != current.isUseDbNameOnDownload();
        modified |= askForRestoreFileLocations.isSelected() != current.isAskForRestoreFileLocations();
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
        current.setAskForRestoreFileLocations(askForRestoreFileLocations.isSelected());
    }

    public void reset() {
        var current = AppSettingsState.getInstance();
        compressionSize.setText(current.getCompressionSize() == 0L ? "" : "" + current.getCompressionSize());
        useCompressedBackup.setSelected(current.isUseCompressedBackup());
        useDbNameOnDownload.setSelected(current.isUseDbNameOnDownload());
        askForRestoreFileLocations.setSelected(current.isAskForRestoreFileLocations());
    }
}
