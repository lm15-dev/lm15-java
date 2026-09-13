package dev.lm15.types;

/** Closed vocabulary (spec/vocabularies.md § LiveClientEventType). */
public enum LiveClientEventType {
    TURN("turn"),
    AUDIO("audio"),
    IMAGE("image"),
    TEXT("text"),
    TOOL_RESULT("tool_result"),
    INTERRUPT("interrupt"),
    END_AUDIO("end_audio");

    private final String wire;

    LiveClientEventType(String wire) { this.wire = wire; }

    /** The canonical JSON string. */
    public String wire() { return wire; }

    @Override public String toString() { return wire; }

    /** The value for a canonical string, or a {@code ValueError} for anything else (INV-037). */
    public static LiveClientEventType fromWire(String value) {
        for (LiveClientEventType v : values()) if (v.wire.equals(value)) return v;
        throw ValidationException.value("unsupported live client event type: " + value);
    }

    /** Every canonical string, in declaration order. */
    public static java.util.List<String> wires() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (LiveClientEventType v : values()) out.add(v.wire);
        return java.util.List.copyOf(out);
    }
}
