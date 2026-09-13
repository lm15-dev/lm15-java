package dev.lm15.types;

/**
 * How much hidden thinking the model does (MAP-7). {@code effort} is the one
 * dial and is required; {@code thinkingBudget} a token cap on budget wires;
 * {@code summary} visibility. {@code effort=off} forbids both (INV-026).
 */
public record Reasoning(ReasoningEffort effort, Integer thinkingBudget, ReasoningSummary summary) {
    public Reasoning {
        Check.required(effort, "reasoning effort");
        Check.positive(thinkingBudget, "thinking_budget");
        if (effort == ReasoningEffort.OFF && (thinkingBudget != null || summary != null)) {
            throw ValidationException.value("Reasoning(effort='off') cannot specify thinking_budget or summary");
        }
    }

    public Reasoning(ReasoningEffort effort) { this(effort, null, null); }

    public static Reasoning of(ReasoningEffort effort) { return new Reasoning(effort); }
    public static final Reasoning OFF = new Reasoning(ReasoningEffort.OFF);

    public boolean isOff() { return effort == ReasoningEffort.OFF; }
    public Reasoning withThinkingBudget(Integer b) { return new Reasoning(effort, b, summary); }
    public Reasoning withSummary(ReasoningSummary s) { return new Reasoning(effort, thinkingBudget, s); }
}
