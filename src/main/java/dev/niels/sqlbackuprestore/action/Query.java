package dev.niels.sqlbackuprestore.action;

import com.intellij.database.datagrid.DataConsumer;
import com.intellij.database.datagrid.DataRequest;
import com.intellij.openapi.project.Project;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Getter
@ToString(of = {"query", "values"})
public class Query extends DataRequest.RawQueryRequest implements DataConsumer {
    private List<Column> columns;
    private List<Map<String, Object>> values = new ArrayList<>();
    private CompletableFuture<List<Map<String, Object>>> future = new CompletableFuture<>();

    public Query(@NotNull Project project, @NotNull String query) {
        super(DataRequest.newOwnerEx(project), query, DataRequest.newConstraints(0, 100_000, 0, 0));
        getPromise().onProcessed(x -> future.complete(values));
    }

    @Override
    public void updateColumns(@NotNull Context context, @NotNull Column[] columns) {
        this.columns = List.of(columns);
    }

    @Override
    public void addRows(@NotNull Context context, List<Row> list) {
        for (Row row : list) {
            Map<String, Object> r = new HashMap<>();
            for (Column column : columns) {
                r.put(column.getName(), column.getValue(row));
            }
            values.add(r);
        }
    }
}
