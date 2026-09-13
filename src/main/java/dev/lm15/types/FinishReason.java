package dev.lm15.types;

/** Closed vocabulary (spec/vocabularies.md § FinishReason). */
public enum FinishReason {
    STOP("stop"),
    LENGTH("length"),
    TOOL_CALL("tool_call"),
    CONTENT_FILTER("content_filter"),
    ERROR("error");

    private final String wire;

    FinishReason(String wire) { this.wire = wire; }

    /** The canonical JSON string. */
    public String wire() { return wire; }

    @Override public String toString() { return wire; }

    /** The value for a canonical string, or a {@code ValueError} for anything else (INV-037). */
    public static FinishReason fromWire(String value) {
        for (FinishReason v : values()) if (v.wire.equals(value)) return v;
        throw ValidationException.value("unsupported finish reason: " + value);
    }

    /** Every canonical string, in declaration order. */
    public static java.util.List<String> wires() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (FinishReason v : values()) out.add(v.wire);
        return java.util.List.copyOf(out);
    }
}
