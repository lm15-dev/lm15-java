package dev.lm15;

import dev.lm15.registry.Registry;

/**
 * Anthropic Messages API (playbooks/api-family.md § Providers, direct). A named constructor
 * over {@link ProviderLM}: {@code AnthropicLM.create(apiKey)} for a key,
 * {@code AnthropicLM.builder()} for a credential value or provider, a base URL,
 * host settings, a compat preset or a transport.
 */
public final class AnthropicLM {
    private AnthropicLM() {}

    public static final String PROVIDER = "anthropic";

    /** A builder bound to the {@code anthropic} registry definition. */
    public static ProviderLM.Builder builder() { return ProviderLM.builder(Registry.require(PROVIDER)); }

    /** The adapter with a static API key (the {@code ApiKey} shorthand); normally ANTHROPIC_API_KEY. */
    public static ProviderLM create(String apiKey) { return builder().apiKey(apiKey).build(); }
}
