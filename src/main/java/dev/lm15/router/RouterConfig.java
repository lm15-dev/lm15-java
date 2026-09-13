package dev.lm15.router;

import dev.lm15.auth.Credential;
import dev.lm15.auth.CredentialProvider;
import dev.lm15.registry.Registry;
import dev.lm15.transport.Transport;
import dev.lm15.types.ModelInfo;
import dev.lm15.types.ValidationException;
import dev.lm15.wire.Clock;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the router consults (lm15-python {@code lm15.router.RouterConfig}).
 * All explicit, nothing discovered behind your back.
 *
 * <ul>
 * <li>{@code apiKeys}: provider string → credential; beats env. A value is a
 *     static key string, a {@link Credential}, or a {@link CredentialProvider}
 *     invoked per request by the adapter. An exact provider entry wins;
 *     otherwise the one entry whose provider declares the identical non-empty
 *     env-key list supplies the credential ({@code openai} also covers
 *     {@code openai-chat}; spec/auth.md AUTH-1 § Shared explicit keys).</li>
 * <li>{@code env}: the environment the router reads; {@code System.getenv()}
 *     when not given (a hermetic caller passes its own map).</li>
 * <li>{@code baseUrls}: provider string → the URL the provider's LM is built
 *     with, replacing the adapter's (or the preset's) default. Not for the
 *     cloud doors: their URL is derived from {@code settings}.</li>
 * <li>{@code settings}: cloud-host settings per provider (AUTH-10).</li>
 * <li>{@code catalog}: the explicit model catalog (rung 2); none means no
 *     catalog rung.</li>
 * <li>{@code rules}: the prefix rules (rung 3); {@link LMRouter#DEFAULT_RULES}
 *     by default.</li>
 * <li>{@code transport}, {@code clock}, {@code credentialsPath}: passed to
 *     every LM the router constructs.</li>
 * </ul>
 *
 * Secrecy (AUTH-5): {@code toString} never shows credential or env values.
 */
public final class RouterConfig {
    private final ModelRegistry catalog;
    private final List<RouteRule> rules;
    private final Map<String, String> env;
    private final Map<String, CredentialProvider> apiKeys;
    private final Map<String, String> baseUrls;
    private final Map<String, Map<String, String>> settings;
    private final Transport transport;
    private final Clock clock;
    private final Path credentialsPath;

    private RouterConfig(Builder b) {
        this.catalog = b.catalog;
        this.rules = List.copyOf(b.rules);
        this.env = b.env == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(b.env));
        this.apiKeys = Collections.unmodifiableMap(new LinkedHashMap<>(b.apiKeys));
        this.baseUrls = Collections.unmodifiableMap(new LinkedHashMap<>(b.baseUrls));
        LinkedHashMap<String, Map<String, String>> s = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : b.settings.entrySet()) s.put(e.getKey(), Map.copyOf(e.getValue()));
        this.settings = Collections.unmodifiableMap(s);
        this.transport = b.transport;
        this.clock = b.clock;
        this.credentialsPath = b.credentialsPath;
    }

    /** The defaults: no keys, the process environment, no catalog, {@link LMRouter#DEFAULT_RULES}. */
    public static final RouterConfig DEFAULT = builder().build();

    public static Builder builder() { return new Builder(); }

    /** The explicit catalog, or null when no catalog was given (no catalog rung). */
    public ModelRegistry catalog() { return catalog; }
    public List<RouteRule> rules() { return rules; }
    /** The explicit env map, or null when the process environment is read. */
    public Map<String, String> env() { return env; }
    /** The explicit credentials, keyed by the spelling given; a null value is an empty credential (refused at lookup). */
    public Map<String, CredentialProvider> apiKeys() { return apiKeys; }
    public Map<String, String> baseUrls() { return baseUrls; }
    public Map<String, Map<String, String>> settings() { return settings; }
    public Transport transport() { return transport; }
    public Clock clock() { return clock; }
    public Path credentialsPath() { return credentialsPath; }

    /** The environment the router reads: the explicit map, else the process environment. */
    public Map<String, String> environment() { return env != null ? env : System.getenv(); }

    /** The non-empty value of one env var, or null. */
    public String envValue(String key) {
        String v = environment().get(key);
        return v == null || v.isEmpty() ? null : v;
    }

    /** The explicit base_urls entry for a provider, matching either spelling; URLs are never shared. */
    public String baseUrlFor(String provider) {
        String target = Registry.canonicalProvider(provider);
        for (Map.Entry<String, String> e : baseUrls.entrySet()) {
            if (Registry.canonicalProvider(e.getKey()).equals(target)) return e.getValue();
        }
        return null;
    }

    /** The explicit settings entry for a provider, matching either spelling, or an empty map. */
    public Map<String, String> settingsFor(String provider) {
        String target = Registry.canonicalProvider(provider);
        for (Map.Entry<String, Map<String, String>> e : settings.entrySet()) {
            if (Registry.canonicalProvider(e.getKey()).equals(target)) return e.getValue();
        }
        return Map.of();
    }

    public Builder toBuilder() { return new Builder(this); }

    @Override public String toString() {
        return "RouterConfig(rules=" + rules.size() + ", env=" + (env == null ? "<process>" : "<" + env.size() + " vars>")
            + ", api_keys=" + apiKeys.keySet() + ", base_urls=" + baseUrls + ", settings=" + settings
            + ", catalog=" + (catalog == null ? "none" : catalog.size() + " models") + ")";
    }

    public static final class Builder {
        private ModelRegistry catalog;
        private List<RouteRule> rules = LMRouter.DEFAULT_RULES;
        private Map<String, String> env;
        private final LinkedHashMap<String, CredentialProvider> apiKeys = new LinkedHashMap<>();
        private final LinkedHashMap<String, String> baseUrls = new LinkedHashMap<>();
        private final LinkedHashMap<String, Map<String, String>> settings = new LinkedHashMap<>();
        private Transport transport;
        private Clock clock = Clock.SYSTEM;
        private Path credentialsPath;

        private Builder() {}

        private Builder(RouterConfig c) {
            catalog = c.catalog;
            rules = c.rules;
            env = c.env;
            apiKeys.putAll(c.apiKeys);
            baseUrls.putAll(c.baseUrls);
            settings.putAll(c.settings);
            transport = c.transport;
            clock = c.clock;
            credentialsPath = c.credentialsPath;
        }

        /** The explicit catalog (rung 2). */
        public Builder catalog(ModelRegistry registry) { this.catalog = registry; return this; }

        /** The explicit catalog from canonical values, in catalog order. */
        public Builder catalog(List<ModelInfo> infos) { this.catalog = infos == null ? null : ModelRegistry.of(infos); return this; }

        /** Replaces the built-in rules; first match wins. */
        public Builder rules(List<RouteRule> rules) { this.rules = rules == null ? List.of() : List.copyOf(rules); return this; }

        /** The environment to read instead of the process environment (hermetic callers pass a map; the vet passes {}). */
        public Builder env(Map<String, String> env) { this.env = env; return this; }

        /** A static API key for a provider; an empty or null key is an empty explicit credential (refused at lookup, never a fallback). */
        public Builder apiKey(String provider, String key) {
            requireProvider(provider);
            apiKeys.put(provider, key == null || key.isEmpty() ? null : CredentialProvider.of(key));
            return this;
        }

        public Builder apiKey(String provider, Credential credential) {
            requireProvider(provider);
            apiKeys.put(provider, credential == null ? null : CredentialProvider.of(credential));
            return this;
        }

        public Builder apiKey(String provider, CredentialProvider provider1) {
            requireProvider(provider);
            apiKeys.put(provider, provider1);
            return this;
        }

        /** Provider → String / {@link Credential} / {@link CredentialProvider}. */
        public Builder apiKeys(Map<String, ?> keys) {
            if (keys == null) return this;
            for (Map.Entry<String, ?> e : keys.entrySet()) {
                Object v = e.getValue();
                if (v == null) apiKey(e.getKey(), (String) null);
                else if (v instanceof String s) apiKey(e.getKey(), s);
                else if (v instanceof Credential c) apiKey(e.getKey(), c);
                else if (v instanceof CredentialProvider p) apiKey(e.getKey(), p);
                else throw ValidationException.type("api_keys[" + e.getKey() + "]: a credential is a String, a Credential or a CredentialProvider, not " + v.getClass().getSimpleName());
            }
            return this;
        }

        public Builder baseUrl(String provider, String url) {
            requireProvider(provider);
            if (url == null || url.isEmpty()) throw ValidationException.value("base_urls[" + provider + "] must be a non-empty string");
            baseUrls.put(provider, url);
            return this;
        }

        public Builder baseUrls(Map<String, String> urls) {
            if (urls != null) for (Map.Entry<String, String> e : urls.entrySet()) baseUrl(e.getKey(), e.getValue());
            return this;
        }

        public Builder settings(String provider, Map<String, String> s) {
            requireProvider(provider);
            settings.put(provider, s == null ? Map.of() : new LinkedHashMap<>(s));
            return this;
        }

        public Builder setting(String provider, String name, String value) {
            requireProvider(provider);
            settings.computeIfAbsent(provider, k -> new LinkedHashMap<>()).put(name, value);
            return this;
        }

        public Builder settings(Map<String, Map<String, String>> all) {
            if (all != null) for (Map.Entry<String, Map<String, String>> e : all.entrySet()) settings(e.getKey(), e.getValue());
            return this;
        }

        public Builder transport(Transport t) { this.transport = t; return this; }
        public Builder clock(Clock c) { this.clock = c == null ? Clock.SYSTEM : c; return this; }
        public Builder credentialsPath(Path p) { this.credentialsPath = p; return this; }

        private static void requireProvider(String provider) {
            if (provider == null || provider.isEmpty()) throw ValidationException.value("provider must be a non-empty string");
        }

        public RouterConfig build() { return new RouterConfig(this); }
    }
}
