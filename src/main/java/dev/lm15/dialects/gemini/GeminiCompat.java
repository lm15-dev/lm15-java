package dev.lm15.dialects.gemini;

import dev.lm15.compat.Compat;

/**
 * The Gemini dialect takes no compat: GenerateContent has one server shape
 * (the cloud doors differ only by host, handled by {@code Hosts}). The
 * record exists so every dialect resolves to a {@link Compat} value.
 */
public record GeminiCompat(String preset) implements Compat {
    public static final GeminiCompat DEFAULT = new GeminiCompat("gemini");
}
