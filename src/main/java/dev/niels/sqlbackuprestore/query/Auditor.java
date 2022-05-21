package dev.niels.sqlbackuprestore.query;

import com.intellij.database.connection.throwable.info.ErrorInfo;
import com.intellij.database.connection.throwable.info.WarningInfo;
import com.intellij.database.datagrid.DataAuditor;
import com.intellij.database.datagrid.DataProducer;
import com.intellij.database.datagrid.DataRequest;
import com.intellij.database.datagrid.DataRequest.Context;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;

public class Auditor implements DataAuditor {
    private final Set<BiConsumer<MessageType, String>> consumers = new HashSet<>();

    public enum MessageType {
        PRINT, WARN, ERROR
    }

    public void addWarningConsumer(BiConsumer<MessageType, String> consumer) {
        consumers.add(consumer);
    }

    private void produce(MessageType type, String s) {
        if (!consumers.isEmpty()) {
            consumers.forEach(c -> c.accept(type, s));
        }
    }

    @Override
    public void print(@NotNull Context context, @Nullable String s) {
        produce(MessageType.PRINT, s);
    }

    @Override public void warn(@NotNull Context context, @NotNull WarningInfo warningInfo) {
        produce(MessageType.WARN, warningInfo.getMessage());
    }

    @Override public void error(@NotNull Context context, @NotNull ErrorInfo errorInfo) {
        produce(MessageType.ERROR, errorInfo.getMessage());
    }

    @Override
    public void beforeStatement(@NotNull Context context) {
        // Not needed
    }

    @Override
    public void afterStatement(@NotNull Context context) {
        // Not needed
    }

    @Override
    public void updateCountReceived(@NotNull Context context, int i) {
        // Not needed
    }

    @Override
    public void fetchStarted(@NotNull Context context, int i) {
        // Not needed
    }

    @Override
    public void fetchFinished(@NotNull Context context, int i, int i1) {
        // Not needed
    }

    @Override
    public void requestStarted(@NotNull Context context) {
        // Not needed
    }

    @Override
    public void requestFinished(@NotNull Context context) {
        // Not needed
    }

    @Override
    public void txCompleted(@NotNull Context context, @NotNull TxEvent txEvent) {
        // Not needed
    }

    @Override
    public void jobSubmitted(@NotNull DataRequest dataRequest, @NotNull DataProducer dataProducer) {
        // Not needed
    }

    @Override
    public void jobFinished(@NotNull DataRequest dataRequest, @NotNull DataProducer dataProducer) {
        // Not needed
    }
}
