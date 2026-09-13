package dev.lm15.types;

/** Closed vocabulary (spec/vocabularies.md § ErrorCode). */
public enum ErrorCode {
    AUTH("auth"),
    BILLING("billing"),
    RATE_LIMIT("rate_limit"),
    INVALID_REQUEST("invalid_request"),
    CONTEXT_LENGTH("context_length"),
    TIMEOUT("timeout"),
    SERVER("server"),
    UNSUPPORTED_MODEL("unsupported_model"),
    UNSUPPORTED_FEATURE("unsupported_feature"),
    NOT_CONFIGURED("not_configured"),
    UNKNOWN_MODEL("unknown_model"),
    AMBIGUOUS_MODEL("ambiguous_model"),
    TRANSPORT("transport"),
    LOCK_TIMEOUT("lock_timeout"),
    STREAM_ASSEMBLY("stream_assembly"),
    PROVIDER("provider");

    private final String wire;

    ErrorCode(String wire) { this.wire = wire; }

    /** The canonical JSON string. */
    public String wire() { return wire; }

    @Override public String toString() { return wire; }

    /** The value for a canonical string, or a {@code ValueError} for anything else (INV-037). */
    public static ErrorCode fromWire(String value) {
        for (ErrorCode v : values()) if (v.wire.equals(value)) return v;
        throw ValidationException.value("unsupported error code: " + value);
    }

    /** Every canonical string, in declaration order. */
    public static java.util.List<String> wires() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (ErrorCode v : values()) out.add(v.wire);
        return java.util.List.copyOf(out);
    }
}
