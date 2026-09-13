package dev.lm15.types;

import java.util.List;

/** A reference to source material; at least one of url/title/text (INV-017). */
public record CitationPart(String url, String title, String text, List<ContinuationState> continuation) implements Part {
    public CitationPart {
        Check.optNonEmpty(url, "CitationPart.url");
        Check.optNonEmpty(title, "CitationPart.title");
        Check.optNonEmpty(text, "CitationPart.text");
        if (url == null && title == null && text == null) {
            throw ValidationException.value("CitationPart requires at least one of url, title, or text");
        }
        continuation = Check.continuation(continuation);
    }

    public CitationPart(String url, String title, String text) { this(url, title, text, List.of()); }

    @Override public PartType type() { return PartType.CITATION; }
    @Override public CitationPart withContinuation(List<ContinuationState> c) { return new CitationPart(url, title, text, c); }
}
