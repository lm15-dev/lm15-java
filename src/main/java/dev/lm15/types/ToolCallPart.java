package dev.lm15.types;

import dev.lm15.json.JsonObject;

import java.util.List;

/** The model requests an external computation. {@code input} is an opaque JSON object, always emitted. */
public record ToolCallPart(String id, String name, JsonObject input, List<ContinuationState> continuation) implements Part {
    public ToolCallPart {
        Check.nonEmpty(id, "ToolCallPart.id");
        Check.nonEmpty(name, "ToolCallPart.name");
        Check.jsonObject(input, "input", true);
        continuation = Check.continuation(continuation);
    }

    public ToolCallPart(String id, String name, JsonObject input) { this(id, name, input, List.of()); }

    @Override public PartType type() { return PartType.TOOL_CALL; }
    @Override public ToolCallPart withContinuation(List<ContinuationState> c) { return new ToolCallPart(id, name, input, c); }
}
