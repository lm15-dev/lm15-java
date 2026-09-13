package dev.lm15.types;

/** Closed vocabulary (spec/vocabularies.md § PartType). */
public enum PartType {
    TEXT("text"),
    IMAGE("image"),
    AUDIO("audio"),
    VIDEO("video"),
    DOCUMENT("document"),
    BINARY("binary"),
    TOOL_CALL("tool_call"),
    TOOL_RESULT("tool_result"),
    THINKING("thinking"),
    REFUSAL("refusal"),
    CITATION("citation");

    private final String wire;

    PartType(String wire) { this.wire = wire; }

    /** The canonical JSON string. */
    public String wire() { return wire; }

    @Override public String toString() { return wire; }

    /** The value for a canonical string, or a {@code ValueError} for anything else (INV-037). */
    public static PartType fromWire(String value) {
        for (PartType v : values()) if (v.wire.equals(value)) return v;
        throw ValidationException.value("unsupported part type: " + value);
    }

    /** Every canonical string, in declaration order. */
    public static java.util.List<String> wires() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (PartType v : values()) out.add(v.wire);
        return java.util.List.copyOf(out);
    }
}
