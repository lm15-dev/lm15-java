package dev.lm15.json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An insertion-ordered object builder. {@code put} accepts plain Java values
 * ({@link Json#of(Object)}); {@code putIfPresent} skips nulls, which is how
 * dialects write optional wire fields.
 */
public final class JsonBuilder {
    private final LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();

    public JsonBuilder() {}

    public JsonBuilder(JsonObject seed) {
        members.putAll(seed.members());
    }

    public JsonBuilder put(String key, JsonValue value) {
        members.put(key, value == null ? JsonNull.INSTANCE : value);
        return this;
    }

    public JsonBuilder put(String key, Object value) {
        members.put(key, Json.of(value));
        return this;
    }

    /** Put unless {@code value} is a Java null. */
    public JsonBuilder putIfPresent(String key, Object value) {
        if (value != null) members.put(key, Json.of(value));
        return this;
    }

    /** Put unless {@code value} is null, an empty string, an empty array or an empty object. */
    public JsonBuilder putIfNonEmpty(String key, Object value) {
        JsonValue v = Json.of(value);
        if (!Json.isEmpty(v)) members.put(key, v);
        return this;
    }

    public JsonBuilder putAll(JsonObject other) {
        members.putAll(other.members());
        return this;
    }

    public JsonBuilder putAll(Map<String, ? extends JsonValue> other) {
        members.putAll(other);
        return this;
    }

    public JsonBuilder remove(String key) {
        members.remove(key);
        return this;
    }

    public boolean has(String key) { return members.containsKey(key); }
    public JsonValue get(String key) { return members.get(key); }
    public boolean isEmpty() { return members.isEmpty(); }
    public List<String> keys() { return List.copyOf(members.keySet()); }

    public JsonObject build() {
        return new JsonObject(members);
    }
}
