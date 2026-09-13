package dev.lm15.dialects.gemini;

import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBool;
import dev.lm15.json.JsonFloat;
import dev.lm15.json.JsonInt;
import dev.lm15.json.JsonNull;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;

import java.util.List;

/**
 * Loose readers over a Gemini wire object, mirroring the reference's
 * Python idioms ({@code str(x or "")}, truthiness, {@code json.dumps}) so
 * the port answers byte for byte where the reference does.
 */
final class GeminiJson {
    private GeminiJson() {}

    /** Python truthiness of a JSON value: null, false, 0, "", [], {} are false. */
    static boolean truthy(JsonValue v) {
        if (v == null || v instanceof JsonNull) return false;
        if (v instanceof JsonBool b) return b.value();
        if (v instanceof JsonInt i) return i.value().signum() != 0;
        if (v instanceof JsonFloat f) return f.value() != 0.0;
        if (v instanceof JsonString s) return !s.value().isEmpty();
        if (v instanceof JsonArray a) return !a.isEmpty();
        if (v instanceof JsonObject o) return !o.isEmpty();
        return true;
    }

    /** Python {@code str(v)} for a JSON scalar (null → "None", booleans capitalized). */
    static String str(JsonValue v) {
        if (v == null || v instanceof JsonNull) return "None";
        if (v instanceof JsonString s) return s.value();
        if (v instanceof JsonBool b) return b.value() ? "True" : "False";
        return v.toJson();
    }

    /** Python {@code str(v or "")}: an empty string for a falsy value, else the string form. */
    static String strOrEmpty(JsonValue v) {
        return truthy(v) ? str(v) : "";
    }

    /** {@code o.get(key)} as a Python-truthy string, else {@code fallback}. */
    static String strOr(JsonObject o, String key, String fallback) {
        JsonValue v = o.get(key);
        return truthy(v) ? str(v) : fallback;
    }

    static JsonObject obj(JsonValue v) { return v instanceof JsonObject o ? o : null; }

    static JsonArray arr(JsonValue v) { return v instanceof JsonArray a ? a : null; }

    /** {@code o.get(key)} when it is an object, else null. */
    static JsonObject objectAt(JsonObject o, String key) { return o == null ? null : obj(o.get(key)); }

    /** {@code o.get(key)} when it is an array, else null. */
    static JsonArray arrayAt(JsonObject o, String key) { return o == null ? null : arr(o.get(key)); }

    /** Python {@code int(v)} for an int-valued JSON number; null for anything else (bools included). */
    static Integer intOrNull(JsonValue v) {
        if (v == null || v instanceof JsonNull || v instanceof JsonBool) return null;
        try {
            if (v instanceof JsonInt i) return i.value().intValueExact();
            if (v instanceof JsonFloat f) return f.isIntegral() ? f.toBigInteger().intValueExact() : null;
            if (v instanceof JsonString s) return Integer.parseInt(s.value().strip());
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** The Python type name of a JSON value ({@code type(x).__name__}). */
    static String pyType(JsonValue v) {
        if (v == null || v instanceof JsonNull) return "NoneType";
        if (v instanceof JsonBool) return "bool";
        if (v instanceof JsonInt) return "int";
        if (v instanceof JsonFloat) return "float";
        if (v instanceof JsonString) return "str";
        if (v instanceof JsonArray) return "list";
        return "dict";
    }

    /** Python {@code json.dumps(v, separators=(",", ":"))}: compact, ASCII-escaped. */
    static String dumpsCompact(JsonValue v) {
        String compact = Json.write(v);
        StringBuilder sb = new StringBuilder(compact.length());
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** The unmapped recorder's element (PROTOCOL.md § Unmapped recorder). */
    static void record(List<JsonValue> unmapped, String path, String type) {
        unmapped.add(Json.obj("path", path, "type", type == null || type.isEmpty() ? "<missing>" : type));
    }
}
