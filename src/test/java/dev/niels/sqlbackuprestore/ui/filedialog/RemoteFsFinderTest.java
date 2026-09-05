package dev.niels.sqlbackuprestore.ui.filedialog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemoteFsFinderTest {
    @Test
    void separatorFollowsTheServerNotTheClient() {
        assertEquals("/", new RemoteFsFinder(List.of(FakeRemoteFile.root("/"))).getSeparator());
        assertEquals("\\", new RemoteFsFinder(List.of(FakeRemoteFile.root("C:\\"), FakeRemoteFile.root("D:\\"))).getSeparator());
    }

    @Test
    void normalizeAcceptsWhatTheSaveDialogPutsInThePathField() {
        var finder = new RemoteFsFinder(List.of(FakeRemoteFile.root("C:\\")));
        assertEquals("C:\\temp", finder.normalize("mssqldb://C:\\temp"));
    }
}
