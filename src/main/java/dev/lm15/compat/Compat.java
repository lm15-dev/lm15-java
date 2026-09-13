package dev.lm15.compat;

/**
 * A server's quirks for one dialect (the resolved compat policy): each
 * dialect defines its own record ({@code OpenAIChatCompat},
 * {@code OpenAIResponsesCompat}, {@code AnthropicCompat}). Presets are data
 * tables copied from the reference (playbooks/port.md rule 2).
 */
public interface Compat {
    /** The preset name this value was resolved from, or null for a hand-built value. */
    String preset();
}
