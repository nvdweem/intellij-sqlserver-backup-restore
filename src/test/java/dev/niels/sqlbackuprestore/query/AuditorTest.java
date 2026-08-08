package dev.niels.sqlbackuprestore.query;

import dev.niels.sqlbackuprestore.query.Auditor.MessageType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The auditor is how a failed BACKUP or RESTORE becomes a failed future: {@link Query} registers a consumer that
 * watches for an error message, and completes exceptionally when it sees one.
 * <p>
 * Every consumer on a connection therefore shares a fate. If one of them throws, and the delivery loop lets that
 * escape, the consumers after it never hear about the message - and if the one that never hears is Query's error
 * watcher, the statement is reported to the user as a success. Which consumer runs first depends on the order things
 * happened to register, which is not something any caller thinks about.
 */
class AuditorTest {
    @Test
    void deliversAMessageToEveryConsumer() {
        var auditor = new Auditor();
        var seen = new ArrayList<String>();
        auditor.addWarningConsumer((type, message) -> seen.add("first:" + message));
        auditor.addWarningConsumer((type, message) -> seen.add("second:" + message));

        auditor.produce(MessageType.ERROR, "Cannot open backup device");

        assertEquals(List.of("first:Cannot open backup device", "second:Cannot open backup device"), seen);
    }

    @Test
    void keepsDeliveringWhenAnEarlierConsumerThrows() {
        var auditor = new Auditor();
        var seen = new ArrayList<String>();
        auditor.addWarningConsumer((type, message) -> {
            throw new IllegalStateException("this consumer is broken");
        });
        auditor.addWarningConsumer((type, message) -> seen.add(message));

        auditor.produce(MessageType.ERROR, "Cannot open backup device");

        // The second consumer is Query's error watcher in real life. Losing this message means a failed backup is
        // reported as a success.
        assertEquals(List.of("Cannot open backup device"), seen);
    }

    @Test
    void doesNotLetAConsumerFailureEscapeIntoTheDriversThread() {
        var auditor = new Auditor();
        auditor.addWarningConsumer((type, message) -> {
            throw new IllegalStateException("this consumer is broken");
        });

        // It runs on the database thread, mid-statement; an exception there is nobody's to catch.
        auditor.produce(MessageType.ERROR, "boom");
    }

    @Test
    void turnsAMissingMessageIntoAnEmptyOne() {
        var auditor = new Auditor();
        var seen = new ArrayList<String>();
        auditor.addWarningConsumer((type, message) -> seen.add(message));

        auditor.produce(MessageType.PRINT, null);

        assertEquals(List.of(""), seen);
    }

    @Test
    void stopsDeliveringToAConsumerThatHasBeenRemoved() {
        var auditor = new Auditor();
        var seen = new ArrayList<String>();
        java.util.function.BiConsumer<MessageType, String> consumer = (type, message) -> seen.add(message);
        auditor.addWarningConsumer(consumer);
        auditor.removeWarningConsumer(consumer);

        auditor.produce(MessageType.ERROR, "after removal");

        assertTrue(seen.isEmpty(), "a finished operation kept reacting to the next one's messages");
    }
}
