package dev.niels.sqlbackuprestore.query;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.apache.commons.lang3.Strings;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One file inside a backup, as reported by {@code RESTORE FILELISTONLY}, together with where the restore is going to
 * put it. The rows used to be passed around as the raw {@code Map<String, Object>} the driver produced, which meant
 * every use site spelled out a column name and the plugin's own "RestoreAs" was stored back into the server's row.
 */
@Getter
@RequiredArgsConstructor
public class RestoreFile {
    private final String logicalName;
    private final String physicalName;
    /** {@code D} for a data file, {@code L} for a log file; there are more, but only the log needs telling apart. */
    private final String type;
    /** The path this file will be restored to. Defaulted from the server's data directory, editable by the user. */
    @Setter
    private String restoreAs;

    public static @NotNull List<RestoreFile> from(@NotNull List<Map<String, Object>> rows) {
        return rows.stream().map(RestoreFile::from).toList();
    }

    private static RestoreFile from(Map<String, Object> row) {
        return new RestoreFile(column(row, "LogicalName"), column(row, "PhysicalName"), column(row, "Type"));
    }

    public boolean isLog() {
        return Strings.CI.equals(type, "L");
    }

    private static String column(Map<String, Object> row, String name) {
        return Objects.toString(row.get(name), "");
    }
}
