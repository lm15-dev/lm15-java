package dev.lm15;

import dev.lm15.registry.Registry;

/**
 * Claude subscription through the local {@code claude} CLI login (playbooks/api-family.md § Providers, direct; spec/auth.md AUTH-1
 * {@code oauth}: the stored login owns the provider — no env key, no
 * fallback). A named constructor over {@link ProviderLM}.
 */
public final class ClaudeCodeLM {
    private ClaudeCodeLM() {}

    public static final String PROVIDER = "claude-code";

    /** A builder bound to the {@code claude-code} registry definition. */
    public static ProviderLM.Builder builder() { return ProviderLM.builder(Registry.require(PROVIDER)); }

    /** The adapter over the stored local login, loaded by the adapter and refreshed per request. */
    public static ProviderLM create() { return builder().build(); }

    /** The adapter over an explicit bearer token (a credential the caller already holds). */
    public static ProviderLM create(String accessToken) { return builder().credential(new dev.lm15.auth.Credential.BearerToken(accessToken)).build(); }
}
