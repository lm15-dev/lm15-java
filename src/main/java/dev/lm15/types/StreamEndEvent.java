package dev.lm15.types;

import dev.lm15.json.JsonObject;

/** The response stream completed — exactly one per stream, final (MAP-3). */
public record StreamEndEvent(FinishReason finishReason, Usage usage, JsonObject providerData) implements StreamEvent {
    public StreamEndEvent {
        Check.jsonObject(providerData, "provider_data", false);
    }

    public StreamEndEvent(FinishReason finishReason, Usage usage) { this(finishReason, usage, null); }

    @Override public StreamEventType type() { return StreamEventType.END; }

    @Override public String toString() {
        return "StreamEndEvent(finish_reason=" + finishReason + ", usage=" + usage
            + (providerData == null ? "" : ", provider_data=<object: " + providerData.size() + " keys>") + ")";
    }
}
