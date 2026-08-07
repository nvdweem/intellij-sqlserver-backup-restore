package dev.niels.sqlbackuprestore.query;

import dev.niels.sqlbackuprestore.ui.filedialog.RemoteFile;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Data
// Lets the header-matching rules be exercised without a live server behind them.
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class RemoteFileWithMeta {
    private final RemoteFile file;
    private final BackupType type;
    /**
     * Log sequence numbers are {@code numeric(25,0)}, which is far wider than a {@code long}. They used to be narrowed
     * with {@link Number#longValue()}, and the silent overflow made unrelated backups look like they belonged together.
     */
    private final BigDecimal firstLSN;
    private final BigDecimal databaseBackupLSN;
    private final String backupFinishDate;
    private final String machineName;

    public static Function<RemoteFile, RemoteFileWithMeta> factory(Client c) {
        return file -> new RemoteFileWithMeta(c, file);
    }

    public RemoteFileWithMeta(Client c, RemoteFile file) {
        var result = c.withRows("RESTORE HEADERONLY FROM DISK = N'" + Sql.literal(file.getPath()) + "' WITH NOUNLOAD;", (cs, rs) -> {
        }).join();

        if (result.isEmpty()) {
            throw new IllegalArgumentException(file.getPath() + " does not look like a SQL Server backup file");
        }
        Map<String, Object> header = result.getFirst();

        this.file = file;
        this.type = BackupType.from(header.get("BackupType") instanceof Number nr ? nr.intValue() : -1);
        this.firstLSN = toDecimal(header.get("FirstLSN"));
        this.databaseBackupLSN = toDecimal(header.get("DatabaseBackupLSN"));
        this.backupFinishDate = Objects.toString(header.get("BackupFinishDate"), "");
        this.machineName = Objects.toString(header.get("MachineName"), "");
    }

    public boolean isFull() {
        return type == BackupType.FULL;
    }

    /**
     * A differential belongs to the full backup whose FirstLSN it was taken against.
     */
    public boolean isPartialOf(RemoteFileWithMeta other) {
        return type == BackupType.PARTIAL && other.type == BackupType.FULL
                && databaseBackupLSN != null && other.firstLSN != null
                && other.firstLSN.compareTo(databaseBackupLSN) == 0;
    }

    private static BigDecimal toDecimal(Object o) {
        return switch (o) {
            case BigDecimal bd -> bd;
            case Number nr -> new BigDecimal(nr.toString());
            case String s -> {
                try {
                    yield new BigDecimal(s);
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            case null, default -> null;
        };
    }

    public enum BackupType {
        FULL, PARTIAL, UNSUPPORTED;

        public static BackupType from(int value) {
            return switch (value) {
                case 1 -> FULL;
                case 5 -> PARTIAL;
                default -> UNSUPPORTED;
            };
        }
    }
}
