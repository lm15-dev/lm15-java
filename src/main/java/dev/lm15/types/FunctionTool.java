package dev.lm15.types;

import dev.lm15.json.Json;
import dev.lm15.json.JsonObject;

/**
 * A function tool: the schema is written by the user (api-family § Tools).
 * {@code parameters} is required-with-shape (INV-033): always emitted, an
 * explicit {@code {}} round-trips verbatim.
 */
public record FunctionTool(String name, String description, JsonObject parameters) implements Tool {
    public static final JsonObject DEFAULT_PARAMETERS = Json.obj("type", "object", "properties", JsonObject.EMPTY);

    public FunctionTool {
        Check.nonEmpty(name, "FunctionTool.name");
        Check.jsonObject(parameters, "parameters", true);
    }

    public FunctionTool(String name, String description) { this(name, description, DEFAULT_PARAMETERS); }
    public FunctionTool(String name) { this(name, null, DEFAULT_PARAMETERS); }

    @Override public String type() { return "function"; }
}
