package dev.niels.sqlbackuprestore.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlTest {
    @Test
    @DisplayName("a value without quotes is left alone")
    void leavesPlainValuesAlone() {
        assertEquals("backups", Sql.literal("backups"));
        assertEquals("C:\\backups\\db.bak", Sql.literal("C:\\backups\\db.bak"));
    }

    @Test
    @DisplayName("literal doubles every quote so the value can't end the string it sits in")
    void escapesLiterals() {
        assertEquals("o''brien", Sql.literal("o'brien"));
        assertEquals("''''", Sql.literal("''"));
        assertEquals("C:\\it''s here\\db.bak", Sql.literal("C:\\it's here\\db.bak"));
    }

    @Test
    @DisplayName("a path that closes the literal and appends a statement stays one single value")
    void neutralisesInjectedStatement() {
        assertEquals("C:\\db.bak''; DROP DATABASE [x]; --", Sql.literal("C:\\db.bak'; DROP DATABASE [x]; --"));
    }

    @Test
    @DisplayName("identifier doubles every closing bracket")
    void escapesIdentifiers() {
        assertEquals("master", Sql.identifier("master"));
        assertEquals("my]]db", Sql.identifier("my]db"));
        assertEquals("]]]]", Sql.identifier("]]"));
    }

    @Test
    @DisplayName("quoted wraps the escaped identifier in brackets")
    void quotesIdentifiers() {
        assertEquals("[master]", Sql.quoted("master"));
        assertEquals("[my]]db]", Sql.quoted("my]db"));
        // Without escaping this would close the identifier and smuggle in a second one.
        assertEquals("[a]] WITH x, [b]", Sql.quoted("a] WITH x, [b"));
    }
}
