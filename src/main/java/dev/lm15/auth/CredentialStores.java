package dev.lm15.auth;

import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.NotConfiguredError;

import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Loading a stored subscription credential, keyed by provider (AUTH-1 oauth
 * policies, AUTH-8 borrowed files). The policy says THAT a login is used;
 * the loader registered here says HOW. The auth module registers the three
 * loaders (claude-code, openai-codex, xai) and the xai offline probe.
 */
public final class CredentialStores {
    private CredentialStores() {}

    private static final java.util.Map<String, Function<Path, LoadedCredential>> LOADERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Supplier<Boolean>> PROBES = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        // The auth module registers the claude-code / openai-codex / xai loaders and the xai probe.
        AuthModule.init();
    }

    public static void registerLoader(String provider, Function<Path, LoadedCredential> loader) { LOADERS.put(provider, loader); }
    public static void registerProbe(String provider, Supplier<Boolean> probe) { PROBES.put(provider, probe); }

    /** An explicit credential always wins; otherwise a stored-login policy loads; a key policy with none is not configured. */
    public static LoadedCredential load(AccessPolicy policy, CredentialProvider explicit, Path credentialsPath) {
        if (explicit != null) return LoadedCredential.explicit(explicit);
        Function<Path, LoadedCredential> loader = policy.credentialPolicy() == CredentialPolicy.KEY ? null : LOADERS.get(policy.provider());
        if (loader != null) return loader.apply(credentialsPath);
        String fix = policy.envKeys().isEmpty() ? "; pass api_key=" : "; set " + String.join(" or ", policy.envKeys()) + " or pass api_key=";
        throw new NotConfiguredError(policy.provider() + ": no credential given" + fix, ErrorMeta.of(policy.provider()), policy.envKeys(), policy.loginHint());
    }

    /** Offline probe for the router's oauth-unless-explicit chain: is a usable login stored locally? */
    public static boolean hasStoredCredential(AccessPolicy policy) {
        Supplier<Boolean> probe = PROBES.get(policy.provider());
        return probe != null && Boolean.TRUE.equals(probe.get());
    }
}
