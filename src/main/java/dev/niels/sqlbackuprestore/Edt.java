package dev.niels.sqlbackuprestore;

import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Asking the user something from a background thread.
 */
public final class Edt {
    private Edt() {
    }

    /**
     * Runs {@code supplier} on the event thread and hands back what it produced. The platform's own
     * {@code invokeAndWait} returns nothing, so every caller that needs an answer - which is every dialog - has to
     * carry the value back itself. Doing that by hand is how a supplier that threw used to leave the calling thread
     * waiting for a value that was never going to arrive.
     */
    public static <T> T compute(@NotNull Supplier<T> supplier) {
        var result = new CompletableFuture<T>();
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                result.complete(supplier.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.join();
    }
}
