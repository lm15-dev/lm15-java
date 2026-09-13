package dev.lm15.auth;

import dev.lm15.cloud.ChainContext;
import dev.lm15.cloud.Chains;
import dev.lm15.cloud.Hosts;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.NotConfiguredError;
import dev.lm15.registry.ProviderDefinition;
import dev.lm15.registry.Registry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The AUTH-1 credential resolution as one pure function the router and the
 * doctor (AUTH-7) both use, so the report and real construction can never
 * diverge. Every rung of every policy — {@code key}, {@code oauth},
 * {@code oauth-unless-explicit}, {@code aws-chain}, {@code azure-chain},
 * {@code gcp-chain} — in chain order, with the shared-explicit-keys rule
 * (ratified 2026-09-09). No network ever: network rungs are {@code unprobed}
 * unless an environment variable proves them present or absent; the
 * returned credential provider does its I/O at request time.
 *
 * <p>Purity note: presence checks read secret values into memory; nothing
 * here retains or renders them (AUTH-5).
 */
public final class AuthChain {
    private AuthChain() {}

    static {
        AuthModule.init();
    }

    /**
     * One rung. {@code kind} is the language-neutral fixture vocabulary
     * ({@code api_keys}, {@code env:<VAR>}, {@code placeholder}, {@code oauth-file},
     * a cloud rung name); {@code state} is {@code selected|shadowed|absent|unprobed};
     * {@code detail} and {@code source} are human text carrying no secret.
     */
    public record Step(String kind, String state, String detail, String source) {
        public Step(String kind, String state, String detail) { this(kind, state, detail, kind); }

        public String describe() {
            String marker = switch (state) {
                case "selected" -> "=> ";
                case "shadowed" -> " ~ ";
                case "unprobed" -> " ? ";
                default -> " - ";
            };
            return marker + source + ": " + detail;
        }
    }

    /**
     * The resolution: the credential provider (null when nothing is
     * configured), the winning rung's kind as {@code source}
     * ({@code api_keys | env:<VAR> | placeholder | oauth-file | <rung>}; the
     * first unprobed rung when nothing was selected offline), every step in
     * chain order, {@code configured}, and for cloud doors the resolved host
     * settings (or the settings error).
     */
    public record Resolved(CredentialProvider credential, String source, List<Step> steps, boolean configured,
                           Map<String, String> settings, String settingError) {
        public Resolved {
            steps = steps == null ? List.of() : List.copyOf(steps);
            settings = settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        }

        /** The credential provider, or the typed not-configured error naming the fix. */
        public CredentialProvider require(AccessPolicy policy) {
            if (credential != null) return credential;
            String fix = policy.envKeys().isEmpty() ? "" : " Set " + String.join(" or ", policy.envKeys()) + " in the environment, or pass an explicit api_keys entry.";
            throw new NotConfiguredError("no credential found for provider '" + policy.provider() + "'." + fix, ErrorMeta.of(policy.provider()),
                policy.envKeys(), policy.loginHint());
        }
    }

