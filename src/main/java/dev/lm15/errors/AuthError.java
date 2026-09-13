package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

import java.util.List;

/** Authentication failed (401/403): invalid, expired, or missing credential. */
public class AuthError extends ProviderError {
    private final List<String> envKeys;
    private final String credentialHint;

    public AuthError(String message) { this(message, ErrorMeta.NONE, List.of(), null); }
    public AuthError(String message, ErrorMeta meta) { this(message, meta, List.of(), null); }

    public AuthError(String message, ErrorMeta meta, List<String> envKeys, String credentialHint) {
        super(Guidance.auth(message, meta == null ? null : meta.provider(), envKeys, credentialHint), ErrorCode.AUTH, meta);
        this.envKeys = envKeys == null ? List.of() : List.copyOf(envKeys);
        this.credentialHint = credentialHint;
    }

    public List<String> envKeys() { return envKeys; }
    public String credentialHint() { return credentialHint; }

    /** The same error with its guidance rewritten for a subscription (OAuth) adapter. */
    public AuthError withCredentialHint(String hint) {
        return new AuthError(Guidance.stripGuidance(message()), meta(), List.of(), hint);
    }
}
