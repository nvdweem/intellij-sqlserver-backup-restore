package dev.niels.sqlbackuprestore;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.components.PersistentStateComponent;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * plugin.xml against the classes it names.
 * <p>
 * Everything declared there is resolved by string at runtime, so a renamed or moved class is not a compile error - it is
 * a menu entry that throws when clicked, or a settings page that fails to open. Reading the file and reflecting on what
 * it points at catches that in a plain test, without booting an IDE.
 */
class PluginXmlTest {
    private static final Path PLUGIN_XML = Path.of("src/main/resources/META-INF/plugin.xml");

    private static Document pluginXml() {
        return assertDoesNotThrow(() -> {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            try (var in = Files.newInputStream(PLUGIN_XML)) {
                return factory.newDocumentBuilder().parse(in);
            }
        });
    }

    private static List<Element> elements(String tag) {
        var found = new ArrayList<Element>();
        NodeList nodes = pluginXml().getElementsByTagName(tag);
        for (var i = 0; i < nodes.getLength(); i++) {
            found.add((Element) nodes.item(i));
        }
        return found;
    }

    @Test
    void namesActionClassesThatExistAndAreActions() {
        var actions = elements("action");

        assertFalse(actions.isEmpty(), "no actions declared");
        for (var action : actions) {
            var className = action.getAttribute("class");
            var loaded = assertDoesNotThrow(() -> Class.forName(className), className + " is declared but does not exist");
            assertTrue(AnAction.class.isAssignableFrom(loaded), className + " is not an AnAction");
        }
    }

    @Test
    void givesEveryActionAnIdInThePluginsOwnNamespace() {
        for (var action : elements("action")) {
            var id = action.getAttribute("id");
            // Action ids share one IDE-wide namespace. A bare "backup" collides with any other plugin that wants it,
            // and the loser of that collision simply does not appear.
            assertTrue(id.startsWith("dev.niels.sqlbackuprestore."), "not namespaced: " + id);
        }
    }

    @Test
    void namesServiceClassesThatExistAndCanPersistTheirState() {
        var services = new ArrayList<Element>();
        services.addAll(elements("applicationService"));
        services.addAll(elements("projectService"));

        assertEquals(1, services.size(), "expected the application-wide settings");
        for (var service : services) {
            var className = service.getAttribute("serviceImplementation");
            var loaded = assertDoesNotThrow(() -> Class.forName(className), className + " is declared but does not exist");
            assertTrue(PersistentStateComponent.class.isAssignableFrom(loaded),
                    className + " would not keep its settings across a restart");
        }
    }

    @Test
    void registersTheNotificationGroupIdThatTheCodeActuallyRaises() {
        var groups = elements("notificationGroup");

        assertEquals(1, groups.size());
        // These two drifting apart is exactly the bug this pins: the balloons were raised under a group id that was
        // never declared, so the platform logged a warning for each one and none could be configured.
        assertEquals(Constants.NOTIFICATION_GROUP, groups.getFirst().getAttribute("id"));
    }

    @Test
    void registersTheFileSystemUnderTheKeyItsOwnProtocolReturns() {
        var declared = elements("virtualFileSystem").getFirst();

        assertEquals("mssqldb", declared.getAttribute("key"));
        // Non-physical: the files live on the server, and the platform must not try to stat them locally.
        assertEquals("false", declared.getAttribute("physical"));
    }

    @Test
    void offersTheSettingsPageOnce() {
        assertTrue(elements("projectConfigurable").isEmpty(), "every setting on the page is stored IDE-wide");
        assertEquals(1, elements("applicationConfigurable").size());

        var className = elements("applicationConfigurable").getFirst().getAttribute("instance");
        assertDoesNotThrow(() -> Class.forName(className), className + " is declared but does not exist");
    }

    @Test
    void declaresTheDatabasePluginItCannotWorkWithout() throws IOException, SAXException, ParserConfigurationException {
        var dependencies = elements("depends").stream().map(element -> element.getTextContent().trim()).toList();

        assertTrue(dependencies.contains("com.intellij.database"),
                "every action reads a data source from the Database view: " + dependencies);
    }
}
