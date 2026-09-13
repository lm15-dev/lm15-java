package dev.lm15.types;

/** A typed content delta arrived. */
public record StreamDeltaEvent(Delta delta) implements StreamEvent {
    public StreamDeltaEvent {
        if (delta == null) throw ValidationException.type("StreamDeltaEvent.delta must be a Delta");
    }

    @Override public StreamEventType type() { return StreamEventType.DELTA; }
}
