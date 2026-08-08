package dev.niels.sqlbackuprestore.it;

import com.intellij.database.dataSource.DatabaseDriverManager;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.FixturesKt;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.testFramework.junit5.fixture.TestFixtures;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory;
import dev.niels.sqlbackuprestore.query.Client;
import dev.niels.sqlbackuprestore.query.Statements;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.File;
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
 * <b>Not working yet.</b> Three obstacles have been cleared and a fourth has not:
 * <ol>
 *     <li>The IDE refuses to connect with "Driver files are not downloaded", because the bundled {@code sqlserver.ms}
 *         definition declares artifacts it expects to fetch. Cleared by emptying {@code getArtifacts()}.</li>
 *     <li>The driver then has no jar. Cleared by pointing {@code setAdditionalClasspathElements} at the mssql-jdbc
 *         already on this test's classpath, whose path the build passes in as {@code it.mssql.jdbc.jar} - the
 *         platform's classloader hides it from {@code getCodeSource()}.</li>
 *     <li>Those elements are VFS urls, not paths, hence {@code VfsUtil.getUrlForLibraryRoot}.</li>
 *     <li><b>Open:</b> connecting now fails with "Execution cancelled" out of
 *         {@code DatabaseConnectionEstablisher}. Presumably the establishment wants a modality or progress context
 *         that a plain headless test does not give it.</li>
 * </ol>
 * Worth finishing: this is the one layer no other test covers, and it is where the reported "backing several databases
 * up to an unwritable folder reports no error" bug lives.
 */
@TestApplication
@TestFixtures
@RequiresSqlServer
@Disabled("Does not connect yet - see the class comment for exactly how far it gets.")
class ClientIT {
    private final TestFixture<Project> projectFixture = FixturesKt.projectFixture();

    /**
     * Credentials go in the URL rather than through DataGrip's credential store, which would want a real keychain.
     */
    private LocalDataSource dataSource() {
        var url = SqlServer.URL + "user=" + SqlServer.USER + ";password=" + SqlServer.PASSWORD + ";";
        var dataSource = LocalDataSource.create("ij-it", "com.microsoft.sqlserver.jdbc.SQLServerDriver", url, SqlServer.USER);

        // The IDE downloads its JDBC drivers on demand, which a test has no business doing. The driver is already on
        // this test's own classpath, so point DataGrip's driver definition straight at that jar.
        var driver = DatabaseDriverManager.getInstance().getDriver("sqlserver.ms");
        assertNotNull(driver, "the bundled SQL Server driver definition is missing");
        // Its declared artifacts are what the IDE would go and download; the jar below stands in for them.
        try {
            driver.getArtifacts().clear();
        } catch (UnsupportedOperationException e) {
            throw new IllegalStateException("Cannot clear the driver's artifacts, so it will insist on downloading", e);
        }
        driver.setAdditionalClasspathElements(SimpleClasspathElementFactory.createElements(
                VfsUtil.getUrlForLibraryRoot(new File(driverJar()))));
        DatabaseDriverManager.getInstance().updateDriver(driver);
        dataSource.setDatabaseDriver(driver);
        return dataSource;
    }

    /** Handed over by the build; the platform's classloader hides it from {@code getCodeSource()}. */
    private static String driverJar() {
        var jar = System.getProperty("it.mssql.jdbc.jar");
        assertNotNull(jar, "the build did not pass it.mssql.jdbc.jar");
        return jar;
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
