package dev.lm15.types;

import dev.lm15.json.JsonObject;

import java.util.List;

/**
 * Opaque provider-owned state needed to continue or replay a transcript
 * (spec/types.md § ContinuationState). {@code provider} names the dialect that
 * consumes the state (MAP-7.8), {@code kind} is an open string namespace,
 * {@code data} an opaque JSON object (required, may be empty).
 */
public record ContinuationState(String provider, String kind, JsonObject data) {
    public ContinuationState {
        Check.nonEmpty(provider, "ContinuationState.provider");
        Check.nonEmpty(kind, "ContinuationState.kind");
        Check.jsonObject(data, "data", true);
    }

    public ContinuationState(String provider, String kind) {
        this(provider, kind, JsonObject.EMPTY);
    }

    /** The first matching state's data from a continuation list, or null. */
    public static JsonObject find(List<ContinuationState> states, String provider, String kind) {
        for (ContinuationState s : states) {
            if (s.provider.equals(provider) && s.kind.equals(kind)) return s.data;
        }
        return null;
    }

    @Override public String toString() {
        return "ContinuationState(provider=" + provider + ", kind=" + kind + ", data=<object: " + data.size() + " keys>)";
    }
}
