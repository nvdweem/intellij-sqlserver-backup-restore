package dev.niels.sqlbackuprestore.it;

import dev.niels.sqlbackuprestore.ServerPath;
import dev.niels.sqlbackuprestore.query.Sql;
import dev.niels.sqlbackuprestore.query.Statements;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Databases and backup files for one test, cleaned up afterwards whether it passed or not. Names are prefixed so that
 * anything a crashed run leaves behind is obvious in the server's database list.
 */
@RequiresSqlServer
abstract class SqlServerTestBase {
    protected static final String PREFIX = "ij_it_";

    private final Set<String> databases = new LinkedHashSet<>();
    private final Set<String> backupFiles = new LinkedHashSet<>();
    protected Connection connection;
    private String backupDirectory;

    @BeforeEach
    void openConnection() throws SQLException {
        connection = SqlServer.connect();
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try {
            // Back to master first: a database cannot be dropped while this session is the one sitting in it, and both
            // sp_spaceused and the post-restore script leave the connection pointed at whatever they were run against.
            SqlServer.execute(connection, "USE [master];");
            databases.forEach(this::dropQuietly);
            backupFiles.forEach(SqlServerTestBase::deleteQuietly);
        } finally {
            connection.close();
        }
    }

    /** The directory the server itself reports, which is the one its service account is certain to be able to write. */
    protected String backupDirectory() throws SQLException {
        if (backupDirectory == null) {
            backupDirectory = Objects.toString(SqlServer.single(connection, Statements.defaultBackupDirectory(), "directory"), "");
        }
        return backupDirectory;
    }

    /** A path in the server's backup directory, registered for deletion when the test ends. */
    protected String backupPath(String fileName) throws SQLException {
        var path = ServerPath.join(backupDirectory(), fileName);
        backupFiles.add(path);
        return path;
    }

    /**
     * A database with one table holding {@code rows} numbered rows, so that a restore can be checked by counting them
     * rather than by trusting that the statement returned without complaining.
     */
    protected String createDatabase(String name, int rows) throws SQLException {
        databases.add(name);
        dropQuietly(name);
        SqlServer.execute(connection, "CREATE DATABASE " + Sql.quoted(name) + ";");
        SqlServer.execute(connection, "CREATE TABLE " + Sql.quoted(name) + ".dbo.payload (id int NOT NULL PRIMARY KEY, note nvarchar(100) NULL);");
        insertRows(name, 1, rows);
        return name;
    }

    protected void insertRows(String database, int from, int count) throws SQLException {
        if (count <= 0) {
            return;
        }
        SqlServer.execute(connection, """
                INSERT INTO %s.dbo.payload (id, note)
                SELECT TOP (%d) %d + ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) - 1, 'row'
                FROM sys.all_objects;""".formatted(Sql.quoted(database), count, from));
    }

    protected int rowCount(String database) throws SQLException {
        return ((Number) SqlServer.single(connection, "SELECT COUNT(*) AS c FROM " + Sql.quoted(database) + ".dbo.payload", "c")).intValue();
    }

    /** Registers a name for cleanup without creating it, for the databases a restore brings into existence. */
    protected String willCreate(String name) {
        databases.add(name);
        return name;
    }

    protected List<Map<String, Object>> query(String sql) throws SQLException {
        return SqlServer.query(connection, sql);
    }

    protected void execute(String sql) throws SQLException {
        SqlServer.execute(connection, sql);
    }

    protected List<String> databaseFiles(String database) throws SQLException {
        var rows = query("SELECT physical_name FROM sys.master_files WHERE database_id = DB_ID(N'" + Sql.literal(database) + "');");
        var paths = new ArrayList<String>();
        rows.forEach(row -> paths.add(Objects.toString(row.get("physical_name"), "")));
        return paths;
    }

    private void dropQuietly(String name) {
        // A database left mid-restore cannot be ALTERed, and one with sessions on it cannot be dropped, so both orders
        // are tried. Failure here must not mask the test's own result.
        for (var statement : List.of("DROP DATABASE IF EXISTS " + Sql.quoted(name) + ";",
                "ALTER DATABASE " + Sql.quoted(name) + " SET SINGLE_USER WITH ROLLBACK IMMEDIATE;",
                "DROP DATABASE IF EXISTS " + Sql.quoted(name) + ";")) {
            try {
                SqlServer.execute(connection, statement);
            } catch (SQLException | RuntimeException e) {
                // Next attempt.
            }
        }
    }

    /**
     * Best effort, and only meaningful when the server shares a filesystem with the test - which is the case these
     * tests are written for. A leftover file on a remote server is untidy, not a failure.
     */
    private static void deleteQuietly(String path) {
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (IOException | RuntimeException e) {
            // Not ours to delete.
        }
    }
}
