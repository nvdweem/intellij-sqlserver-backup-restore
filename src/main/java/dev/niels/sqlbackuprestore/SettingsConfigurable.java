package dev.niels.sqlbackuprestore;

import com.intellij.openapi.options.Configurable;
import dev.niels.sqlbackuprestore.ui.AppSettingsComponent;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

public class SettingsConfigurable implements Configurable {
    private AppSettingsComponent settingsComponent;

    @Override
    public String getDisplayName() {
        return "SQLServer Backup And Restore";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        settingsComponent = new AppSettingsComponent();
        return settingsComponent.getMainPanel();
    }

    @Override
    public boolean isModified() {
        return settingsComponent.isModified();
    }

    @Override
    public void apply() {
        settingsComponent.apply();
    }

    @Override
    public void reset() {
        settingsComponent.reset();
    }

    @Override
    public void disposeUIResources() {
        settingsComponent = null;
    }
}
