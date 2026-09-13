package dev.lm15.router;

import dev.lm15.auth.CredentialPolicy;
import dev.lm15.registry.ProviderDefinition;
import dev.lm15.registry.Registry;
import dev.lm15.types.ModelInfo;
import dev.lm15.types.ValidationException;

import java.util.ArrayList;
import java.util.List;

/**
 * The complete answer to "how did you route this string" (lm15-python
 * {@code lm15.router.Resolution}). {@link LMRouter#resolve} returning this
 * IS the explain method; {@link #describe()} renders it as one paragraph.
 * A resolution never embeds a credential: {@code envKey} names WHICH
 * variable the key would be read from, never its value.
 *
 * @param requested the verbatim input string
 * @param model     the id sent on the wire (prefix stripped, alias resolved)
 * @param provider  the canonical (hyphenated) provider string
 * @param adapter   the family LM class name ({@code AnthropicLM}, {@code OpenAIChatLM}, …)
 * @param source    the rung that answered
 * @param rule      the matching rule when {@code source} is {@link Source#RULE}
 * @param envKey    the env var the key would be read from; null for a stored-login
 *                  provider, a keyless local preset, or when an explicit api_keys
 *                  entry overrides env lookup
 * @param modelInfo catalog metadata when {@code source} is {@link Source#CATALOG}
 * @param compat    the compat preset name when routed through a bound registry entry
 */
public record Resolution(String requested, String model, String provider, String adapter, Source source, RouteRule rule,
                         String envKey, ModelInfo modelInfo, String compat) {

    /** The rung that answered: prefix (1), catalog (2), rule (3). */
    public enum Source {
        PREFIX("prefix"), CATALOG("catalog"), RULE("rule");

        private final String wire;
        Source(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        @Override public String toString() { return wire; }

        public static Source fromWire(String value) {
            for (Source s : values()) if (s.wire.equals(value)) return s;
            throw ValidationException.value("unknown resolution source: " + value);
        }
    }

    public Resolution {
        if (requested == null) throw ValidationException.value("Resolution.requested must be a string");
        if (model == null || model.isEmpty()) throw ValidationException.value("Resolution.model must be a non-empty string");
        if (provider == null || provider.isEmpty()) throw ValidationException.value("Resolution.provider must be a non-empty string");
        if (adapter == null) adapter = "ProviderLM";
        if (source == null) throw ValidationException.value("Resolution.source must be given");
    }

    /** One-paragraph human-readable explanation of this resolution. */
    public String describe() {
        List<String> parts = new ArrayList<>();
        parts.add(quote(requested) + " -> provider " + quote(provider) + " (" + adapter + ")");
        switch (source) {
            case PREFIX -> parts.add("via explicit provider prefix");
            case CATALOG -> parts.add("via catalog match");
            case RULE -> {
                if (rule != null) {
                    String note = rule.note().isEmpty() ? "" : " — " + rule.note();
                    parts.add("via built-in rule prefix=" + quote(rule.prefix()) + note);
                }
            }
        }
        if (compat != null) parts.add("compat preset " + quote(compat));
        parts.add("wire model " + quote(model));
        ProviderDefinition definition = Registry.lookup(provider);
        CredentialPolicy policy = definition == null ? CredentialPolicy.KEY : definition.credentialPolicy();
        if (policy == CredentialPolicy.OAUTH_UNLESS_EXPLICIT) {
            // resolve() is pure (no file reads): the chain is described, not decided.
            String chain = "key from explicit api_keys, else the stored subscription OAuth credential";
            if (envKey != null) chain += ", else $" + envKey;
            parts.add(chain);
        } else if (envKey != null) {
            parts.add("key from $" + envKey);
        } else if (policy == CredentialPolicy.OAUTH) {
            parts.add("local OAuth credential (no env key)");
        } else if (definition != null && definition.placeholderKey() != null) {
            parts.add("key from explicit api_keys or the preset's local-server default");
        } else {
            parts.add("key from explicit api_keys");
        }
        return String.join("; ", parts) + ".";
    }

    private static String quote(String s) { return "'" + s + "'"; }

    @Override public String toString() { return describe(); }
}
