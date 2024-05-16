package dev.niels.sqlbackuprestore.query;

import dev.niels.sqlbackuprestore.ui.filedialog.RemoteFile;
import lombok.Data;

import java.util.Objects;
import java.util.function.Function;

@Data
public class RemoteFileWithMeta {
    private final RemoteFile file;
    private final BackupType type;
    private final long firstLSN;
    private final long databaseBackupLSN;
    private final String backupFinishDate;
    private final String machineName;

    public static Function<RemoteFile, RemoteFileWithMeta> factory(Client c) {
        return file -> new RemoteFileWithMeta(c, file);
    }

    public RemoteFileWithMeta(Client c, RemoteFile file) {
        var result = c.withRows("RESTORE HEADERONLY FROM DISK = N'" + file.getPath() + "' WITH NOUNLOAD;", (cs, rs) -> {
        }).join();

        this.file = file;
        this.type = BackupType.from(toNumber(result.get(0).get("BackupType"), Number::intValue));
        this.firstLSN = toNumber(result.get(0).get("FirstLSN"), Number::longValue);
        this.databaseBackupLSN = toNumber(result.get(0).get("DatabaseBackupLSN"), Number::longValue);
        this.backupFinishDate = Objects.toString(result.get(0).get("BackupFinishDate"), "");
        this.machineName = Objects.toString(result.get(0).get("MachineName"), "");
    }

    public boolean isFull() {
        return type == BackupType.FULL;
    }

    public boolean isPartialOf(RemoteFileWithMeta other) {
        return type == BackupType.PARTIAL && other.type == BackupType.FULL && other.firstLSN == databaseBackupLSN;
    }

    private <T extends Number> T toNumber(Object o, Function<Number, T> getter) {
        return getter.apply(o instanceof Number nr ? nr : -1);
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
