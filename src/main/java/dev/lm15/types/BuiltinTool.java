package dev.lm15.types;

import dev.lm15.json.JsonObject;

/** A provider-native tool (web_search, code_execution, ...); unknown names pass through. */
public record BuiltinTool(String name, JsonObject config) implements Tool {
    public BuiltinTool {
        Check.nonEmpty(name, "BuiltinTool.name");
        Check.jsonObject(config, "config", false);
    }

    public BuiltinTool(String name) { this(name, null); }

    @Override public String type() { return "builtin"; }
}
