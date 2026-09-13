package dev.lm15.types;

import java.util.List;

/** A text fragment; {@code logprobs} covers exactly the tokens of this fragment. */
public record TextDelta(String text, int partIndex, List<TokenLogprob> logprobs) implements Delta {
    public TextDelta {
        Check.text(text, "TextDelta.text");
        Check.partIndex(partIndex);
        logprobs = Check.list(logprobs, "TextDelta.logprobs");
    }

    public TextDelta(String text, int partIndex) { this(text, partIndex, List.of()); }
    public TextDelta(String text) { this(text, 0, List.of()); }

    @Override public DeltaType type() { return DeltaType.TEXT; }
}
