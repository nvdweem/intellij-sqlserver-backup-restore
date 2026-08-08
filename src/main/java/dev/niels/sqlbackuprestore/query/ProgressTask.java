package dev.niels.sqlbackuprestore.query;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task.Backgroundable;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.niels.sqlbackuprestore.query.Auditor.MessageType;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nls.Capitalization;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Task that keeps track of progress for backup/restore actions.
 */
@Slf4j
public class ProgressTask extends Backgroundable {
    /** SQL Server reports progress as informational message 3211: "10 percent processed." */
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("\\[3211] (\\d+)");
    private static final long CANCEL_POLL_MS = 250;

    private final Consumer<BiConsumer<MessageType, String>> run;
    private final @Nullable Runnable onCancel;
    private ProgressIndicator indicator;

    /**
     * @param onCancel what to do when the user presses cancel, or {@code null} to leave the task uncancellable.
     *                 The button only appears when this is given.
     */
    public ProgressTask(@Nullable Project project, @Nls(capitalization = Capitalization.Sentence) @NotNull String title,
                        @Nullable Runnable onCancel, Consumer<BiConsumer<MessageType, String>> run) {
        super(project, title, onCancel != null);
        this.run = run;
        this.onCancel = onCancel;
    }

    @Override
    public void run(ProgressIndicator indicator) {
        this.indicator = indicator;
        indicator.setText(getTitle());
        indicator.setIndeterminate(false);
        indicator.setFraction(0.0);

        var watcher = watchForCancellation(indicator);
        try {
            run.accept(this::consumeWarning);
        } finally {
            if (watcher != null) {
                watcher.cancel(false);
            }
        }
    }

    /**
     * Cancelling marks the indicator and then waits for {@link #run} to return - the platform has no way to interrupt
     * a thread blocked on the database. So the flag is polled, and acting on it is up to us.
     */
    private @Nullable ScheduledFuture<?> watchForCancellation(ProgressIndicator indicator) {
        if (onCancel == null) {
            return null;
        }

        var fired = new AtomicBoolean(false);
        return AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(() -> {
            if (indicator.isCanceled() && fired.compareAndSet(false, true)) {
                try {
                    onCancel.run();
                } catch (Exception e) {
                    log.warn("Unable to cancel {}", getTitle(), e);
                }
            }
        }, CANCEL_POLL_MS, CANCEL_POLL_MS, TimeUnit.MILLISECONDS);
    }

    private void consumeWarning(MessageType type, String warning) {
        if (type == MessageType.WARN && warning.contains("3211")) {
            var matcher = PROGRESS_PATTERN.matcher(warning);
            if (matcher.find()) {
                var percent = Integer.parseInt(matcher.group(1));
                indicator.setFraction(percent / 100d);
                indicator.setText2(percent + "% processed");
            }
        } else {
            log.warn("Warning: {}:{}", type, warning);
        }
    }
}
