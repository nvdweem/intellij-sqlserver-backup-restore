package dev.niels.sqlbackuprestore.ui;

import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil.ComponentStyle;
import com.intellij.util.ui.UIUtil.FontColor;
import dev.niels.sqlbackuprestore.AppSettingsState;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.jetbrains.annotations.Nullable;

import javax.swing.JPanel;

public class AppSettingsComponent {
    @Getter
    private final JPanel mainPanel;
    private final JBTextField compressionSize = new JBTextField();
    private final JBCheckBox useCompressedBackup = new JBCheckBox("Use compressed backups");
    private final JBCheckBox useDbNameOnDownload = new JBCheckBox("Use DB name on backup and download");
    private final JBCheckBox askForRestoreFileLocations = new JBCheckBox("Ask for file locations when restoring");
    private final JBCheckBox enableDownloadOption = new JBCheckBox("Enable 'Backup and Download' option");
    private final JBCheckBox onlyShowBackupFiles = new JBCheckBox("Only show backup files when restoring");

    public AppSettingsComponent() {
        mainPanel = FormBuilder.createFormBuilder()
                .addComponent(useCompressedBackup)
                .addComponent(new JBLabel("This is supported for SQL2008+", ComponentStyle.SMALL, FontColor.BRIGHTER))
                .addVerticalGap(1)
                .addLabeledComponent("Ask for custom compression when downloading file bigger than (MB)", compressionSize)
                .addComponent(new JBLabel("0 or empty to always ask.", ComponentStyle.SMALL, FontColor.BRIGHTER))
                .addComponent(new JBLabel("Having the database compress the file will be faster but this might make the backup slightly smaller.", ComponentStyle.SMALL, FontColor.BRIGHTER))
                .addVerticalGap(1)
                .addComponent(useDbNameOnDownload)
                .addComponent(new JBLabel("By default, the name of the backup filename will be used", ComponentStyle.SMALL, FontColor.BRIGHTER))
                .addVerticalGap(1)
                .addComponent(askForRestoreFileLocations)
                .addVerticalGap(1)
                .addComponent(enableDownloadOption)
                .addComponent(new JBLabel("Can be used to download a backup from a remote database, not very useful for local database servers", ComponentStyle.SMALL, FontColor.BRIGHTER))
                .addVerticalGap(1)
                .addComponent(onlyShowBackupFiles)
                .addComponent(new JBLabel("Hides everything that isn't a .bak, .trn, .dif or .gzip. Turn off if your backups are named differently.", ComponentStyle.SMALL, FontColor.BRIGHTER))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    public boolean isModified() {
        var current = AppSettingsState.getInstance();
        var modified = parse(compressionSize.getText()) != current.getCompressionSize();
        modified |= useCompressedBackup.isSelected() != current.isUseCompressedBackup();
        modified |= useDbNameOnDownload.isSelected() != current.isUseDbNameOnDownload();
        modified |= askForRestoreFileLocations.isSelected() != current.isAskForRestoreFileLocations();
        modified |= enableDownloadOption.isSelected() != current.isEnableDownloadOption();
        modified |= onlyShowBackupFiles.isSelected() != current.isOnlyShowBackupFiles();
        return modified;
    }

    /**
     * Total parse of the compression size field: null, blank, unparseable and negative input all mean "always ask" (0).
     */
    private long parse(@Nullable String in) {
        if (StringUtils.isBlank(in)) {
            return 0L;
        }
        try {
            var number = NumberUtils.createNumber(in.trim());
            return number == null ? 0L : Math.max(0L, number.longValue());
        } catch (RuntimeException e) {
            // createNumber throws NumberFormatException for garbage, but has been known to throw other runtime
            // exceptions on malformed input as well; any failure simply means "no threshold configured".
            return 0L;
        }
    }

    public void apply() {
        var current = AppSettingsState.getInstance();
        current.setCompressionSize(parse(compressionSize.getText()));
        current.setUseCompressedBackup(useCompressedBackup.isSelected());
        current.setUseDbNameOnDownload(useDbNameOnDownload.isSelected());
        current.setAskForRestoreFileLocations(askForRestoreFileLocations.isSelected());
        current.setEnableDownloadOption(enableDownloadOption.isSelected());
        current.setOnlyShowBackupFiles(onlyShowBackupFiles.isSelected());
    }

    public void reset() {
        var current = AppSettingsState.getInstance();
        compressionSize.setText(current.getCompressionSize() == 0L ? "" : "" + current.getCompressionSize());
        useCompressedBackup.setSelected(current.isUseCompressedBackup());
        useDbNameOnDownload.setSelected(current.isUseDbNameOnDownload());
        askForRestoreFileLocations.setSelected(current.isAskForRestoreFileLocations());
        enableDownloadOption.setSelected(current.isEnableDownloadOption());
        onlyShowBackupFiles.setSelected(current.isOnlyShowBackupFiles());
    }

}
