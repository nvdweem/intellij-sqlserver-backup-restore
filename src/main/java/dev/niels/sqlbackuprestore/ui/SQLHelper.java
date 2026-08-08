package dev.niels.sqlbackuprestore.ui;

import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.query.Statements;
import lombok.SneakyThrows;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the queries behind the remote file picker. The queries themselves live in {@link Statements}, with the rest of
 * the SQL this plugin sends.
 */
public final class SQLHelper {
    /** A registry read or a directory listing on a busy server can take a moment; two seconds was optimistic. */
    private static final int TIMEOUT_SECONDS = 10;

    private SQLHelper() {
    }

    @SneakyThrows
    public static String getDefaultBackupDirectory(Client connection) {
        return connection.getSingle(Statements.defaultBackupDirectory(), "directory", String.class).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @SneakyThrows
    public static List<Map<String, Object>> getDrives(Client connection) {
        return connection.getResult(Statements.drives()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @SneakyThrows
    public static List<Map<String, Object>> getSQLPathChildren(Client connection, String path) {
        return connection.getResult(Statements.pathChildren(path)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
