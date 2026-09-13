package dev.lm15.types;

/** Closed vocabulary (spec/vocabularies.md § DeltaType). */
public enum DeltaType {
    TEXT("text"),
    THINKING("thinking"),
    AUDIO("audio"),
    IMAGE("image"),
    TOOL_CALL("tool_call"),
    CITATION("citation"),
    CONTINUATION("continuation");

    private final String wire;

    DeltaType(String wire) { this.wire = wire; }

    /** The canonical JSON string. */
    public String wire() { return wire; }

    @Override public String toString() { return wire; }

    /** The value for a canonical string, or a {@code ValueError} for anything else (INV-037). */
    public static DeltaType fromWire(String value) {
        for (DeltaType v : values()) if (v.wire.equals(value)) return v;
        throw ValidationException.value("unsupported delta type: " + value);
    }

    /** Every canonical string, in declaration order. */
    public static java.util.List<String> wires() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (DeltaType v : values()) out.add(v.wire);
        return java.util.List.copyOf(out);
    }
}
