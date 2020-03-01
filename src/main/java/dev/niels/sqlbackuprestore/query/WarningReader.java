package dev.niels.sqlbackuprestore.query;

import com.intellij.database.remote.jdbc.RemoteStatement;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.rmi.RemoteException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Thread that keeps trying to get the warnings from a statement.
 * Can be used to get near real-time information about the progress of statements.
 */
@RequiredArgsConstructor
@Slf4j
public class WarningReader {
    private final RemoteStatement statement;
    private final Consumer<SQLWarning> consumer;
    private boolean running = true;

    public static Optional<WarningReader> ifNeeded(RemoteStatement s, Consumer<SQLWarning> c) {
        if (c != null) {
            return Optional.of(new WarningReader(s, c).startReading());
        }
        return Optional.empty();
    }

    public void stop() {
        running = false;
        try {
            read();
        } catch (Exception e) {
            log.error("Unable to read warnings", e);
        }
    }

    public WarningReader startReading() {
        new Thread(this::readLoop).start();
        return this;
    }

    @SneakyThrows
    private void readLoop() {
        while (running && !statement.isClosed()) {
            read();
            Thread.sleep(10);
        }
    }

    private synchronized void read() throws RemoteException, SQLException {
        statement.getAllWarnings().forEach(consumer);
    }

    public void consume(int code, String message, Throwable cause) {
        consumer.accept(new SQLWarning(message, "error", code, cause));
    }
}
