package dev.lm15.types;

/** A reasoning fragment. */
public record ThinkingDelta(String text, int partIndex) implements Delta {
    public ThinkingDelta {
        Check.text(text, "ThinkingDelta.text");
        Check.partIndex(partIndex);
    }

    public ThinkingDelta(String text) { this(text, 0); }

    @Override public DeltaType type() { return DeltaType.THINKING; }
}
