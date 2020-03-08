package dev.niels.sqlbackuprestore.query;

import com.intellij.database.datagrid.DataRequest;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public class Query extends DataRequest.RawQueryRequest {
    private final Consumer<Pair<List<Column>, List<Row>>> consumer;
    private List<Column> columns;
    private List<Map<String, Object>> result = new ArrayList<>();
    @Getter
    private CompletableFuture<List<Map<String, Object>>> future = new CompletableFuture<>();

    protected Query(Client c, OwnerEx owner, String query, Consumer<Pair<List<Column>, List<Row>>> consumer) {
        super(owner, query, DataRequest.newConstraints(0, 5000, 0, 0));
        this.consumer = consumer;

        c.open();
        getPromise().onProcessed(x -> {
            future.complete(result);
            c.close();
        });
    }

    @Override
    public void updateColumns(@NotNull Context context, @NotNull Column[] columns) {
        this.columns = List.of(columns);
    }

    @Override
    public void addRows(@NotNull Context context, List<Row> list) {
        if (columns == null) {
            log.error("No columns set yet, ignoring rows");
            return;
        }

        if (consumer != null) {
            consumer.accept(Pair.of(columns, list));
        }
        result.addAll(list.stream().map(r -> columns.stream().collect(HashMap<String, Object>::new, (m, v) -> m.put(v.name, v.getValue(r)), HashMap::putAll)).collect(Collectors.toList()));
    }

    @Override
    public void afterLastRowAdded(@NotNull Context context, int total) {
        super.afterLastRowAdded(context, total);
        future.complete(result);
    }
}
