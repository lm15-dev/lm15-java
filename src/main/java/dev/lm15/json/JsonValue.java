package dev.lm15.json;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * A JSON value with number fidelity.
 *
 * <p>The contract treats {@code 1} and {@code 1.0} as two different values
 * (harness PROTOCOL.md: strict typed deep-equality; docs/serde-rules.md: the
 * Number rule) and requires opaque payloads to round-trip verbatim. This
 * closed sum keeps integers ({@link JsonInt}) and floats ({@link JsonFloat})
 * apart, keeps object key order, and can only hold finite, string-keyed
 * JSON — so INV-001 (strict JSON values only) holds by construction.
 *
 * <p>Every value is immutable. Containers copy on construction and expose
 * unmodifiable views.
 */
public sealed interface JsonValue permits JsonNull, JsonBool, JsonInt, JsonFloat, JsonString, JsonArray, JsonObject {

    /** The JSON type name the harness uses: null, boolean, integer, float, string, array, object. */
    String typeName();

    default boolean isNull() { return this instanceof JsonNull; }
    default boolean isBool() { return this instanceof JsonBool; }
    default boolean isInt() { return this instanceof JsonInt; }
    default boolean isFloat() { return this instanceof JsonFloat; }
    default boolean isNumber() { return this instanceof JsonInt || this instanceof JsonFloat; }
    default boolean isString() { return this instanceof JsonString; }
    default boolean isArray() { return this instanceof JsonArray; }
    default boolean isObject() { return this instanceof JsonObject; }

    /** The string, or a {@link JsonException} when this is not a string. */
    default String asString() {
        if (this instanceof JsonString s) return s.value();
        throw new JsonException("expected a string, got " + typeName());
    }

    default boolean asBool() {
        if (this instanceof JsonBool b) return b.value();
        throw new JsonException("expected a boolean, got " + typeName());
    }

    default JsonObject asObject() {
        if (this instanceof JsonObject o) return o;
        throw new JsonException("expected an object, got " + typeName());
    }

    default JsonArray asArray() {
        if (this instanceof JsonArray a) return a;
        throw new JsonException("expected an array, got " + typeName());
    }

    /** The integer value of a {@link JsonInt}, or of an integral {@link JsonFloat}. */
    default BigInteger asBigInteger() {
        if (this instanceof JsonInt i) return i.value();
        if (this instanceof JsonFloat f && f.isIntegral()) return f.toBigInteger();
        throw new JsonException("expected an integer, got " + typeName());
    }

    default long asLong() {
        return asBigInteger().longValueExact();
    }

    default int asInt() {
        return asBigInteger().intValueExact();
    }

    /** The numeric value as a double (ints convert). */
    default double asDouble() {
        if (this instanceof JsonFloat f) return f.value();
        if (this instanceof JsonInt i) return i.value().doubleValue();
        throw new JsonException("expected a number, got " + typeName());
    }

    /** Compact JSON text. */
    default String toJson() {
        return JsonWriter.write(this);
    }

    /** The empty object. */
    static JsonObject object() { return JsonObject.EMPTY; }

    /** The empty array. */
    static JsonArray array() { return JsonArray.EMPTY; }

    static JsonNull ofNull() { return JsonNull.INSTANCE; }
    static JsonBool of(boolean b) { return JsonBool.of(b); }
    static JsonInt of(long v) { return new JsonInt(BigInteger.valueOf(v)); }
    static JsonInt of(BigInteger v) { return new JsonInt(v); }
    static JsonFloat of(double v) { return new JsonFloat(v); }
    static JsonString of(String v) { return new JsonString(v); }
    static JsonArray of(List<? extends JsonValue> v) { return new JsonArray(new java.util.ArrayList<JsonValue>(v)); }
    static JsonObject of(Map<String, ? extends JsonValue> v) { return new JsonObject(new java.util.LinkedHashMap<String, JsonValue>(v)); }
}
