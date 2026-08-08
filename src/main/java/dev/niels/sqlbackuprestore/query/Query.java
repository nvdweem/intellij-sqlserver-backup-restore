package dev.niels.sqlbackuprestore.query;

import com.intellij.database.datagrid.DataRequest;
import com.intellij.database.datagrid.DataRequest.RawQueryRequest;
import com.intellij.database.datagrid.GridColumn;
import com.intellij.database.datagrid.GridDataRequest;
import com.intellij.database.datagrid.GridRow;
import dev.niels.sqlbackuprestore.query.Auditor.MessageType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

@Slf4j
public class Query extends RawQueryRequest {
    private final BiConsumer<List<GridColumn>, List<GridRow>> consumer;
    private final List<Map<String, Object>> result = new ArrayList<>();
    private List<GridColumn> columns;
    @Getter
    private final CompletableFuture<List<Map<String, Object>>> future = new CompletableFuture<>();

    protected Query(Client c, Owner owner, String query, BiConsumer<List<GridColumn>, List<GridRow>> consumer) {
        super(owner, query, DataRequest.newConstraints(0, 5000, 0, 0, 0));
        this.consumer = consumer;

        // A statement that fails still gets "processed", so without watching for the error a failed BACKUP/RESTORE
        // would complete this future successfully with zero rows and be reported to the user as a success.
        var failure = new AtomicReference<String>();
        BiConsumer<MessageType, String> errorWatcher = (type, message) -> {
            if (type == MessageType.ERROR) {
                failure.compareAndSet(null, message);
            }
        };
        c.addWarningConsumer(errorWatcher);

        // Held for as long as the statement runs, so the session cannot be disconnected out from under it.
        c.acquire();
        getPromise().onProcessed(x -> {
            c.removeWarningConsumer(errorWatcher);
            var error = failure.get();
            if (error != null) {
                future.completeExceptionally(new QueryException(error, query));
            } else {
                future.complete(result);
            }
            c.release();
        });
    }

    @Override public void updateColumns(@NotNull GridDataRequest.Context context, GridColumn @NotNull [] columns) {
        this.columns = List.of(columns);
    }

    @Override public void addRows(@NotNull GridDataRequest.Context context, @NotNull List<? extends GridRow> list) {
        if (columns == null) {
            log.error("No columns set yet, ignoring rows");
            return;
        }

        if (consumer != null) {
            consumer.accept(columns, StreamEx.of(list).select(GridRow.class).toImmutableList());
        }

        for (var row : list) {
            // A plain HashMap rather than Collectors.toMap: a null in any column would make that throw.
            Map<String, Object> values = new HashMap<>();
            for (var column : columns) {
                values.put(column.getName(), column.getValue(row));
            }
            result.add(values);
        }
    }

    @Override public void afterLastRowAdded(@NotNull GridDataRequest.Context context, int total) {
        super.afterLastRowAdded(context, total);
        // Completing as soon as the rows are in keeps result-producing queries as responsive as they were; statements
        // that produce no rows at all (BACKUP, RESTORE) are completed by onProcessed, which is where failures land.
        future.complete(result);
    }

    /**
     * Carries the server's message so the notification the user sees says what actually went wrong.
     */
    public static class QueryException extends RuntimeException {
        private final transient String query;

        public QueryException(String message, String query) {
            super(message);
            this.query = query;
        }

        public String getQuery() {
            return query;
        }
    }
}
