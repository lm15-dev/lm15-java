package dev.lm15.json;

/** JSON {@code null}. */
public final class JsonNull implements JsonValue {
    public static final JsonNull INSTANCE = new JsonNull();

    private JsonNull() {}

    @Override public String typeName() { return "null"; }
    @Override public String toString() { return "null"; }
    @Override public boolean equals(Object o) { return o instanceof JsonNull; }
    @Override public int hashCode() { return 0; }
}
