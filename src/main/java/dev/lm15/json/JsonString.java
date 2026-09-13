package dev.lm15.json;

import java.util.Objects;

/** A JSON string. */
public record JsonString(String value) implements JsonValue {
    public JsonString {
        Objects.requireNonNull(value, "value");
    }

    @Override public String typeName() { return "string"; }
    @Override public String toString() { return JsonWriter.write(this); }
}
