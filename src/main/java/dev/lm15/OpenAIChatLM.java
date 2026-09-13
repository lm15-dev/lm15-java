package dev.lm15;

import dev.lm15.registry.Registry;

/**
 * OpenAI Chat Completions dialect — the wire other servers speak; {@code builder().preset("groq")} names a server's quirks and address (playbooks/api-family.md § Providers, direct). A named constructor
 * over {@link ProviderLM}: {@code OpenAIChatLM.create(apiKey)} for a key,
 * {@code OpenAIChatLM.builder()} for a credential value or provider, a base URL,
 * host settings, a compat preset or a transport.
 */
public final class OpenAIChatLM {
    private OpenAIChatLM() {}

    public static final String PROVIDER = "openai-chat";

    /** A builder bound to the {@code openai-chat} registry definition. */
    public static ProviderLM.Builder builder() { return ProviderLM.builder(Registry.require(PROVIDER)); }

    /** The adapter with a static API key (the {@code ApiKey} shorthand); normally OPENAI_API_KEY. */
    public static ProviderLM create(String apiKey) { return builder().apiKey(apiKey).build(); }
}
