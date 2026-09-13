package dev.lm15.types;

/** Closed vocabulary (spec/vocabularies.md § CacheRetention). */
public enum CacheRetention {
    SHORT("short"),
    LONG("long");

    private final String wire;

    CacheRetention(String wire) { this.wire = wire; }

    /** The canonical JSON string. */
    public String wire() { return wire; }

    @Override public String toString() { return wire; }

    /** The value for a canonical string, or a {@code ValueError} for anything else (INV-037). */
    public static CacheRetention fromWire(String value) {
        for (CacheRetention v : values()) if (v.wire.equals(value)) return v;
        throw ValidationException.value("unsupported cache retention: " + value);
    }

    /** Every canonical string, in declaration order. */
    public static java.util.List<String> wires() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (CacheRetention v : values()) out.add(v.wire);
        return java.util.List.copyOf(out);
    }
}
