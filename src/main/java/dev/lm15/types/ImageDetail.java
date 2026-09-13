package dev.lm15.types;

/** Closed vocabulary (spec/vocabularies.md § ImageDetail). */
public enum ImageDetail {
    LOW("low"),
    HIGH("high"),
    AUTO("auto");

    private final String wire;

    ImageDetail(String wire) { this.wire = wire; }

    /** The canonical JSON string. */
    public String wire() { return wire; }

    @Override public String toString() { return wire; }

    /** The value for a canonical string, or a {@code ValueError} for anything else (INV-037). */
    public static ImageDetail fromWire(String value) {
        for (ImageDetail v : values()) if (v.wire.equals(value)) return v;
        throw ValidationException.value("unsupported ImagePart.detail: " + value);
    }

    /** Every canonical string, in declaration order. */
    public static java.util.List<String> wires() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (ImageDetail v : values()) out.add(v.wire);
        return java.util.List.copyOf(out);
    }
}
