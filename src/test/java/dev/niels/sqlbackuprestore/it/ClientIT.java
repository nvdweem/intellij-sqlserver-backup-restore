package dev.niels.sqlbackuprestore.it;

import com.intellij.database.dataSource.DatabaseDriver;
import com.intellij.database.dataSource.DatabaseDriverManager;
import com.intellij.database.dataSource.artifacts.DatabaseArtifactDefaultContext;
import com.intellij.database.dataSource.artifacts.DatabaseArtifactManager;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.FixturesKt;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.testFramework.junit5.fixture.TestFixtures;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.query.Statements;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The plugin's own {@link Client}, against a real server, through DataGrip's session machinery.
 * <p>
 * This is the layer everything else in the test suite goes around: the integration tests run the statements over plain
 * JDBC, where a failure simply throws. Whether a failure gets that far when it travels through {@code Query}, the
 * auditor and a {@code CompletableFuture} is a different question, and the only one that matters for what the user
 * eventually sees.
 * <p>
 * <b>Not connecting yet.</b> Where it has got to, so the next attempt does not start over:
 * <ol>
 *     <li>The IDE refuses with "Driver files are not downloaded": the bundled {@code sqlserver.ms} definition names
 *         artifacts it expects to fetch. Substituting a jar of our own via
 *         {@code setAdditionalClasspathElements} got past this check but then failed later inside
 *         {@code DatabaseConnectionEstablisher} with "Execution cancelled", so downloading the real thing - what the
 *         "Download missing driver files" link in the data source dialog does - looks like the right route.</li>
 *     <li>{@code DatabaseArtifactManager.downloadArtifact} needs something cancellable in the calling thread, hence
 *         the {@link com.intellij.openapi.progress.ProgressManager#runProcess} wrapper.</li>
 *     <li>It must download into the data source's own artifact context
 *         ({@code createContextForDataSource}), which is the context the connection then checks.</li>
 *     <li><b>Open:</b> it now reaches download.jetbrains.com and fetches the licence files, but the jars come back as
 *         "Downloaded 0 B" and the connection still reports "Driver files are not downloaded". Probably the artifact
 *         list needs refreshing first - {@code DatabaseArtifactManager.forceUpdate} or {@code checkForUpdates} - so
 *         that the versions resolve to something real.</li>
 * </ol>
 * Worth finishing: this is the one layer no other test reaches, and it is where the reported "backing several
 * databases up to a folder the server cannot write reports no error" bug lives.
 */
@TestApplication
@TestFixtures
@RequiresSqlServer
@Disabled("Does not connect yet - the class comment says exactly how far it gets and what to try next")
class ClientIT {
    private final TestFixture<Project> projectFixture = FixturesKt.projectFixture();

    /**
     * Credentials go in the URL rather than through DataGrip's credential store, which would want a real keychain.
     */
    private LocalDataSource dataSource() {
        var url = SqlServer.URL + "user=" + SqlServer.USER + ";password=" + SqlServer.PASSWORD + ";";
        var dataSource = LocalDataSource.create("ij-it", "com.microsoft.sqlserver.jdbc.SQLServerDriver", url, SqlServer.USER);

        var driver = DatabaseDriverManager.getInstance().getDriver("sqlserver.ms");
        assertNotNull(driver, "the bundled SQL Server driver definition is missing");
        dataSource.setDatabaseDriver(driver);
        downloadDriverFiles(driver, dataSource);
        return dataSource;
    }

    /**
     * Fetches the JDBC driver exactly as the "Download missing driver files" link in the data source dialog does,
     * rather than substituting a jar of our own. Cached under the IDE's download path, so only the first run reaches
     * the network.
     */
    private static void downloadDriverFiles(DatabaseDriver driver, LocalDataSource dataSource) {
        // The data source has its own artifact context, and that is the one the connection checks against.
        var context = new DatabaseArtifactDefaultContext().createContextForDataSource(dataSource);
        var manager = DatabaseArtifactManager.getInstance();
        // The download insists on something cancellable in the current thread, which a plain test method is not.
        ProgressManager.getInstance().runProcess(() -> {
            for (var ref : driver.getArtifacts()) {
                var version = DatabaseArtifactManager.resolveVersion(driver, ref);
                assertNotNull(version, "no version resolved for driver artifact " + ref);
                try {
                    manager.downloadArtifact(version, context, v -> {
                    });
                } catch (IOException e) {
                    throw new IllegalStateException("Could not download the JDBC driver files", e);
                }
            }
        }, new EmptyProgressIndicator());
    }


    @Test
    void connectsAndReadsAValue() throws Exception {
        var client = new Client(projectFixture.get(), dataSource());
        try {
            var spid = client.getSingle(Statements.CURRENT_SESSION_ID, "spid", Number.class).get(60, TimeUnit.SECONDS);

            assertNotNull(spid, "no session id came back, so nothing actually ran");
        } finally {
            client.release();
        }
    }

    @Test
    void readsRowsBackThroughTheClient() throws Exception {
        var client = new Client(projectFixture.get(), dataSource());
        try {
            var rows = client.getResult("SELECT 42 AS answer").get(60, TimeUnit.SECONDS);

            assertEquals(1, rows.size());
            assertEquals(42, ((Number) Objects.requireNonNull(rows.getFirst().get("answer"))).intValue());
        } finally {
            client.release();
        }
    }
}
