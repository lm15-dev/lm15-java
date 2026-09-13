package dev.lm15.wire;

import dev.lm15.auth.AccessPolicy;
import dev.lm15.compat.Compat;

import java.util.Map;

/**
 * Everything a dialect reads besides the Request itself, on both sides of
 * the wire: the provider string, the bound policy, resolved host settings,
 * the compat value, the base URL, the wire model (a {@code provider:}
 * prefix already removed) and the ChatGPT account id when bound.
 */
public record BuildContext(String provider, AccessPolicy policy, Map<String, String> settings, Compat compat,
                           String baseUrl, String model, String accountId) {
    public BuildContext {
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    public <C extends Compat> C compat(Class<C> cls) {
        if (compat == null) throw new IllegalStateException(provider + ": no compat value bound");
        return cls.cast(compat);
    }

    public BuildContext withModel(String m) { return new BuildContext(provider, policy, settings, compat, baseUrl, m, accountId); }
}
