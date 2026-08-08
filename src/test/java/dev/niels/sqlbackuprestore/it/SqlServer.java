package dev.niels.sqlbackuprestore.it;

import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The server the integration tests talk to.
 * <p>
 * Deliberately plain JDBC rather than the plugin's own {@link dev.niels.sqlbackuprestore.query.Client}: that one needs
 * a live IDE with the Database plugin, a configured data source and a driver the IDE downloads at runtime. What these
 * tests are for is the half that has nothing to do with the IDE - whether the SQL in
 * {@link dev.niels.sqlbackuprestore.query.Statements} is accepted and does what it claims. Running it over a driver of
 * our own is the same statements against the same server.
 * <p>
 * Configure with {@code -Dit.sqlserver.url} / {@code .user} / {@code .password}, or the {@code IT_SQLSERVER_*}
 * environment variables. The defaults point at a local default instance.
 */
final class SqlServer {
    static final String URL = setting("url", "jdbc:sqlserver://localhost:1433;encrypt=true;trustServerCertificate=true;");
    static final String USER = setting("user", "smile");
    static final String PASSWORD = setting("password", "smile");

    /** Probed once: every test class would otherwise pay the connection timeout again to find out the same thing. */
    private static Boolean available;
    private static String unavailableReason = "";

    private SqlServer() {
    }

    private static String setting(String name, String fallback) {
        var fromProperty = System.getProperty("it.sqlserver." + name);
        var fromEnvironment = System.getenv("IT_SQLSERVER_" + name.toUpperCase());
        return StringUtils.firstNonBlank(fromProperty, fromEnvironment, fallback);
    }

    static synchronized boolean isAvailable() {
        if (available == null) {
            try (var connection = connect()) {
                available = connection.isValid(5);
            } catch (SQLException | RuntimeException e) {
                available = false;
                unavailableReason = e.getMessage();
            }
        }
        return available;
    }

    static String unavailableReason() {
        return "No SQL Server at " + URL + " as " + USER + ": " + unavailableReason;
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    /**
     * Runs everything the statement produces, including the rows a {@code BACKUP} reports as warnings, so that a
     * failure part-way through a multi-statement batch still surfaces as an exception.
     */
    static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
            while (statement.getMoreResults() || statement.getUpdateCount() != -1) {
                // Draining the results is what makes a later batch's error show up here rather than nowhere.
            }
        }
    }

    /**
     * The rows of the first result set the statement produces, keyed by column label - the same shape the plugin's own
     * {@code Client} hands to its callers.
     */
    static List<Map<String, Object>> query(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            var hasResultSet = statement.execute(sql);
            while (!hasResultSet && statement.getUpdateCount() != -1) {
                hasResultSet = statement.getMoreResults();
            }
            if (!hasResultSet) {
                return List.of();
            }
            try (var rs = statement.getResultSet()) {
                return readAll(rs);
            }
        }
    }

    private static List<Map<String, Object>> readAll(ResultSet rs) throws SQLException {
        var meta = rs.getMetaData();
        var rows = new ArrayList<Map<String, Object>>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (var column = 1; column <= meta.getColumnCount(); column++) {
                row.put(meta.getColumnLabel(column), rs.getObject(column));
            }
            rows.add(row);
        }
        return rows;
    }

    static Object single(Connection connection, String sql, String column) throws SQLException {
        var rows = query(connection, sql);
        if (rows.isEmpty()) {
            throw new IllegalStateException("No rows for " + sql);
        }
        return rows.getFirst().get(column);
    }
}
