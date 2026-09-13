package dev.lm15.types;

/** A tool-call input fragment (raw JSON text), optionally carrying the call's identity. */
public record ToolCallDelta(String input, int partIndex, String id, String name) implements Delta {
    public ToolCallDelta {
        Check.text(input, "ToolCallDelta.input");
        Check.partIndex(partIndex);
        Check.optNonEmpty(id, "ToolCallDelta.id");
        Check.optNonEmpty(name, "ToolCallDelta.name");
    }

    public ToolCallDelta(String input, int partIndex) { this(input, partIndex, null, null); }

    @Override public DeltaType type() { return DeltaType.TOOL_CALL; }
}
