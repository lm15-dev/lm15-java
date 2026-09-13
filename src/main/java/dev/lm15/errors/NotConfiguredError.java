package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

import java.util.List;

/** No API key or required provider configuration was found (AUTH-6). */
public class NotConfiguredError extends ConfigurationError {
    private final List<String> envKeys;
    private final String credentialHint;

    public NotConfiguredError(String message) { this(message, ErrorMeta.NONE, List.of(), null); }
    public NotConfiguredError(String message, ErrorMeta meta) { this(message, meta, List.of(), null); }

    public NotConfiguredError(String message, ErrorMeta meta, List<String> envKeys, String credentialHint) {
        super(Guidance.notConfigured(message, meta == null ? null : meta.provider(), envKeys, credentialHint), ErrorCode.NOT_CONFIGURED, meta);
        this.envKeys = envKeys == null ? List.of() : List.copyOf(envKeys);
        this.credentialHint = credentialHint;
    }

    public static NotConfiguredError forProvider(String provider, String message) {
        return new NotConfiguredError(message, ErrorMeta.of(provider));
    }

    public List<String> envKeys() { return envKeys; }
    public String credentialHint() { return credentialHint; }
}
