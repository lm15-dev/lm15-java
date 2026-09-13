package dev.lm15.types;

import java.util.List;

/** The model explicitly refused; {@code text} is non-empty (INV-016). */
public record RefusalPart(String text, List<ContinuationState> continuation) implements Part {
    public RefusalPart {
        Check.nonEmpty(text, "RefusalPart.text");
        continuation = Check.continuation(continuation);
    }

    public RefusalPart(String text) { this(text, List.of()); }

    @Override public PartType type() { return PartType.REFUSAL; }
    @Override public RefusalPart withContinuation(List<ContinuationState> c) { return new RefusalPart(text, c); }
}
