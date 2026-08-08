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
    void namesEachFileAfterTheDatabaseItIsRestoredAs() {
        var files = RestoreFile.assignDefaultTargets(
                RestoreFile.from(List.of(row("Northwind", "D"), row("Northwind_log", "L"))), "C:\\Data\\", "shop");

        assertEquals("C:\\Data\\shop.mdf", files.get(0).getRestoreAs());
        assertEquals("C:\\Data\\shop_log.ldf", files.get(1).getRestoreAs());
    }

    @Test
    void givesASecondDataFileItsOwnPath() {
        // Two data files both defaulting to "shop.mdf" would have the second MOVE overwrite the first, and the restore
        // would still report success.
        var files = RestoreFile.assignDefaultTargets(
                RestoreFile.from(List.of(row("a", "D"), row("b", "D"), row("c", "D"), row("a_log", "L"))), "C:\\Data\\", "shop");

        assertEquals(List.of("C:\\Data\\shop.mdf", "C:\\Data\\shop_1.mdf", "C:\\Data\\shop_2.mdf", "C:\\Data\\shop_log.ldf"),
                files.stream().map(RestoreFile::getRestoreAs).toList());
    }

    @Test
    void numbersDataAndLogFilesIndependently() {
        var files = RestoreFile.assignDefaultTargets(
                RestoreFile.from(List.of(row("a", "D"), row("a_log", "L"), row("b_log", "L"))), "C:\\Data\\", "shop");

        assertEquals(List.of("C:\\Data\\shop.mdf", "C:\\Data\\shop_log.ldf", "C:\\Data\\shop_1_log.ldf"),
                files.stream().map(RestoreFile::getRestoreAs).toList());
    }

    @Test
    void followsTheServersOwnSeparator() {
        // The server may be on Linux even when the IDE is not.
        var files = RestoreFile.assignDefaultTargets(RestoreFile.from(List.of(row("a", "D"))), "/var/opt/mssql/data", "shop");

        assertEquals("/var/opt/mssql/data/shop.mdf", files.getFirst().getRestoreAs());
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
