package dev.lm15.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** An immutable JSON array. */
public record JsonArray(List<JsonValue> values) implements JsonValue, Iterable<JsonValue> {
    public static final JsonArray EMPTY = new JsonArray(List.of());

    public JsonArray {
        Objects.requireNonNull(values, "values");
        for (JsonValue v : values) Objects.requireNonNull(v, "array element");
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static JsonArray of(JsonValue... items) { return new JsonArray(List.of(items)); }

    public int size() { return values.size(); }
    public boolean isEmpty() { return values.isEmpty(); }
    public JsonValue get(int index) { return values.get(index); }

    @Override public Iterator<JsonValue> iterator() { return values.iterator(); }
    @Override public String typeName() { return "array"; }
    @Override public String toString() { return JsonWriter.write(this); }
}
