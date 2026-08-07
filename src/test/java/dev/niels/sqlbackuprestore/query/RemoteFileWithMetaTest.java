package dev.niels.sqlbackuprestore.query;

import dev.niels.sqlbackuprestore.query.RemoteFileWithMeta.BackupType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteFileWithMetaTest {
    /** Realistic log sequence numbers: numeric(25,0), far beyond what a long can hold. */
    private static final BigDecimal LSN_A = new BigDecimal("34000000038300043");
    private static final BigDecimal LSN_B = new BigDecimal("34000000038300044");
    /** Two distinct LSNs that differ by exactly 2^64, so narrowing them to a long makes them indistinguishable. */
    private static final BigDecimal HUGE_A = new BigDecimal("9223372036854775809000000");
    private static final BigDecimal HUGE_B = new BigDecimal("9223390483598849518551616");

    private static RemoteFileWithMeta backup(BackupType type, BigDecimal firstLSN, BigDecimal databaseBackupLSN) {
        return new RemoteFileWithMeta(null, type, firstLSN, databaseBackupLSN, "", "");
    }

    @Test
    @DisplayName("BackupType maps the documented header values")
    void mapsBackupTypes() {
        assertEquals(BackupType.FULL, BackupType.from(1));
        assertEquals(BackupType.PARTIAL, BackupType.from(5));
        assertEquals(BackupType.UNSUPPORTED, BackupType.from(2));
        assertEquals(BackupType.UNSUPPORTED, BackupType.from(-1));
    }

    @Test
    @DisplayName("a differential belongs to the full backup it was taken against")
    void matchesDifferentialToItsFull() {
        var full = backup(BackupType.FULL, LSN_A, BigDecimal.ZERO);
        var differential = backup(BackupType.PARTIAL, LSN_B, LSN_A);

        assertTrue(differential.isPartialOf(full));
        assertTrue(full.isFull());
        assertFalse(differential.isFull());
    }

    @Test
    @DisplayName("a differential taken against a different full backup does not match")
    void rejectsUnrelatedFull() {
        var otherFull = backup(BackupType.FULL, LSN_B, BigDecimal.ZERO);
        var differential = backup(BackupType.PARTIAL, LSN_B, LSN_A);

        assertFalse(differential.isPartialOf(otherFull));
    }

    @Test
    @DisplayName("LSNs wider than a long stay distinguishable")
    void doesNotNarrowLargeLsns() {
        // The regression this guards: these two are different numbers that collapse onto the same long, so a
        // differential taken against HUGE_B used to be offered as belonging to the full backup at HUGE_A.
        assertEquals(HUGE_A.longValue(), HUGE_B.longValue());

        var full = backup(BackupType.FULL, HUGE_A, BigDecimal.ZERO);
        assertFalse(backup(BackupType.PARTIAL, HUGE_B, HUGE_B).isPartialOf(full));
        assertTrue(backup(BackupType.PARTIAL, HUGE_B, HUGE_A).isPartialOf(full));
    }

    @Test
    @DisplayName("a full backup is never the differential of another")
    void fullIsNeverAPartial() {
        var full = backup(BackupType.FULL, LSN_A, BigDecimal.ZERO);
        var otherFull = backup(BackupType.FULL, LSN_B, LSN_A);

        assertFalse(otherFull.isPartialOf(full));
    }

    @Test
    @DisplayName("a header without usable LSNs doesn't match anything")
    void missingLsnsNeverMatch() {
        var full = backup(BackupType.FULL, null, BigDecimal.ZERO);
        var differential = backup(BackupType.PARTIAL, LSN_B, null);

        assertFalse(differential.isPartialOf(full));
        assertFalse(backup(BackupType.PARTIAL, LSN_B, LSN_A).isPartialOf(full));
    }
}
