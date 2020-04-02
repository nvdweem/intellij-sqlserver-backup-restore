package dev.niels.sqlbackuprestore;

import com.intellij.database.dataSource.connection.DatabaseDepartment;

import javax.swing.Icon;

public class Constants {
    public static final String NOTIFICATION_GROUP = "SQL Backup and Restore";
    public static final String ERROR = "Error occurred";

    private Constants() {
    }

    public static final DatabaseDepartment databaseDepartment = new DatabaseDepartment() {
        @Override
        public String getDepartmentName() {
            return "MSSQL Department name";
        }

        @Override
        public String getCommonName() {
            return "MSSQL Common name";
        }

        @Override
        public Icon getIcon() {
            return null;
        }

        @Override
        public boolean isInternal() {
            return false;
        }

        @Override
        public boolean isService() {
            return false;
        }
    };
}
