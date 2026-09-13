package dev.lm15.types;

/** Closed vocabulary (spec/vocabularies.md § Role). */
public enum Role {
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
    DEVELOPER("developer");

    private final String wire;

    Role(String wire) { this.wire = wire; }

    /** The canonical JSON string. */
    public String wire() { return wire; }

    @Override public String toString() { return wire; }

    /** The value for a canonical string, or a {@code ValueError} for anything else (INV-037). */
    public static Role fromWire(String value) {
        for (Role v : values()) if (v.wire.equals(value)) return v;
        throw ValidationException.value("unsupported role: " + value);
    }

    /** Every canonical string, in declaration order. */
    public static java.util.List<String> wires() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Role v : values()) out.add(v.wire);
        return java.util.List.copyOf(out);
    }
}
