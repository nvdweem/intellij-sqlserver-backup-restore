package dev.niels.sqlbackuprestore.query;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Task that keeps track of progress for backup/restore actions.
 */
@Slf4j
public class ProgressTask extends Task.Backgroundable {
    private static final Pattern progressPattern = Pattern.compile("\\d+");
    private final Consumer<Consumer<Pair<Auditor.MessageType, String>>> run;
    private ProgressIndicator indicator;

    public ProgressTask(@Nullable Project project, @Nls(capitalization = Nls.Capitalization.Title) @NotNull String title, boolean canBeCancelled, Consumer<Consumer<Pair<Auditor.MessageType, String>>> run) {
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

    private void consumeWarning(Pair<Auditor.MessageType, String> warning) {
        if (warning.getLeft() == Auditor.MessageType.WARN && warning.getRight().contains("3211")) {
            var matcher = progressPattern.matcher(warning.getRight());
            if (matcher.find()) {
                indicator.setFraction(Integer.parseInt(matcher.group()) / 100d);
            }
        } else {
            log.warn("Warning: {}:{}", warning.getLeft(), warning.getRight());
        }
    }
}
