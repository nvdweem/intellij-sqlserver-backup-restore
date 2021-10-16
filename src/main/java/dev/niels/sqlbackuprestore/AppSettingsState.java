package dev.niels.sqlbackuprestore;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(
        name = "dev.niels.sqlbackuprestore.AppSettingsState",
        storages = {@Storage("SQLBackupRestore.xml")}
)
@Data
public class AppSettingsState implements PersistentStateComponent<AppSettingsState> {
    private long compressionSize = 0L;
    private boolean useCompressedBackup = true;
    private boolean useDbNameOnDownload = false;
    private boolean askForRestoreFileLocations = false;

    public static AppSettingsState getInstance() {
        return ApplicationManager.getApplication().getService(AppSettingsState.class);
    }

    @Nullable
    @Override
    public AppSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull AppSettingsState state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}
