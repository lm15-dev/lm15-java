package dev.lm15.types;

/** A canonical stream event (closed sum): start, delta, end, error. */
public sealed interface StreamEvent permits StreamStartEvent, StreamDeltaEvent, StreamEndEvent, StreamErrorEvent {
    StreamEventType type();
}
