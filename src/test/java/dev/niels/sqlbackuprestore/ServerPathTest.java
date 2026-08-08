package dev.niels.sqlbackuprestore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerPathTest {
    @Test
    void joinsWindowsPaths() {
        assertEquals("C:\\Backups\\Northwind.bak", ServerPath.join("C:\\Backups", "Northwind.bak"));
    }

    @Test
    void followsTheServerOntoLinux() {
        assertEquals("/var/opt/mssql/data/Northwind.bak", ServerPath.join("/var/opt/mssql/data", "Northwind.bak"));
    }

    @Test
    void doesNotDoubleTheSeparator() {
        assertEquals("C:\\Backups\\Northwind.bak", ServerPath.join("C:\\Backups\\", "Northwind.bak"));
        assertEquals("/var/data/Northwind.bak", ServerPath.join("/var/data/", "Northwind.bak"));
    }

    @Test
    void treatsADriveRootAsWindows() {
        assertEquals("C:\\Northwind.bak", ServerPath.join("C:\\", "Northwind.bak"));
    }
}
