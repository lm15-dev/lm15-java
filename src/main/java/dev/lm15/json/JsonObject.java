package dev.lm15.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable, insertion-ordered JSON object. Equality is by content
 * regardless of key order (the harness compares parsed JSON the same way).
 */
public record JsonObject(Map<String, JsonValue> members) implements JsonValue {
    public static final JsonObject EMPTY = new JsonObject(Map.of());

    public JsonObject {
        Objects.requireNonNull(members, "members");
        LinkedHashMap<String, JsonValue> copy = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> e : members.entrySet()) {
            Objects.requireNonNull(e.getKey(), "object key");
            Objects.requireNonNull(e.getValue(), "object value");
            copy.put(e.getKey(), e.getValue());
        }
        members = Collections.unmodifiableMap(copy);
    }

    /** The member, or {@code null} when ABSENT (a JSON null member returns {@link JsonNull}). */
    public JsonValue get(String key) { return members.get(key); }

    /** True when the key is present (even with a JSON null value). */
    public boolean has(String key) { return members.containsKey(key); }

    /** True when the key is present and its value is not JSON null. */
    public boolean hasNonNull(String key) {
        JsonValue v = members.get(key);
        return v != null && !(v instanceof JsonNull);
    }

    /** The member as a non-null value, or {@code null} when absent or JSON null. */
    public JsonValue opt(String key) {
        JsonValue v = members.get(key);
        return v instanceof JsonNull ? null : v;
    }

    public String optString(String key) {
        JsonValue v = opt(key);
        return v == null ? null : v.asString();
    }

    public JsonObject optObject(String key) {
        JsonValue v = opt(key);
        return v == null ? null : v.asObject();
    }

    public JsonArray optArray(String key) {
        JsonValue v = opt(key);
        return v == null ? null : v.asArray();
    }

    public int size() { return members.size(); }
    public boolean isEmpty() { return members.isEmpty(); }
    public Set<String> keys() { return members.keySet(); }

    /** A copy with {@code key} set (appended when new, replaced in place when present). */
    public JsonObject with(String key, JsonValue value) {
        LinkedHashMap<String, JsonValue> copy = new LinkedHashMap<>(members);
        copy.put(key, value);
        return new JsonObject(copy);
    }

    /** A copy without {@code key}. */
    public JsonObject without(String key) {
        if (!members.containsKey(key)) return this;
        LinkedHashMap<String, JsonValue> copy = new LinkedHashMap<>(members);
        copy.remove(key);
        return new JsonObject(copy);
    }

    /** A mutable builder seeded with this object's members. */
    public JsonBuilder toBuilder() { return new JsonBuilder(this); }

    @Override public String typeName() { return "object"; }
    @Override public String toString() { return JsonWriter.write(this); }
}
