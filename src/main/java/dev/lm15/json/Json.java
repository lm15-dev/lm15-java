package dev.lm15.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Entry points: parse, write, and convert plain Java values into {@link JsonValue}. */
public final class Json {
    private Json() {}

    /** Parse JSON text (RFC 8259, key order kept, last duplicate key wins). */
    public static JsonValue parse(String text) {
        return new JsonParser(text).parseDocument();
    }

    public static JsonValue parse(byte[] utf8) {
        return parse(new String(utf8, StandardCharsets.UTF_8));
    }

    /** Parse text that must be an object. */
    public static JsonObject parseObject(String text) {
        return parse(text).asObject();
    }

    /** Compact JSON text ({@code ,} and {@code :} with no spaces), non-ASCII kept verbatim. */
    public static String write(JsonValue value) {
        return JsonWriter.write(value);
    }

    /** Python {@code json.dumps} default rendering: {@code ", "} / {@code ": "} separators, ASCII-escaped. */
    public static String writePythonStyle(JsonValue value) {
        return JsonWriter.writePythonStyle(value);
    }

    public static byte[] writeBytes(JsonValue value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Convert a plain Java value. Accepts {@link JsonValue}, {@code null},
     * Boolean, Integer/Long/Short/Byte/BigInteger (integers), Float/Double
     * (floats), BigDecimal (int when scale-free, else float), CharSequence,
     * Enum (its {@code toString}), {@code Map<String, ?>} and {@code Iterable}
     * / arrays (recursively).
     */
    public static JsonValue of(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof JsonValue jv) return jv;
        if (value instanceof Boolean b) return JsonBool.of(b);
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return JsonInt.of(((Number) value).longValue());
        }
        if (value instanceof BigInteger bi) return new JsonInt(bi);
        if (value instanceof Double || value instanceof Float) return new JsonFloat(((Number) value).doubleValue());
        if (value instanceof BigDecimal bd) {
            return bd.scale() <= 0 ? new JsonInt(bd.toBigIntegerExact()) : new JsonFloat(bd.doubleValue());
        }
        if (value instanceof CharSequence cs) return new JsonString(cs.toString());
        if (value instanceof Enum<?> e) return new JsonString(e.toString());
        if (value instanceof Map<?, ?> m) {
            LinkedHashMap<String, JsonValue> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String k)) {
                    throw new JsonException("object keys must be strings");
                }
                out.put(k, of(e.getValue()));
            }
            return new JsonObject(out);
        }
        if (value instanceof Iterable<?> it) {
            List<JsonValue> out = new ArrayList<>();
            for (Object o : it) out.add(of(o));
            return new JsonArray(out);
        }
        if (value instanceof Object[] arr) {
            List<JsonValue> out = new ArrayList<>(arr.length);
            for (Object o : arr) out.add(of(o));
            return new JsonArray(out);
        }
        if (value instanceof byte[] bytes) {
            List<JsonValue> out = new ArrayList<>(bytes.length);
            for (byte b : bytes) out.add(JsonInt.of(b & 0xff));
            return new JsonArray(out);
        }
        throw new JsonException("not a JSON-compatible value: " + value.getClass().getName());
    }

    /** {@code Json.obj("a", 1, "b", "x")}: an object from alternating keys and values. */
    public static JsonObject obj(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) throw new JsonException("obj() takes key/value pairs");
        LinkedHashMap<String, JsonValue> out = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (!(keysAndValues[i] instanceof String k)) throw new JsonException("obj() keys must be strings");
            out.put(k, of(keysAndValues[i + 1]));
        }
        return new JsonObject(out);
    }

    /** An array from plain values. */
    public static JsonArray arr(Object... items) {
        List<JsonValue> out = new ArrayList<>(items.length);
        for (Object o : items) out.add(of(o));
        return new JsonArray(out);
    }

    public static JsonBuilder builder() { return new JsonBuilder(); }

    /** The omission-rule test: null, "", [] or {} (docs/serde-rules.md). */
    public static boolean isEmpty(JsonValue v) {
        return v == null
            || v instanceof JsonNull
            || (v instanceof JsonString s && s.value().isEmpty())
            || (v instanceof JsonArray a && a.isEmpty())
            || (v instanceof JsonObject o && o.isEmpty());
    }

    /** Convert back to plain Java: null, Boolean, BigInteger, Double, String, List, LinkedHashMap. */
    public static Object toJava(JsonValue v) {
        return switch (v) {
            case JsonNull n -> null;
            case JsonBool b -> b.value();
            case JsonInt i -> i.value();
            case JsonFloat f -> f.value();
            case JsonString s -> s.value();
            case JsonArray a -> {
                List<Object> out = new ArrayList<>(a.size());
                for (JsonValue item : a) out.add(toJava(item));
                yield out;
            }
            case JsonObject o -> {
                LinkedHashMap<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<String, JsonValue> e : o.members().entrySet()) out.put(e.getKey(), toJava(e.getValue()));
                yield out;
            }
        };
    }
}
