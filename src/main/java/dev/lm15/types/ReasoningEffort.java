package dev.lm15.types;

/** Closed vocabulary (spec/vocabularies.md § ReasoningEffort). */
public enum ReasoningEffort {
    OFF("off"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    private final String wire;

    ReasoningEffort(String wire) { this.wire = wire; }

    /** The canonical JSON string. */
    public String wire() { return wire; }

    @Override public String toString() { return wire; }

    /** The value for a canonical string, or a {@code ValueError} for anything else (INV-037). */
    public static ReasoningEffort fromWire(String value) {
        for (ReasoningEffort v : values()) if (v.wire.equals(value)) return v;
        throw ValidationException.value("unsupported reasoning effort: " + value);
    }

    /** Every canonical string, in declaration order. */
    public static java.util.List<String> wires() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (ReasoningEffort v : values()) out.add(v.wire);
        return java.util.List.copyOf(out);
    }
}