    /**
     * Resolve {@code policy}'s credential over the given inputs only.
     *
     * @param policy          the provider's access policy (its credential policy drives the chain)
     * @param apiKeys         explicit entries keyed by provider string (either spelling); may be null
     * @param env             the complete environment (null = the process environment)
     * @param credentialsPath an override for the provider's stored-login file (null = the AUTH-8 default paths)
     * @param files           a sandbox file map keyed by {@code ~/}-relative or absolute paths, resolved against {@code home}; null = the filesystem
     * @param home            the home directory for {@code ~} (null = {@code env.HOME}, else the user's home)
     * @param settings        explicit host settings for cloud doors; may be null
     */
    public static Resolved resolve(AccessPolicy policy, Map<String, CredentialProvider> apiKeys, Map<String, String> env, Path credentialsPath,
                                   Map<String, String> files, String home, Map<String, String> settings) {
        Map<String, String> environment = env != null ? env : System.getenv();
        String canonical = Registry.canonicalProvider(policy.provider());
        ProviderDefinition definition = Registry.lookup(canonical);
        Path homePath = home != null && !home.isEmpty() ? Path.of(home) : null;

        if (policy.cloudChain() || (definition != null && definition.hosted())) {
            return resolveCloud(policy, canonical, apiKeys, environment, files, homePath, settings);
        }

        if (policy.credentialPolicy() == CredentialPolicy.OAUTH) {
            Step step = oauthStep(canonical, credentialsPath, false);
            boolean configured = step.state().equals("selected");
            CredentialProvider credential = !configured ? null : canonical.equals("claude-code")
                ? () -> new Credential.BearerToken(ClaudeCodeStore.accessToken(credentialsPath))
                : () -> new Credential.BearerToken(CodexStore.accessToken(credentialsPath));
            return new Resolved(credential, configured ? "oauth-file" : null, List.of(step), configured, Map.of(), null);
        }

        List<Step> steps = new ArrayList<>();
        CredentialProvider credential = null;
        String source = null;

        String entry = apiKeysSource(apiKeys, canonical);
        if (entry != null) {
            steps.add(new Step("api_keys", "selected", "provided (value never shown)", entrySource(canonical, entry)));
            credential = apiKeys.get(entry);
            source = "api_keys";
        } else {
            steps.add(new Step("api_keys", "absent", "not provided", "explicit api_keys entry"));
        }

        if (policy.credentialPolicy() == CredentialPolicy.OAUTH_UNLESS_EXPLICIT) {
            // The stored subscription login outranks env keys (AUTH-1): it spends no money per token.
            List<Path> paths = credentialsPath != null ? List.of(credentialsPath) : XaiStore.storePaths(environment, homePath);
            Step step = xaiOauthStep(paths, source != null);
            steps.add(step);
            if (step.state().equals("selected")) {
                credential = () -> new Credential.BearerToken(XaiStore.accessToken(paths, true));
                source = "oauth-file";
            }
        }

        for (String key : policy.envKeys()) {
            String value = environment.get(key);
            if (value != null && !value.isEmpty()) {
                steps.add(new Step("env:" + key, source != null ? "shadowed" : "selected", "set (value never shown)", "env $" + key));
                if (source == null) {
                    credential = CredentialProvider.of(value);
                    source = "env:" + key;
                }
            } else {
                steps.add(new Step("env:" + key, "absent", "not set", "env $" + key));
            }
        }

        if (definition != null && definition.placeholderKey() != null) {
            steps.add(new Step("placeholder", source != null ? "shadowed" : "selected", "preset default for keyless " + canonical + " servers",
                "local-server placeholder key"));
            if (source == null) {
                credential = CredentialProvider.of(definition.placeholderKey());
                source = "placeholder";
            }
        }
        return new Resolved(credential, source, steps, source != null, Map.of(), null);
    }

    private static Resolved resolveCloud(AccessPolicy policy, String canonical, Map<String, CredentialProvider> apiKeys, Map<String, String> environment,
                                         Map<String, String> files, Path homePath, Map<String, String> settings) {
        String entry = apiKeysSource(apiKeys, canonical);
        boolean hasEntry = entry != null;
        ChainContext ctx = ChainContext.offline(environment, homePath, files);
        Map<String, String> resolved = new LinkedHashMap<>();
        String settingError = null;
        try {
            Function<String, String> profile = policy.cloudChain() ? Chains.profileSettings(policy, ctx) : name -> null;
            resolved = Hosts.resolveSettings(policy.host(), settings, environment, canonical, profile);
        } catch (NotConfiguredError e) {
            settingError = firstLine(e.message());
        }
        ctx.withSettings(resolved);

        List<Step> steps = new ArrayList<>();
        boolean configured;
        CredentialProvider credential = hasEntry ? apiKeys.get(entry) : null;
        String source = hasEntry ? "api_keys" : null;
        if (policy.cloudChain()) {
            Map.Entry<List<Chains.Step>, Boolean> walk = Chains.explain(policy, ctx, hasEntry);
            for (Chains.Step s : walk.getKey()) {
                String label = s.kind().equals("api_keys") ? entrySource(canonical, entry) : s.source();
                steps.add(new Step(s.kind(), s.state(), s.detail(), label));
                if (source == null && s.state().equals("selected")) source = s.kind();
            }
            configured = walk.getValue();
            if (source == null && configured) {
                for (Step s : steps) if (s.state().equals("unprobed")) { source = s.kind(); break; }
            }
            if (credential == null && configured) {
                // The chain's caching provider walks the SDK order online at request time (AUTH-2/AUTH-3).
                ChainContext online = ChainContext.online(environment, homePath, ctx.clock()).withSettings(resolved);
                credential = Chains.credentialProvider(policy, online);
            }
        } else {
            // A hosted door with the ordinary key chain (vertex-express).
            steps.add(new Step("api_keys", hasEntry ? "selected" : "absent", hasEntry ? "provided (value never shown)" : "not provided",
                entrySource(canonical, entry)));
            configured = hasEntry;
            for (String key : policy.envKeys()) {
                String value = environment.get(key);
                if (value != null && !value.isEmpty()) {
                    steps.add(new Step("env:" + key, configured ? "shadowed" : "selected", "set (value never shown)", "env $" + key));
                    if (!configured) {
                        credential = CredentialProvider.of(value);
                        source = "env:" + key;
                    }
                    configured = true;
                } else {
                    steps.add(new Step("env:" + key, "absent", "not set", "env $" + key));
                }
            }
        }
        return new Resolved(credential, source, steps, configured, resolved, settingError);
    }

