package dev.niels.sqlbackuprestore.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLHelperTest {
    @Test
    void rendersThePathIntoTheQuery() {
        var query = SQLHelper.pathChildrenQuery("C:\\Backups");

        assertTrue(query.contains("select @Path = N'C:\\Backups'"), query);
    }

    @Test
    void escapesQuotesInThePath() {
        var query = SQLHelper.pathChildrenQuery("C:\\O'Brien");

        assertTrue(query.contains("N'C:\\O''Brien'"), query);
    }

    @Test
    void keepsTheWindowsFallbackSeparatorsIntact() {
        // The query is a text block now, where a single backslash would be an illegal escape rather than a separator.
        var query = SQLHelper.pathChildrenQuery("/var/opt/mssql");

        assertTrue(query.contains("if (right(@Path, 1) = '\\')"), query);
        assertTrue(query.contains("@Path + '\\' + Name"), query);
    }
}
