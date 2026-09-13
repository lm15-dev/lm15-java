package dev.lm15.jobs;

/**
 * How a handle waits: the poll interval and an optional deadline
 * (api-family: {@code wait(poll_every=, timeout=)}). Java reserves the
 * no-argument {@code wait()} on every object, so the family's {@code wait}
 * takes this options value ({@link #DEFAULT} for the defaults) — a stated
 * deviation in the README.
 */
public record WaitOptions(long pollEveryMs, Long timeoutMs) {
    public static final WaitOptions DEFAULT = new WaitOptions(5000, null);

    public static WaitOptions pollEvery(long ms) { return new WaitOptions(ms, null); }
    public WaitOptions timeout(long ms) { return new WaitOptions(pollEveryMs, ms); }
}
