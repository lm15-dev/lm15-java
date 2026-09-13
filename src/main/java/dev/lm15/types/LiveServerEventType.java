package dev.lm15.types;

/** Closed vocabulary (spec/vocabularies.md § LiveServerEventType). */
public enum LiveServerEventType {
    AUDIO("audio"),
    TEXT("text"),
    TOOL_CALL("tool_call"),
    TOOL_CALL_DELTA("tool_call_delta"),
    INTERRUPTED("interrupted"),
    TURN_END("turn_end"),
    USAGE("usage"),
    ERROR("error");

    private final String wire;

    LiveServerEventType(String wire) { this.wire = wire; }

    /** The canonical JSON string. */
    public String wire() { return wire; }

    @Override public String toString() { return wire; }

    /** The value for a canonical string, or a {@code ValueError} for anything else (INV-037). */
    public static LiveServerEventType fromWire(String value) {
        for (LiveServerEventType v : values()) if (v.wire.equals(value)) return v;
        throw ValidationException.value("unsupported live server event type: " + value);
    }

    /** Every canonical string, in declaration order. */
    public static java.util.List<String> wires() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (LiveServerEventType v : values()) out.add(v.wire);
        return java.util.List.copyOf(out);
    }
}
