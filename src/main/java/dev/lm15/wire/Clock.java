package dev.lm15.wire;

import java.time.Instant;

/** The clock every time-dependent byte reads (SigV4 date, JWT iat/exp); the harness injects a fixed one. */
@FunctionalInterface
public interface Clock {
    Instant now();

    Clock SYSTEM = Instant::now;

    static Clock fixed(Instant at) { return () -> at; }
}
