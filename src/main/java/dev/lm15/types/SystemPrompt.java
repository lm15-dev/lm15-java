package dev.lm15.types;

import java.util.List;

/**
 * {@code Request.system}: a non-empty string or a non-empty list of prompt
 * parts (INV-021, INV-024). Exactly one of the two is set.
 */
public record SystemPrompt(String text, List<Part> parts) {
    public SystemPrompt {
        if ((text == null) == (parts == null)) throw ValidationException.type("SystemPrompt is a string or a list of parts");
        if (text != null && text.isEmpty()) throw ValidationException.value("system cannot be empty");
        if (parts != null) {
            parts = Check.list(parts, "system");
            if (parts.isEmpty()) throw ValidationException.value("content sequence cannot be empty");
            for (Part p : parts) {
                if (Part.isPromptForbidden(p)) throw ValidationException.type("system parts cannot contain model/tool protocol parts");
            }
        }
    }

    public static SystemPrompt of(String text) { return new SystemPrompt(text, null); }
    public static SystemPrompt of(List<? extends Part> parts) { return new SystemPrompt(null, List.copyOf(parts)); }

    public boolean isText() { return text != null; }

    /** The parts form: a string becomes one TextPart. */
    public List<Part> asParts() { return text != null ? List.of(new TextPart(text)) : parts; }

    /** Text of a string form, or of a text-only parts form; null when a part is not text. */
    public String asText() {
        if (text != null) return text;
        for (Part p : parts) if (!(p instanceof TextPart)) return null;
        return Parts.textOf(parts);
    }
}
