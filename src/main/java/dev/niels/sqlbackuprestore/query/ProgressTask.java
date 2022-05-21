package dev.niels.sqlbackuprestore.query;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task.Backgroundable;
import com.intellij.openapi.project.Project;
import dev.niels.sqlbackuprestore.query.Auditor.MessageType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nls.Capitalization;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Task that keeps track of progress for backup/restore actions.
 */
@Slf4j
public class ProgressTask extends Backgroundable {
    private static final Pattern progressPattern = Pattern.compile("\\[3211] (\\d+)");
    private final Consumer<Consumer<Pair<MessageType, String>>> run;
    private ProgressIndicator indicator;

    public ProgressTask(@Nullable Project project, @Nls(capitalization = Capitalization.Sentence) @NotNull String title, boolean canBeCancelled, Consumer<Consumer<Pair<MessageType, String>>> run) {
        super(project, title, canBeCancelled);
        this.run = run;
    }

    @Override
    public void run(ProgressIndicator indicator) {
        this.indicator = indicator;
        indicator.setText(getTitle());
        indicator.setIndeterminate(false);
        indicator.setFraction(0.0);

        run.accept(this::consumeWarning);
    }

    private void consumeWarning(Pair<MessageType, String> warning) {
        if (warning.getLeft() == MessageType.WARN && warning.getRight().contains("3211")) {
            var matcher = progressPattern.matcher(warning.getRight());
            if (matcher.find()) {
                indicator.setFraction(Integer.parseInt(matcher.group(1)) / 100d);
            }
        } else {
            log.warn("Warning: {}:{}", warning.getLeft(), warning.getRight());
        }
    }
}
