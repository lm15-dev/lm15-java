package dev.lm15.dialects.openai;

import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBool;
import dev.lm15.json.JsonFloat;
import dev.lm15.json.JsonInt;
import dev.lm15.json.JsonNull;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.types.Usage;

/** Small wire-reading helpers with the reference's Python truthiness where the reference relies on it. */
final class Js {
    private Js() {}

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

    /** Python {@code str(x)} for a JSON value. */
    static String pyStr(JsonValue v) {
        if (v == null || v instanceof JsonNull) return "None";
        if (v instanceof JsonString s) return s.value();
        if (v instanceof JsonBool b) return b.value() ? "True" : "False";
        return v.toJson();
    }

    /** Python {@code str(x or "")}: a falsy value is the empty string. */
    static String strOrEmpty(JsonValue v) {
        return truthy(v) ? pyStr(v) : "";
    }

    /** {@code str(a or b or "")}. */
    static String strOrEmpty(JsonValue a, JsonValue b) {
        return truthy(a) ? pyStr(a) : strOrEmpty(b);
    }

    /** {@code str(a or b or "") or None}. */
    static String strOrNull(JsonValue a, JsonValue b) {
        String s = strOrEmpty(a, b);
        return s.isEmpty() ? null : s;
    }

    static String strOrNull(JsonValue a) {
        String s = strOrEmpty(a);
        return s.isEmpty() ? null : s;
    }

    /** {@code o.get(key)} as a Python-style string-or-empty. */
    static String str(JsonObject o, String key) {
        return strOrEmpty(o == null ? null : o.get(key));
    }

    /** The object member when it is an object, else an empty object (the reference's {@code x.get(k) or {}} idiom). */
    static JsonObject obj(JsonObject o, String key) {
        if (o == null) return JsonObject.EMPTY;
        JsonValue v = o.get(key);
        return v instanceof JsonObject j ? j : JsonObject.EMPTY;
    }

    /** The array member when it is an array, else empty. */
    static JsonArray arr(JsonObject o, String key) {
        if (o == null) return JsonArray.EMPTY;
        JsonValue v = o.get(key);
        return v instanceof JsonArray a ? a : JsonArray.EMPTY;
    }

    /** An int-valued counter or null (INV-029: never invent zero). */
    static Integer intOrNull(JsonValue v) {
        if (v == null || v instanceof JsonNull || v instanceof JsonBool) return null;
        if (v instanceof JsonInt i) return i.value().intValueExact();
        if (v instanceof JsonFloat f && f.isIntegral()) return f.toBigInteger().intValueExact();
        if (v instanceof JsonString s) {
            try {
                return Integer.parseInt(s.value().strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static Integer intOrNull(JsonObject o, String key) {
        return o == null ? null : intOrNull(o.get(key));
    }

    /** {@code int(payload.get("output_index", 0) or 0)}. */
    static int outputIndex(JsonObject payload) {
        Integer i = intOrNull(payload.get("output_index"));
        return i == null ? 0 : i;
    }

    /** The Python type name of a JSON value, for unmapped shape failures. */
    static String typeName(JsonValue v) {
        if (v == null || v instanceof JsonNull) return "NoneType";
        if (v instanceof JsonBool) return "bool";
        if (v instanceof JsonInt) return "int";
        if (v instanceof JsonFloat) return "float";
        if (v instanceof JsonString) return "str";
        if (v instanceof JsonArray) return "list";
        return "dict";
    }

    /** Python {@code json.dumps(value, separators=(",", ":"))}: compact, ASCII-escaped. */
    static String dumpsCompact(JsonValue value) {
        String compact = Json.write(value);
        StringBuilder sb = new StringBuilder(compact.length());
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Usage from a Responses-shaped usage object ({@code input_tokens_details} / {@code output_tokens_details}); null when absent. */
    static Usage usageFrom(JsonObject usageData) {
        if (usageData == null) return null;
        JsonObject in = obj(usageData, "input_tokens_details");
        JsonObject out = obj(usageData, "output_tokens_details");
        return new Usage(intOrNull(usageData, "input_tokens"), intOrNull(usageData, "output_tokens"), intOrNull(usageData, "total_tokens"),
            intOrNull(in, "cached_tokens"), intOrNull(in, "cache_write_tokens"), intOrNull(out, "reasoning_tokens"),
            intOrNull(in, "audio_tokens"), intOrNull(out, "audio_tokens"));
    }

    /** Usage from a Realtime {@code response.done} payload (the {@code *_token_details} spelling first); null when absent. */
    static Usage liveUsageFrom(JsonObject response) {
        JsonValue u = response == null ? null : response.get("usage");
        if (!(u instanceof JsonObject usageData)) return null;
        JsonObject in = truthy(usageData.get("input_token_details")) ? obj(usageData, "input_token_details") : obj(usageData, "input_tokens_details");
        JsonObject out = truthy(usageData.get("output_token_details")) ? obj(usageData, "output_token_details") : obj(usageData, "output_tokens_details");
        return new Usage(intOrNull(usageData, "input_tokens"), intOrNull(usageData, "output_tokens"), intOrNull(usageData, "total_tokens"),
            intOrNull(in, "cached_tokens"), intOrNull(in, "cache_write_tokens"), intOrNull(out, "reasoning_tokens"),
            intOrNull(in, "audio_tokens"), intOrNull(out, "audio_tokens"));
    }
}
