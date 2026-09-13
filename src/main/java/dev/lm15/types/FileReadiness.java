package dev.lm15.types;

/** Closed vocabulary (spec/vocabularies.md § FileReadiness). */
public enum FileReadiness {
    PENDING("pending"),
    READY("ready"),
    FAILED("failed");

    private final String wire;

    FileReadiness(String wire) { this.wire = wire; }

    /** The canonical JSON string. */
    public String wire() { return wire; }

    @Override public String toString() { return wire; }

    /** The value for a canonical string, or a {@code ValueError} for anything else (INV-037). */
    public static FileReadiness fromWire(String value) {
        for (FileReadiness v : values()) if (v.wire.equals(value)) return v;
        throw ValidationException.value("unsupported file readiness: " + value);
    }

    /** Every canonical string, in declaration order. */
    public static java.util.List<String> wires() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (FileReadiness v : values()) out.add(v.wire);
        return java.util.List.copyOf(out);
    }
}
