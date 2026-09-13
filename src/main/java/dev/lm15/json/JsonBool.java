package dev.lm15.json;

/** JSON {@code true} / {@code false}. Never a number (INV-003). */
public record JsonBool(boolean value) implements JsonValue {
    public static final JsonBool TRUE = new JsonBool(true);
    public static final JsonBool FALSE = new JsonBool(false);

    public static JsonBool of(boolean b) { return b ? TRUE : FALSE; }

    @Override public String typeName() { return "boolean"; }
    @Override public String toString() { return Boolean.toString(value); }
}
