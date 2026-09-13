package dev.lm15.types;

/** The stream failed. */
public record StreamErrorEvent(ErrorDetail error) implements StreamEvent {
    public StreamErrorEvent {
        if (error == null) throw ValidationException.type("StreamErrorEvent.error must be an ErrorDetail");
    }

    @Override public StreamEventType type() { return StreamEventType.ERROR; }
}
