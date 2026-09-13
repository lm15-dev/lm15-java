package dev.lm15.types;

/**
 * A typed fragment of a Part being assembled during streaming (a closed
 * sum). Every delta carries a {@code partIndex} slot (INV-006); the
 * state-only {@link ContinuationDelta} may target the message instead.
 */
public sealed interface Delta permits TextDelta, ThinkingDelta, AudioDelta, ImageDelta, ToolCallDelta, CitationDelta, ContinuationDelta {
    DeltaType type();
}
