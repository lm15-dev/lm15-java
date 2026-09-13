package dev.lm15.types;

import java.util.List;

/** A block of text. {@code text} may be empty (INV-015). */
public record TextPart(String text, List<ContinuationState> continuation) implements Part {
    public TextPart {
        Check.text(text, "TextPart.text");
        continuation = Check.continuation(continuation);
    }

    public TextPart(String text) { this(text, List.of()); }

    @Override public PartType type() { return PartType.TEXT; }
    @Override public TextPart withContinuation(List<ContinuationState> c) { return new TextPart(text, c); }
}
