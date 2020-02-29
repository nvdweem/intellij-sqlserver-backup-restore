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

@Slf4j
public class ProgressTask extends Task.Backgroundable {
    private static final Pattern progressPattern = Pattern.compile("\\d+");
    private final Consumer<Consumer<SQLWarning>> run;
    private ProgressIndicator indicator;

    public ProgressTask(@Nullable Project project, @Nls(capitalization = Nls.Capitalization.Title) @NotNull String title, boolean canBeCancelled, Consumer<Consumer<SQLWarning>> run) {
        super(project, title, canBeCancelled);
        this.run = run;
    }

    @Override
    public void run(ProgressIndicator indicator) {
        this.indicator = indicator;
        indicator.setText("Restoring backup");
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
