package dev.lm15.jobs;

import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** The shared wait loop: poll until done, bounded by a deadline (a TimeoutException past it). */
final class Jobs {
    private Jobs() {}

    static boolean await(Supplier<Boolean> done, Runnable refresh, long pollEveryMs, Long timeoutMs, String what) throws TimeoutException {
        long deadline = timeoutMs == null ? Long.MAX_VALUE : System.nanoTime() + timeoutMs * 1_000_000L;
        while (!done.get()) {
            if (System.nanoTime() >= deadline) throw new TimeoutException(what + " not finished after " + timeoutMs + "ms");
            try {
                Thread.sleep(Math.max(1, pollEveryMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TimeoutException("interrupted while waiting for " + what);
            }
            refresh.run();
        }
        return true;
    }
}
