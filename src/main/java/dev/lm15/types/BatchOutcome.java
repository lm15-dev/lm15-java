package dev.lm15.types;

/** Closed vocabulary (spec/vocabularies.md § BatchOutcome). */
public enum BatchOutcome {
    SUCCEEDED("succeeded"),
    ERRORED("errored"),
    CANCELLED("cancelled"),
    EXPIRED("expired");

    private final String wire;

    BatchOutcome(String wire) { this.wire = wire; }

    /** The canonical JSON string. */
    public String wire() { return wire; }

    @Override public String toString() { return wire; }

    /** The value for a canonical string, or a {@code ValueError} for anything else (INV-037). */
    public static BatchOutcome fromWire(String value) {
        for (BatchOutcome v : values()) if (v.wire.equals(value)) return v;
        throw ValidationException.value("unsupported batch outcome: " + value);
    }

    /** Every canonical string, in declaration order. */
    public static java.util.List<String> wires() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (BatchOutcome v : values()) out.add(v.wire);
        return java.util.List.copyOf(out);
    }
}
