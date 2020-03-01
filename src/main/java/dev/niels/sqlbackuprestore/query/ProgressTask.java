package dev.niels.sqlbackuprestore.query;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLWarning;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Task that keeps track of progress for backup/restore actions.
 */
@Slf4j
public class ProgressTask extends Task.Backgroundable {
    private static final Pattern progressPattern = Pattern.compile("\\d+");
    private final Consumer<Consumer<SQLWarning>> run;
    private ProgressIndicator indicator;
    private Runnable afterFinish;

    public ProgressTask(@Nullable Project project, @Nls(capitalization = Nls.Capitalization.Title) @NotNull String title, boolean canBeCancelled, Consumer<Consumer<SQLWarning>> run) {
        super(project, title, canBeCancelled);
        this.run = run;
    }

    public ProgressTask afterFinish(Runnable r) {
        afterFinish = r;
        return this;
    }

    @Override
    public void onFinished() {
        afterFinish.run();
    }

    @Override
    public void run(ProgressIndicator indicator) {
        this.indicator = indicator;
        indicator.setText(getTitle());
        indicator.setIndeterminate(false);
        indicator.setFraction(0.0);

        run.accept(this::consumeWarning);
    }

    private void consumeWarning(SQLWarning warning) {
        if (warning.getErrorCode() == 3211) {
            var matcher = progressPattern.matcher(warning.getMessage());
            if (matcher.find()) {
                indicator.setFraction(Integer.parseInt(matcher.group()) / 100d);
            }
        } else {
            log.warn("Warning: {}:{}", warning.getErrorCode(), warning.getMessage());
        }
    }
}
