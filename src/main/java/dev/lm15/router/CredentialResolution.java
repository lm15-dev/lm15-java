package dev.lm15.router;

import dev.lm15.auth.AccessPolicy;
import dev.lm15.auth.CredentialPolicy;
import dev.lm15.auth.CredentialProvider;
import dev.lm15.auth.CredentialStores;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.NotConfiguredError;
import dev.lm15.registry.ProviderDefinition;
import dev.lm15.registry.Registry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The router's copy of the AUTH-1 credential chain (spec/auth.md), shared in
 * spirit with the doctor. One seam: {@link #resolve} — the integration step
 * swaps its body for {@code dev.lm15.auth.AuthChain.resolve} once the auth
 * module lands; nothing else in the router touches a credential source.
 *
 * <p>Rungs, first hit wins, later rungs dead:
 * <ol>
 * <li>the explicit {@code api_keys} entry selected by {@link #apiKeysSource}
 *     (exact provider, else the single sibling with the identical non-empty
 *     env-key declaration);</li>
 * <li>{@code oauth-unless-explicit}: a usable stored local login (offline
 *     probe through {@link CredentialStores});</li>
 * <li>the provider's declared env keys, in order, first non-empty value;</li>
 * <li>a keyless local server's placeholder key;</li>
 * <li>nothing: {@code oauth} / {@code oauth-unless-explicit} / cloud-chain
 *     policies defer to the adapter's own stored-login loader (which raises
 *     the typed, login-hinted error); a {@code key} policy is
 *     {@link NotConfiguredError} naming the env keys.</li>
 * </ol>
 * An {@code oauth} policy never runs the chain: the stored login owns the
 * provider (AUTH-1); an explicit entry is still honoured as configuration.
 */
final class CredentialResolution {
    private CredentialResolution() {}

    /**
     * What the chain decided. {@code credential} is null when the adapter's own
     * stored-login loader ({@link CredentialStores}) supplies it — {@code source}
     * then reads {@code stored-login}. Other sources: {@code api_keys}
     * ({@code sourceKey} = the config entry that served), {@code env}
     * ({@code sourceKey} = the variable), {@code placeholder}.
     */
    record Resolved(CredentialProvider credential, String source, String sourceKey) {}

    /** The seam. Swap for {@code AuthChain.resolve(policy, apiKeys, env, credentialsPath, files, home, settings)}. */
    static Resolved resolve(AccessPolicy policy, Map<String, CredentialProvider> apiKeys, Map<String, String> env, Path credentialsPath) {
        String provider = policy.provider();
        ProviderDefinition definition = Registry.lookup(provider);
        CredentialPolicy credentialPolicy = policy.credentialPolicy();

        // Rung 1: the explicit entry (shared-key rule).
        String entry = apiKeysSource(apiKeys, provider);
        if (entry != null) return new Resolved(apiKeys.get(entry), "api_keys", entry);

        // oauth: the stored login owns the provider; the chain below never runs.
        if (credentialPolicy == CredentialPolicy.OAUTH) return new Resolved(null, "stored-login", null);

        // Rung 2: a usable stored subscription login outranks ambient env keys (it spends no money per token).
        if (credentialPolicy == CredentialPolicy.OAUTH_UNLESS_EXPLICIT && CredentialStores.hasStoredCredential(policy)) {
            return new Resolved(null, "stored-login", null);
        }

        // Rung 3: the declared env keys in order.
        for (String key : policy.envKeys()) {
            String value = env.get(key);
            if (value != null && !value.isEmpty()) return new Resolved(CredentialProvider.of(value), "env", key);
        }

        // Rung 4: a keyless local server's placeholder.
        if (definition != null && definition.placeholderKey() != null) {
            return new Resolved(CredentialProvider.of(definition.placeholderKey()), "placeholder", null);
        }

        // Nothing: the adapter's own loader raises the typed login-hint error for stored-login and chain policies.
        if (credentialPolicy != CredentialPolicy.KEY) return new Resolved(null, "stored-login", null);
        List<String> envKeys = policy.envKeys();
        String fix = envKeys.isEmpty() ? "pass RouterConfig.builder().apiKey(\"" + provider + "\", ...)"
            : "Set " + String.join(" or ", envKeys) + " in the environment, or pass RouterConfig.builder().apiKey(\"" + provider + "\", ...)";
        throw new NotConfiguredError("no API key found for provider '" + provider + "'. " + fix + ".", ErrorMeta.of(provider), envKeys, null);
    }

    /**
     * Select a config key, not its value; never invoke credential providers
     * (spec/auth.md AUTH-1 § Shared explicit keys). Exact provider first (either
     * spelling), else the entries whose provider declares the identical
     * non-empty env-key list. Several candidates are ambiguous even if their
     * values look equal; an empty value under the selected key is a
     * configuration failure, never permission to fall back.
     *
     * @return the config key that serves {@code provider}, or null when none
     */
    static String apiKeysSource(Map<String, CredentialProvider> apiKeys, String provider) {
        if (apiKeys == null || apiKeys.isEmpty()) return null;
        String target = Registry.canonicalProvider(provider);
        List<String> candidates = new ArrayList<>();
        for (String key : apiKeys.keySet()) if (Registry.canonicalProvider(key).equals(target)) candidates.add(key);
        if (candidates.isEmpty()) {
            ProviderDefinition definition = Registry.lookup(target);
            if (definition != null && !definition.envKeys().isEmpty()) {
                for (String key : apiKeys.keySet()) {
                    ProviderDefinition other = Registry.lookup(key);
                    if (other != null && other.envKeys().equals(definition.envKeys())) candidates.add(key);
                }
            }
        }
        if (candidates.size() > 1) {
            List<String> sorted = new ArrayList<>(new TreeSet<>(candidates));
            throw new NotConfiguredError("RouterConfig(api_keys=...): ambiguous credentials for '" + target + "' from "
                + String.join(", ", sorted.stream().map(k -> "'" + k + "'").toList())
                + "; supply one entry under '" + target + "' or keep only one shared entry", ErrorMeta.of(target));
        }
        if (candidates.isEmpty()) return null;
        String key = candidates.get(0);
        if (apiKeys.get(key) == null) {
            throw new NotConfiguredError("RouterConfig(api_keys=...): empty credential under '" + key + "'; no environment fallback", ErrorMeta.of(target));
        }
        return key;
    }
}
