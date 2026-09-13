package dev.lm15.auth;

/**
 * The single-method credential provider (AUTH-2): the adapter invokes it at
 * request-build time, once per request, and never caches the result. A
 * chain provider owns its own token caching (AUTH-3).
 */
@FunctionalInterface
public interface CredentialProvider {
    Credential get();

    static CredentialProvider of(Credential credential) { return () -> credential; }

    static CredentialProvider of(String apiKey) {
        Credential c = new Credential.ApiKey(apiKey);
        return () -> c;
    }
}
