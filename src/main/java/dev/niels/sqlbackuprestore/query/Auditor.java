package dev.niels.sqlbackuprestore.query;

import com.intellij.database.connection.throwable.info.ErrorInfo;
import com.intellij.database.connection.throwable.info.WarningInfo;
import com.intellij.database.datagrid.DataAuditor;
import com.intellij.database.datagrid.DataProducer;
import com.intellij.database.datagrid.DataRequest;
import com.intellij.database.datagrid.DataRequest.Context;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

@Slf4j
public class Auditor implements DataAuditor {
    // Messages arrive on the database thread while consumers are (un)registered from the EDT or a task thread.
    private final List<BiConsumer<MessageType, String>> consumers = new CopyOnWriteArrayList<>();

    public enum MessageType {
        PRINT, WARN, ERROR
    }

    public void addWarningConsumer(BiConsumer<MessageType, String> consumer) {
        consumers.add(consumer);
    }

    /**
     * Consumers outlive the operation that registered them unless they're removed, which both leaks them and lets a
     * finished task keep reacting to messages from the next one.
     */
    public void removeWarningConsumer(BiConsumer<MessageType, String> consumer) {
        consumers.remove(consumer);
    }

    /**
     * Hands the message to every consumer, and keeps going when one of them fails.
     * <p>
     * They share a connection and know nothing about each other, so the order they registered in - which nothing
     * coordinates - decided who heard about a message and who did not. Letting one failure through here would in
     * particular cost {@link Query} its error watcher, and a failed BACKUP would be reported as a success. This runs on
     * the database thread mid-statement, where an escaping exception is nobody's to catch either.
     */
    void produce(MessageType type, String s) {
        var message = s == null ? "" : s;
        for (var consumer : consumers) {
            try {
                consumer.accept(type, message);
            } catch (Exception e) {
                log.warn("A {} message consumer failed", type, e);
            }
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
