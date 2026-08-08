package dev.niels.sqlbackuprestore.query;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreFileTest {
    private static Map<String, Object> row(String logicalName, String type) {
        var row = new HashMap<String, Object>();
        row.put("LogicalName", logicalName);
        row.put("PhysicalName", "D:\\data\\" + logicalName + ".mdf");
        row.put("Type", type);
        return row;
    }

    @Test
    void readsTheColumnsItNeeds() {
        var file = RestoreFile.from(List.of(row("Northwind", "D"))).getFirst();

        assertEquals("Northwind", file.getLogicalName());
        assertEquals("D:\\data\\Northwind.mdf", file.getPhysicalName());
        assertEquals("D", file.getType());
    }

    @Test
    void recognisesLogFiles() {
        assertTrue(RestoreFile.from(List.of(row("Northwind_log", "L"))).getFirst().isLog());
        assertFalse(RestoreFile.from(List.of(row("Northwind", "D"))).getFirst().isLog());
    }

    @Test
    void toleratesAMissingColumn() {
        // FILELISTONLY has grown columns over the years and the driver hands back whatever the server sent; a missing
        // one should not put the string "null" into a RESTORE statement.
        var file = RestoreFile.from(List.of(new HashMap<String, Object>())).getFirst();

        assertEquals("", file.getLogicalName());
        assertEquals("", file.getType());
        assertFalse(file.isLog());
    }
}
