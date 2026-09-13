package dev.lm15.types;

import dev.lm15.json.JsonObject;

/** Per-million-token prices (floats on the wire). */
public record InferencePricing(Double inputPerMillion, Double outputPerMillion, Double cacheReadPerMillion,
                               Double cacheWritePerMillion, String currency, JsonObject dimensions) {
    public InferencePricing {
        for (Double d : new Double[] {inputPerMillion, outputPerMillion, cacheReadPerMillion, cacheWritePerMillion}) {
            if (d != null && (!Double.isFinite(d) || d < 0)) throw ValidationException.value("pricing must be >= 0");
        }
        if (currency == null) currency = "USD";
        if (currency.isEmpty()) throw ValidationException.value("currency must be a non-empty string");
    }

    /** A lower-bound estimate: an unknown (null) count contributes nothing, never zero. */
    public double estimate(Integer inputTokens, Integer outputTokens, Integer cacheReadTokens, Integer cacheWriteTokens) {
        double total = 0.0;
        if (inputPerMillion != null && inputTokens != null) total += inputTokens * inputPerMillion / 1_000_000;
        if (outputPerMillion != null && outputTokens != null) total += outputTokens * outputPerMillion / 1_000_000;
        if (cacheReadPerMillion != null && cacheReadTokens != null) total += cacheReadTokens * cacheReadPerMillion / 1_000_000;
        if (cacheWritePerMillion != null && cacheWriteTokens != null) total += cacheWriteTokens * cacheWritePerMillion / 1_000_000;
        return total;
    }
}
