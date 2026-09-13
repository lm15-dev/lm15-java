package dev.lm15.types;

/** The response stream has started (exactly one per stream, MAP-4). */
public record StreamStartEvent(String id, String model) implements StreamEvent {
    public StreamStartEvent {
        Check.optNonEmpty(id, "StreamStartEvent.id");
        Check.optNonEmpty(model, "StreamStartEvent.model");
    }

    @Override public StreamEventType type() { return StreamEventType.START; }
}
