package dev.niels.sqlbackuprestore;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.testFramework.junit5.TestApplication;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What plugin.xml declares, checked against a running headless IDE.
 * <p>
 * None of this is a compile error when it goes wrong. An unregistered notification group means every balloon the plugin
 * raises is logged as a warning and cannot be turned off in Settings | Notifications; a missing file system means the
 * restore picker silently browses local files instead of the server's.
 * <p>
 * The actions are covered by {@link PluginXmlTest} rather than through {@code ActionManager}: asking it for one action
 * initialises every action in the IDE, and IntelliJ's own bundled Java plugin logs an error while that happens, which
 * the test framework escalates into a failure of whatever test happened to trigger it.
 */
@TestApplication
class PluginRegistrationTest {
    @Test
    void registersTheNotificationGroupEveryBalloonUses() {
        assertNotNull(NotificationGroupManager.getInstance().getNotificationGroup(Constants.NOTIFICATION_GROUP),
                "balloons would be logged as 'notification group is not registered' and could not be configured");
    }

    @Test
    void registersTheVirtualFileSystemTheRemotePickerBrowses() {
        assertNotNull(VirtualFileManager.getInstance().getFileSystem("mssqldb"),
                "without this the restore picker falls back to browsing local files");
    }

    @Test
    void resolvesTheApplicationWideSettings() {
        assertNotNull(AppSettingsState.getInstance());
    }

}
