package dev.lm15.stream;

import java.util.function.Consumer;

/**
 * The post-completion warning channel (contract 2026-09-11): a source that
 * fails after the end event was yielded is reported here, never raised in
 * place of the complete Response. Default: {@code System.Logger} at WARNING.
 */
public final class StreamWarnings {
    private StreamWarnings() {}

    private static volatile Consumer<Throwable> handler = e ->
        System.getLogger("dev.lm15").log(System.Logger.Level.WARNING,
            "stream cleanup failed (" + e.getClass().getSimpleName() + "); see ResponseStream.cleanupErrors()");

    public static void setHandler(Consumer<Throwable> h) { handler = h == null ? e -> {} : h; }

    public static void warn(Throwable e) {
        try {
            handler.accept(e);
        } catch (RuntimeException warningFailure) {
            // User-provided logging must not replace a completed answer.
            try {
                System.getLogger("dev.lm15").log(System.Logger.Level.WARNING, "stream cleanup warning handler failed");
            } catch (RuntimeException ignored) { /* The original failure remains in cleanupErrors. */ }
        }
    }
}
