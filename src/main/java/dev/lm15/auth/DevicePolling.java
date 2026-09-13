package dev.lm15.auth;

import dev.lm15.errors.AuthError;
import dev.lm15.errors.ErrorMeta;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * The RFC 8628 §3.5 device-authorization polling state machine (AUTH-9):
 * pending / slow_down (+5 s unless the server names an interval) / complete
 * / failure / expiry, with an injectable clock and sleep for tests.
 */
public final class DevicePolling {
    private DevicePolling() {}

    static final double SLOW_DOWN_STEP_S = 5.0;

    public sealed interface Result permits Pending, SlowDown, Complete, Failed {}

    /** Authorization still pending; poll again after the interval. */
    public record Pending() implements Result {}

    /** Server asked to slow down; interval grows by 5 s unless it names one. */
    public record SlowDown(Double intervalS) implements Result {}

    /** Authorization finished; {@code value} usually carries tokens (never rendered). */
    public record Complete(Object value) implements Result {
        @Override public String toString() { return "Complete(<redacted>)"; }
    }

    /** Terminal denial/failure. {@code message} must not contain secrets. */
    public record Failed(String message) implements Result {}

    /** The device code expired before the user approved it: a typed error distinct from denial. */
    public static final class DeviceCodeExpiredError extends AuthError {
        public DeviceCodeExpiredError(String message, String provider) { super(message, ErrorMeta.of(provider)); }
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(double seconds);
    }

    public static final Sleeper REAL_SLEEP = seconds -> {
        try {
            Thread.sleep((long) Math.max(0, seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    public static final DoubleSupplier MONOTONIC = () -> System.nanoTime() / 1e9;

    /** Run the polling loop; return the completed value. {@code poll} performs one token-endpoint request and classifies the outcome. */
    public static Object poll(Supplier<Result> poll, double intervalS, double expiresInS, String provider, boolean waitBeforeFirstPoll,
                              Sleeper sleep, DoubleSupplier clock) {
        double interval = Math.max(intervalS, 0.0);
        double deadline = clock.getAsDouble() + expiresInS;
        boolean first = true;
        while (true) {
            if (!first || waitBeforeFirstPoll) {
                if (clock.getAsDouble() + interval > deadline) {
                    throw new DeviceCodeExpiredError("Device authorization expired before it was approved. Start the login again.", provider);
                }
                sleep.sleep(interval);
            }
            first = false;
            if (clock.getAsDouble() > deadline) {
                throw new DeviceCodeExpiredError("Device authorization expired before it was approved. Start the login again.", provider);
            }
            Result result = poll.get();
            if (result instanceof Complete c) return c.value();
            if (result instanceof Failed f) throw new AuthError(f.message(), ErrorMeta.of(provider));
            if (result instanceof SlowDown s) interval = s.intervalS() != null && s.intervalS() > 0 ? s.intervalS() : interval + SLOW_DOWN_STEP_S;
            // Pending: loop
        }
    }

    public static Object poll(Supplier<Result> poll, double intervalS, double expiresInS, String provider) {
        return poll(poll, intervalS, expiresInS, provider, false, REAL_SLEEP, MONOTONIC);
    }
}
