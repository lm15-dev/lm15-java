package dev.lm15;

import dev.lm15.registry.Registry;

/**
 * Google Gemini API (playbooks/api-family.md § Providers, direct). A named constructor
 * over {@link ProviderLM}: {@code GeminiLM.create(apiKey)} for a key,
 * {@code GeminiLM.builder()} for a credential value or provider, a base URL,
 * host settings, a compat preset or a transport.
 */
public final class GeminiLM {
    private GeminiLM() {}

    public static final String PROVIDER = "gemini";

    /** A builder bound to the {@code gemini} registry definition. */
    public static ProviderLM.Builder builder() { return ProviderLM.builder(Registry.require(PROVIDER)); }

    /** The adapter with a static API key (the {@code ApiKey} shorthand); normally GEMINI_API_KEY or GOOGLE_API_KEY. */
    public static ProviderLM create(String apiKey) { return builder().apiKey(apiKey).build(); }
}
