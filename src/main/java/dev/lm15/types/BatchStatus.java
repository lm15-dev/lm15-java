package dev.lm15.types;

/** Closed vocabulary (spec/vocabularies.md § BatchStatus). */
public enum BatchStatus {
    QUEUED("queued"),
    RUNNING("running"),
    CANCELLING("cancelling"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    EXPIRED("expired");

    private final String wire;

    BatchStatus(String wire) { this.wire = wire; }

    /** The canonical JSON string. */
    public String wire() { return wire; }

    @Override public String toString() { return wire; }

    /** The value for a canonical string, or a {@code ValueError} for anything else (INV-037). */
    public static BatchStatus fromWire(String value) {
        for (BatchStatus v : values()) if (v.wire.equals(value)) return v;
        throw ValidationException.value("unsupported batch status: " + value);
    }

    /** Every canonical string, in declaration order. */
    public static java.util.List<String> wires() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (BatchStatus v : values()) out.add(v.wire);
        return java.util.List.copyOf(out);
    }

    /** The states in which a job makes no further progress (spec/vocabularies.md BATCH_TERMINAL_STATUSES). */
    public static final java.util.Set<BatchStatus> TERMINAL = java.util.Set.of(COMPLETED, FAILED, CANCELLED, EXPIRED);

    public boolean isTerminal() { return TERMINAL.contains(this); }
}
