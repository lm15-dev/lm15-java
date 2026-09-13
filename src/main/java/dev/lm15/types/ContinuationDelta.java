package dev.lm15.types;

import dev.lm15.json.JsonObject;

/**
 * Opaque provider continuation state arriving during streaming. A null
 * {@code partIndex} attaches the state to the assistant message; an int
 * attaches it to that completed part.
 */
public record ContinuationDelta(String provider, String kind, JsonObject data, Integer partIndex) implements Delta {
    public ContinuationDelta {
        Check.nonEmpty(provider, "ContinuationDelta.provider");
        Check.nonEmpty(kind, "ContinuationDelta.kind");
        Check.jsonObject(data, "data", true);
        if (partIndex != null) Check.partIndex(partIndex);
    }

    public ContinuationState toState() { return new ContinuationState(provider, kind, data); }

    public static ContinuationDelta of(ContinuationState state, Integer partIndex) {
        return new ContinuationDelta(state.provider(), state.kind(), state.data(), partIndex);
    }

    @Override public DeltaType type() { return DeltaType.CONTINUATION; }
}
