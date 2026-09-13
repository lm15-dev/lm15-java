package dev.lm15.registry;

import dev.lm15.types.ValidationException;

/** The wire formats lm15 speaks. A provider is a dialect plus an access policy (plus a compat preset). */
public enum DialectId {
    OPENAI_RESPONSES("openai-responses", "openai_responses"),
    OPENAI_CHAT("openai-chat", "openai_chat"),
    ANTHROPIC("anthropic", "anthropic_messages"),
    GEMINI("gemini", "gemini_generate_content");

    private final String wire;
    private final String apiFamily;

    DialectId(String wire, String apiFamily) {
        this.wire = wire;
        this.apiFamily = apiFamily;
    }

    public String wire() { return wire; }

    /** The {@code ModelInfo.api_family} value (underscore-spelled; a wire, not a door). */
    public String apiFamily() { return apiFamily; }

    @Override public String toString() { return wire; }

    public static DialectId fromWire(String value) {
        for (DialectId d : values()) if (d.wire.equals(value)) return d;
        throw ValidationException.value("unknown dialect: " + value);
    }
}
