package dev.lm15;

import dev.lm15.registry.Registry;

/**
 * OpenAI Responses API (playbooks/api-family.md § Providers, direct). A named constructor
 * over {@link ProviderLM}: {@code OpenAILM.create(apiKey)} for a key,
 * {@code OpenAILM.builder()} for a credential value or provider, a base URL,
 * host settings, a compat preset or a transport.
 */
public final class OpenAILM {
    private OpenAILM() {}

    public static final String PROVIDER = "openai";

    /** A builder bound to the {@code openai} registry definition. */
    public static ProviderLM.Builder builder() { return ProviderLM.builder(Registry.require(PROVIDER)); }

    /** The adapter with a static API key (the {@code ApiKey} shorthand); normally OPENAI_API_KEY. */
    public static ProviderLM create(String apiKey) { return builder().apiKey(apiKey).build(); }
}
