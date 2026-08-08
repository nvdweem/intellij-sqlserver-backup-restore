package dev.niels.sqlbackuprestore.it;

import dev.niels.sqlbackuprestore.query.Statements;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The queries behind the remote file picker, against a real server.
 * <p>
 * These are the ones that browse the server's filesystem, so they are also the ones most likely to be quietly broken by
 * permissions or by a server that does not have the {@code sys.dm_os_*} views. A picker that comes up empty looks the
 * same as a directory that is empty.
 */
class FilePickerIT extends SqlServerTestBase {
    private Map<String, Object> entryNamed(List<Map<String, Object>> rows, String name) {
        return rows.stream()
                .filter(row -> name.equalsIgnoreCase(Objects.toString(row.get("Name"), "")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " not found among " + rows.stream().map(r -> r.get("Name")).toList()));
    }

    @Test
    void reportsADefaultBackupDirectory() throws SQLException {
        var directory = backupDirectory();

        // Read out of the registry via xp_instance_regread, which needs rights the picker's user may not have; when it
        // fails the picker has nowhere to open at.
        assertFalse(directory.isBlank(), "no default backup directory");
    }

    @Test
    void listsTheServersFixedDrives() throws SQLException {
        var drives = query(Statements.drives());

        assertFalse(drives.isEmpty(), "no drives reported, so the picker would have no roots");
        drives.forEach(drive -> {
            assertFalse(Objects.toString(drive.get("Name"), "").isBlank(), "a drive with no name: " + drive);
            assertNotNull(drive.get("Size"), "a drive with no size: " + drive);
        });
    }

    @Test
    void listsAFileWithItsSizeAndModificationDate() throws SQLException {
        var database = createDatabase(PREFIX + "listing", 50);
        var fileName = PREFIX + "listing.bak";
        execute(Statements.backup(database, backupPath(fileName), false));

        var entry = entryNamed(query(Statements.pathChildren(backupDirectory())), fileName);

        assertEquals(1, ((Number) entry.get("IsFile")).intValue(), "a backup file was reported as a directory");
        // Both columns exist only for the picker's size/date display; without them it silently shows nothing.
        assertTrue(((Number) entry.get("SizeInBytes")).longValue() > 0, "no size: " + entry);
        assertNotNull(entry.get("LastWriteTime"), "no modification date: " + entry);
        assertTrue(entry.get("LastWriteTime") instanceof Timestamp,
                "the picker converts this to a timestamp: " + entry.get("LastWriteTime").getClass());
        assertTrue(Objects.toString(entry.get("FullName"), "").endsWith(fileName), "unusable full path: " + entry);
    }

    @Test
    void tellsDirectoriesApartFromFilesAndListsThemFirst() throws SQLException {
        // The instance's own root has both: a Backup/ directory next to whatever files live there.
        var parent = parentOf(backupDirectory());
        var rows = query(Statements.pathChildren(parent));

        assertFalse(rows.isEmpty(), "nothing listed under " + parent);
        var directories = rows.stream().filter(row -> ((Number) row.get("IsFile")).intValue() == 0).toList();
        assertFalse(directories.isEmpty(), "no directories found under " + parent);

        // ORDER BY IsFile ASC: the picker relies on this to group folders above files.
        var lastDirectory = rows.stream().reduce((first, second) -> second).orElseThrow();
        assertEquals(0, ((Number) rows.getFirst().get("IsFile")).intValue(), "the first entry should be a directory");
        assertTrue(((Number) lastDirectory.get("IsFile")).intValue() >= 0);
    }

    @Test
    void reportsNothingRatherThanFailingForADirectoryThatIsNotThere() throws SQLException {
        // getChildren() runs from the tree's paint, so this must not be how the picker learns about a bad path.
        var rows = query(Statements.pathChildren(backupDirectory() + "\\definitely-not-here-" + PREFIX));

        assertTrue(rows.isEmpty(), "expected an empty listing but got " + rows);
    }

    @Test
    void listsADirectoryWhoseNameContainsAQuote() throws SQLException {
        // Not created here - just checking the escaping survives the round trip rather than becoming a syntax error.
        var rows = query(Statements.pathChildren(backupDirectory() + "\\O'Brien"));

        assertTrue(rows.isEmpty(), "unexpected contents: " + rows);
    }

    private static String parentOf(String directory) {
        var trimmed = directory.endsWith("\\") || directory.endsWith("/") ? directory.substring(0, directory.length() - 1) : directory;
        var cut = Math.max(trimmed.lastIndexOf('\\'), trimmed.lastIndexOf('/'));
        return cut <= 0 ? trimmed : trimmed.substring(0, cut);
    }
}
