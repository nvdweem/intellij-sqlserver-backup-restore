package dev.niels.sqlbackuprestore.query;

import com.intellij.database.datagrid.DataRequest;
import com.intellij.database.datagrid.DataRequest.RawQueryRequest;
import com.intellij.database.datagrid.GridColumn;
import com.intellij.database.datagrid.GridDataRequest;
import com.intellij.database.datagrid.GridRow;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Slf4j
public class Query extends RawQueryRequest {
    private final BiConsumer<List<GridColumn>, List<GridRow>> consumer;
    private final List<Map<String, Object>> result = new ArrayList<>();
    private List<GridColumn> columns;
    @Getter
    private final CompletableFuture<List<Map<String, Object>>> future = new CompletableFuture<>();

    protected Query(Client c, Owner owner, String query, BiConsumer<List<GridColumn>, List<GridRow>> consumer) {
        super(owner, query, DataRequest.newConstraints(0, 5000, 0, 0));
        this.consumer = consumer;

        c.open();
        getPromise().onProcessed(x -> {
            future.complete(result);
            c.close();
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
        result.addAll(list.stream().map(r -> columns.stream().collect(HashMap<String, Object>::new, (m, v) -> m.put(v.getName(), v.getValue(r)), HashMap::putAll)).collect(Collectors.toList()));
    }

    @Override public void afterLastRowAdded(@NotNull GridDataRequest.Context context, int total) {
        super.afterLastRowAdded(context, total);
        future.complete(result);
    }
}
