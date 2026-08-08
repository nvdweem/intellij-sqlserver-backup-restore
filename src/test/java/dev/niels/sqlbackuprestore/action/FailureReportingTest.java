package dev.niels.sqlbackuprestore.action;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.junit5.TestApplication;
import dev.niels.sqlbackuprestore.Constants;
import dev.niels.sqlbackuprestore.Notifier;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the user is actually told, captured off the application's notification bus.
 * <p>
 * The original bug in this plugin was a failed backup reporting success, so the type of the balloon is not decoration -
 * it is the whole message. The distinction that matters most is cancelled-versus-failed: pressing Cancel is not an
 * error, and a restore killed part-way leaves a database that cannot be opened, which the user has to be told how to
 * get rid of.
 */
@TestApplication
class FailureReportingTest {
    private final List<Notification> captured = new ArrayList<>();
    private com.intellij.openapi.Disposable subscription;

    @BeforeEach
    void listen() {
        subscription = Disposer.newDisposable("notification-capture");
        ApplicationManager.getApplication().getMessageBus().connect(subscription)
                .subscribe(Notifications.TOPIC, new Notifications() {
                    @Override
                    public void notify(@NotNull Notification notification) {
                        captured.add(notification);
                    }
                });
    }

    @AfterEach
    void stopListening() {
        Disposer.dispose(subscription);
    }

    private Notification only() {
        assertEquals(1, captured.size(), "expected exactly one notification but got " + captured);
        return captured.getFirst();
    }

    @Test
    void raisesEveryBalloonUnderTheGroupThatIsRegistered() {
        Notifier.information("Backup finished", "Backed up shop.");

        // A group id that is not declared in plugin.xml makes the platform log a warning per balloon, and the user
        // cannot turn any of them off.
        assertEquals(Constants.NOTIFICATION_GROUP, only().getGroupId());
    }

    @Test
    void reportsAFailedBackupAsAnError() {
        Backup.reportBackupFailure(false, "shop", "C:\\Backups\\shop.bak", new IllegalStateException("Cannot open backup device"));

        assertEquals(NotificationType.ERROR, only().getType(), "a failed backup reported as anything else is the original bug");
        assertEquals("Backup failed", only().getTitle());
        assertTrue(only().getContent().contains("shop"), only().getContent());
    }

    @Test
    void reportsACancelledBackupAsAWarningNamingTheFileToDelete() {
        Backup.reportBackupFailure(true, "shop", "C:\\Backups\\shop.bak", new IllegalStateException("killed"));

        var notification = only();
        assertEquals(NotificationType.WARNING, notification.getType(), "the user cancelled; that is not a failure");
        assertEquals("Backup cancelled", notification.getTitle());
        // A killed BACKUP leaves a partial file behind that looks usable.
        assertTrue(notification.getContent().contains("C:\\Backups\\shop.bak"), notification.getContent());
        assertTrue(notification.getContent().contains("incomplete"), notification.getContent());
    }

    @Test
    void saysHowFarAMultiDatabaseBackupGotBeforeItWasCancelled() {
        Backup.reportMultiBackupFailure(true, 2, 5, "C:\\Backups", new IllegalStateException("killed"));

        var notification = only();
        assertEquals(NotificationType.WARNING, notification.getType());
        // The two that finished are complete and usable; only the one that was running is not.
        assertTrue(notification.getContent().contains("2 of 5"), notification.getContent());
        assertTrue(notification.getContent().contains("C:\\Backups"), notification.getContent());
    }

    @Test
    void reportsAFailedMultiDatabaseBackupAsAnError() {
        Backup.reportMultiBackupFailure(false, 2, 5, "C:\\Backups", new IllegalStateException("Access is denied"));

        assertEquals(NotificationType.ERROR, only().getType());
        assertEquals("Backup failed", only().getTitle());
    }

    @Test
    void reportsAFailedRestoreAsAnError() {
        Restore.reportRestoreFailure(false, "shop", new IllegalStateException("is not a valid backup set"));

        assertEquals(NotificationType.ERROR, only().getType());
        assertEquals("Restore failed", only().getTitle());
    }

    @Test
    void tellsTheUserHowToGetRidOfADatabaseLeftMidRestore() {
        Restore.reportRestoreFailure(true, "shop", new IllegalStateException("killed"));

        var notification = only();
        assertEquals(NotificationType.WARNING, notification.getType());
        assertEquals("Restore cancelled", notification.getTitle());
        // Killing a RESTORE does not undo it; the database is inaccessible until it is dropped or finished, and
        // without this sentence the user has no way to know that.
        assertTrue(notification.getContent().contains("restoring"), notification.getContent());
        assertTrue(notification.getContent().contains("drop it"), notification.getContent());
    }

    @Test
    void putsTheCauseOfAFailureInFrontOfTheUser() {
        var cause = new IllegalStateException("Operating system error 5(Access is denied.)");

        Notifier.error("Backup failed", "Unable to back up shop", cause);

        // Without the server's own message there is nothing to act on - "it failed" is not a diagnosis.
        assertTrue(only().getContent().contains("Operating system error 5"), only().getContent());
    }

    @Test
    void looksPastAWrapperThatHasNothingToSayOfItsOwn() {
        // What the asynchronous chains produce: a wrapper with no message of its own around the one that matters.
        var wrapped = new RuntimeException((String) null, new IllegalStateException("Cannot open backup device"));

        assertEquals("Cannot open backup device", Notifier.rootMessage(wrapped));
    }

    @Test
    void keepsTheServersMessageWhenTheWrapperDerivedItsOwnFromIt() {
        // RuntimeException(Throwable) copies cause.toString() into its message, so there is nothing to unwrap - the
        // text is already there, class name and all.
        var wrapped = new RuntimeException(new IllegalStateException("Cannot open backup device"));

        assertTrue(Notifier.rootMessage(wrapped).contains("Cannot open backup device"), Notifier.rootMessage(wrapped));
    }

    @Test
    void fallsBackToTheExceptionItselfWhenThereIsNoMessageAnywhere() {
        // Better than a balloon that says "null".
        assertEquals("java.lang.IllegalStateException", Notifier.rootMessage(new IllegalStateException()));
    }
}
