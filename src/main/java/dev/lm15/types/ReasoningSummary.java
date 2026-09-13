package dev.lm15.types;

/** Closed vocabulary (spec/vocabularies.md § ReasoningSummary). */
public enum ReasoningSummary {
    AUTO("auto"),
    CONCISE("concise"),
    DETAILED("detailed");

    private final String wire;

    ReasoningSummary(String wire) { this.wire = wire; }

    /** The canonical JSON string. */
    public String wire() { return wire; }

    @Override public String toString() { return wire; }

    /** The value for a canonical string, or a {@code ValueError} for anything else (INV-037). */
    public static ReasoningSummary fromWire(String value) {
        for (ReasoningSummary v : values()) if (v.wire.equals(value)) return v;
        throw ValidationException.value("unsupported reasoning summary: " + value);
    }

    /** Every canonical string, in declaration order. */
    public static java.util.List<String> wires() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (ReasoningSummary v : values()) out.add(v.wire);
        return java.util.List.copyOf(out);
    }
}
