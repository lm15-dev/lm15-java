package dev.lm15.types;

/** Closed vocabulary (spec/vocabularies.md § VideoStatus). */
public enum VideoStatus {
    QUEUED("queued"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String wire;

    VideoStatus(String wire) { this.wire = wire; }

    /** The canonical JSON string. */
    public String wire() { return wire; }

    @Override public String toString() { return wire; }

    /** The value for a canonical string, or a {@code ValueError} for anything else (INV-037). */
    public static VideoStatus fromWire(String value) {
        for (VideoStatus v : values()) if (v.wire.equals(value)) return v;
        throw ValidationException.value("unsupported video status: " + value);
    }

    /** Every canonical string, in declaration order. */
    public static java.util.List<String> wires() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (VideoStatus v : values()) out.add(v.wire);
        return java.util.List.copyOf(out);
    }

    /** The states in which a job makes no further progress (spec/vocabularies.md VIDEO_TERMINAL_STATUSES). */
    public static final java.util.Set<VideoStatus> TERMINAL = java.util.Set.of(COMPLETED, FAILED, CANCELLED);

    public boolean isTerminal() { return TERMINAL.contains(this); }
}
