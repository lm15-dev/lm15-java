package dev.lm15.types;

/** A citation fragment; at least one of text/url/title (INV-019). */
public record CitationDelta(String text, String url, String title, int partIndex) implements Delta {
    public CitationDelta {
        Check.partIndex(partIndex);
        if (text == null && url == null && title == null) {
            throw ValidationException.value("CitationDelta requires at least one of text, url, or title");
        }
    }

    @Override public DeltaType type() { return DeltaType.CITATION; }
}
