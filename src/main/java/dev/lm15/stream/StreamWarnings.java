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
            "stream source failed after the response was complete (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "); the Response is returned unchanged");

    public static void setHandler(Consumer<Throwable> h) { handler = h == null ? e -> {} : h; }

    public static void warn(Throwable e) { handler.accept(e); }
}
