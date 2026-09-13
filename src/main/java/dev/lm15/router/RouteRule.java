package dev.lm15.router;

import dev.lm15.types.ValidationException;

/**
 * Maps a model-id prefix to a provider. That is all a rule is. {@code note}
 * is a short human rationale surfaced by {@link Resolution#describe()}.
 * The built-in table is {@link LMRouter#DEFAULT_RULES}.
 */
public record RouteRule(String prefix, String provider, String note) {
    public RouteRule {
        if (prefix == null || prefix.isEmpty()) throw ValidationException.value("RouteRule.prefix must be a non-empty string");
        if (provider == null || provider.isEmpty()) throw ValidationException.value("RouteRule.provider must be a non-empty string");
        if (note == null) note = "";
    }

    public RouteRule(String prefix, String provider) { this(prefix, provider, ""); }
}
