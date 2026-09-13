package dev.lm15.types;

import java.util.List;

/**
 * A model reasoning trace. Hidden thinking is an empty {@code text} with its
 * replay state in {@code continuation} — no flag, no placeholder (MAP-7 rule 11).
 */
public record ThinkingPart(String text, List<ContinuationState> continuation) implements Part {
    public ThinkingPart {
        Check.text(text, "ThinkingPart.text");
        continuation = Check.continuation(continuation);
    }

    public ThinkingPart(String text) { this(text, List.of()); }

    @Override public PartType type() { return PartType.THINKING; }
    @Override public ThinkingPart withContinuation(List<ContinuationState> c) { return new ThinkingPart(text, c); }
}
