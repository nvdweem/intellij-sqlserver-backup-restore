package dev.niels.sqlbackuprestore;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications.Bus;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

/**
 * Every balloon the plugin shows goes through here, so the notification group and the rule for picking a message out of
 * an exception live in one place instead of being spelled out at each of the dozen call sites.
 */
public final class Notifier {
    private Notifier() {
    }

    public static void error(@NotNull String title, @NotNull String content) {
        notify(NotificationType.ERROR, title, content);
    }

    /**
     * Reports {@code cause} under {@code title}, introduced by {@code context} ("Unable to restore Northwind").
     */
    public static void error(@NotNull String title, @NotNull String context, @NotNull Throwable cause) {
        error(title, context + ":\n" + rootMessage(cause));
    }

    public static void warning(@NotNull String title, @NotNull String content) {
        notify(NotificationType.WARNING, title, content);
    }

    /**
     * The message of the first exception in the chain that has one of its own. Futures hand back a
     * {@link java.util.concurrent.CompletionException} wrapping the real failure, and showing its message would tell
     * the user the name of a class rather than what the server said.
     */
    public static @NotNull String rootMessage(@NotNull Throwable t) {
        var cause = t;
        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }
        return StringUtils.defaultIfBlank(cause.getMessage(), cause.toString());
    }

    private static void notify(NotificationType type, String title, String content) {
        Bus.notify(new Notification(Constants.NOTIFICATION_GROUP, title, content, type));
    }
}
