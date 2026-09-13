package dev.lm15.auth;

/**
 * The single-method credential provider (AUTH-2): the adapter invokes it at
 * request-build time, once per request, and never caches the result. A
 * chain provider owns its own token caching (AUTH-3).
 */
@FunctionalInterface
public interface CredentialProvider {
    Credential get();

    /** A known value can be validated without invoking a dynamic credential source. */
    record Fixed(Credential value) implements CredentialProvider {
        public Fixed { java.util.Objects.requireNonNull(value, "credential"); }
        @Override public Credential get() { return value; }
        @Override public String toString() { return "CredentialProvider([REDACTED])"; }
    }

    static CredentialProvider of(Credential credential) { return new Fixed(credential); }

    static CredentialProvider of(String apiKey) { return of(new Credential.ApiKey(apiKey)); }
}
