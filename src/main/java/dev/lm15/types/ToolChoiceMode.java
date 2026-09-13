package dev.lm15.types;

/** Closed vocabulary (spec/vocabularies.md § ToolChoiceMode). */
public enum ToolChoiceMode {
    AUTO("auto"),
    REQUIRED("required"),
    NONE("none");

    private final String wire;

    ToolChoiceMode(String wire) { this.wire = wire; }

    /** The canonical JSON string. */
    public String wire() { return wire; }

    @Override public String toString() { return wire; }

    /** The value for a canonical string, or a {@code ValueError} for anything else (INV-037). */
    public static ToolChoiceMode fromWire(String value) {
        for (ToolChoiceMode v : values()) if (v.wire.equals(value)) return v;
        throw ValidationException.value("unsupported tool choice mode: " + value);
    }

    /** Every canonical string, in declaration order. */
    public static java.util.List<String> wires() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (ToolChoiceMode v : values()) out.add(v.wire);
        return java.util.List.copyOf(out);
    }
}
