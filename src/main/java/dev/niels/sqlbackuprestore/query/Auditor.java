package dev.niels.sqlbackuprestore.query;

import com.intellij.database.datagrid.DataAuditor;
import com.intellij.database.datagrid.DataProducer;
import com.intellij.database.datagrid.DataRequest;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public class Auditor implements DataAuditor {
    private final Set<Consumer<Pair<MessageType, String>>> consumers = new HashSet<>();

    public enum MessageType {
        PRINT, WARN, ERROR
    }

    public void addWarningConsumer(Consumer<Pair<MessageType, String>> consumer) {
        consumers.add(consumer);
    }

    private void produce(MessageType type, String s) {
        if (!consumers.isEmpty()) {
            var msg = Pair.of(type, s);
            consumers.forEach(c -> c.accept(msg));
        }
    }

    @Override
    public void print(@NotNull DataRequest.Context context, @Nullable String s) {
        produce(MessageType.PRINT, s);
    }

    @Override
    public void warn(@NotNull DataRequest.Context context, @Nullable String s) {
        produce(MessageType.WARN, s);
    }

    @Override
    public void error(@NotNull DataRequest.Context context, @Nullable String s, @Nullable Throwable throwable) {
        produce(MessageType.ERROR, s);
    }

    @Override
    public void beforeStatement(@NotNull DataRequest.Context context) {
        // Not needed
    }

    @Override
    public void afterStatement(@NotNull DataRequest.Context context) {
        // Not needed
    }

    @Override
    public void updateCountReceived(@NotNull DataRequest.Context context, int i) {
        // Not needed
    }

    @Override
    public void fetchStarted(@NotNull DataRequest.Context context, int i) {
        // Not needed
    }

    @Override
    public void fetchFinished(@NotNull DataRequest.Context context, int i, int i1) {
        // Not needed
    }

    @Override
    public void requestStarted(@NotNull DataRequest.Context context) {
        // Not needed
    }

    @Override
    public void requestFinished(@NotNull DataRequest.Context context) {
        // Not needed
    }

    @Override
    public void txCompleted(@NotNull DataRequest.Context context, @NotNull TxEvent txEvent) {
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
