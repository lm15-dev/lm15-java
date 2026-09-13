package dev.lm15.types;

import dev.lm15.json.JsonObject;

/** Callback view of a tool call without the part discriminator. */
public record ToolCallInfo(String id, String name, JsonObject input) {
    public ToolCallInfo {
        Check.nonEmpty(id, "ToolCallInfo.id");
        Check.nonEmpty(name, "ToolCallInfo.name");
        Check.jsonObject(input, "input", true);
    }

    public static ToolCallInfo fromPart(ToolCallPart part) { return new ToolCallInfo(part.id(), part.name(), part.input()); }
    public ToolCallPart toPart() { return new ToolCallPart(id, name, input); }
}
