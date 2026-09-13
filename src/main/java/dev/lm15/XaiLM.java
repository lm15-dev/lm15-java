package dev.lm15;

import dev.lm15.registry.Registry;

/**
 * xAI Grok (Chat Completions dialect; an explicit key, else the stored subscription login, else XAI_API_KEY) (playbooks/api-family.md § Providers, direct). A named constructor
 * over {@link ProviderLM}: {@code XaiLM.create(apiKey)} for a key,
 * {@code XaiLM.builder()} for a credential value or provider, a base URL,
 * host settings, a compat preset or a transport.
 */
public final class XaiLM {
    private XaiLM() {}

    public static final String PROVIDER = "xai";

    /** A builder bound to the {@code xai} registry definition. */
    public static ProviderLM.Builder builder() { return ProviderLM.builder(Registry.require(PROVIDER)); }

    /** The adapter with a static API key (the {@code ApiKey} shorthand); normally XAI_API_KEY. */
    public static ProviderLM create(String apiKey) { return builder().apiKey(apiKey).build(); }

    /** The stored subscription login (AUTH-1 oauth-unless-explicit), loaded by the adapter. */
    public static ProviderLM create() { return builder().build(); }
}