    static String firstLine(String text) {
        if (text == null) return "";
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    // ─── the shared explicit keys rule (AUTH-1, ratified 2026-09-09) ────

    /**
     * Select a configuration key, never its value; credential providers are
     * not invoked. Exact provider first (either spelling), else the single
     * entry whose declared non-empty env-key list is identical to the
     * target's. Duplicate spellings, several shared candidates, or a null
     * entry are configuration failures, never a fallback.
     */
    public static String apiKeysSource(Map<String, CredentialProvider> apiKeys, String provider) {
        if (apiKeys == null || apiKeys.isEmpty()) return null;
        String canonical = Registry.canonicalProvider(provider);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String key : apiKeys.keySet()) {
            if (!seen.add(Registry.canonicalProvider(key))) {
                throw new NotConfiguredError("RouterConfig(api_keys=...): duplicate spellings for '" + Registry.canonicalProvider(key) + "'; use one entry");
            }
        }
        List<String> candidates = new ArrayList<>();
        for (String key : apiKeys.keySet()) if (Registry.canonicalProvider(key).equals(canonical)) candidates.add(key);
        ProviderDefinition target = Registry.lookup(canonical);
        if (candidates.isEmpty() && target != null && !target.envKeys().isEmpty()) {
            for (String key : apiKeys.keySet()) {
                ProviderDefinition other = Registry.lookup(key);
                if (other != null && other.envKeys().equals(target.envKeys())) candidates.add(key);
            }
        }
        if (candidates.size() > 1) {
            List<String> sorted = new ArrayList<>(candidates);
            Collections.sort(sorted);
            throw new NotConfiguredError("RouterConfig(api_keys=...): ambiguous credentials for '" + canonical + "' from "
                + String.join(", ", sorted.stream().map(k -> "'" + k + "'").toList()) + "; supply one entry under '" + canonical + "' or keep only one shared entry");
        }
        if (candidates.isEmpty()) return null;
        String key = candidates.get(0);
        if (apiKeys.get(key) == null) throw new NotConfiguredError("RouterConfig(api_keys=...): empty credential under '" + key + "'; no environment fallback");
        return key;
    }

    /** AUTH-7: the source configuration key is shown when it differs from the target; the kind stays {@code api_keys}. */
    static String entrySource(String provider, String entry) {
        String source = "explicit api_keys entry";
        if (entry != null && !Registry.canonicalProvider(entry).equals(provider)) source += " (via '" + entry + "', shared env-key declarations)";
        return source;
    }

    // ─── stored logins ───

    static String expiryDetail(LocalOAuthCredential credential) {
        if (credential.expiresAt() == null) return "no recorded expiry";
        long remainingMs = credential.expiresAt() - System.currentTimeMillis();
        if (remainingMs <= 0) return "expired, " + (credential.refreshToken() != null ? "refresh token present" : "NO refresh token");
        long minutes = remainingMs / 60_000;
        long hours = minutes / 60;
        minutes = minutes % 60;
        String span = hours > 0 ? hours + "h " + String.format("%02d", minutes) + "m" : minutes + "m";
        return "fresh, expires in " + span;
    }

    static String usableState(LocalOAuthCredential credential, String detail, boolean shadowed) {
        if (detail.contains("expired") && credential.refreshToken() == null) return "absent";
        return shadowed ? "shadowed" : "selected";
    }

    static Step oauthStep(String provider, Path override, boolean shadowed) {
        boolean claude = provider.equals("claude-code");
        Path path = override != null ? override : (claude ? ClaudeCodeStore.defaultPath() : CodexStore.defaultPath());
        String source = "local OAuth credential " + path;
        LocalOAuthCredential credential = claude ? ClaudeCodeStore.read(path) : CodexStore.read(path);
        if (credential == null) return new Step("oauth-file", "absent", "missing or unreadable", source);
        String detail = expiryDetail(credential);
        return new Step("oauth-file", usableState(credential, detail, shadowed), detail, source);
    }

    /** The stored xAI login — the middle rung of oauth-unless-explicit: beaten only by an explicit entry, and itself beating env. */
    static Step xaiOauthStep(List<Path> paths, boolean shadowed) {
        XaiStore.Loaded loaded;
        try {
            loaded = XaiStore.loadWithSource(paths);
        } catch (NotConfiguredError e) {
            String checked = String.join(" or ", paths.stream().map(Path::toString).toList());
            return new Step("oauth-file", "absent", "missing or unreadable", "local OAuth credential " + checked);
        }
        String detail = expiryDetail(loaded.credential());
        return new Step("oauth-file", usableState(loaded.credential(), detail, shadowed), detail, "local OAuth credential " + loaded.path());
    }
}
