package dev.lm15.json;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;

/** Serializes {@link JsonValue}s. Floats print the way Python's {@code repr} does. */
public final class JsonWriter {
    private JsonWriter() {}

    public static String write(JsonValue value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, ",", ":", false);
        return sb.toString();
    }

    /** {@code json.dumps} defaults: {@code ", "} / {@code ": "} and ASCII-only output. */
    public static String writePythonStyle(JsonValue value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, ", ", ": ", true);
        return sb.toString();
    }

    static void write(JsonValue value, StringBuilder sb, String itemSep, String keySep, boolean asciiOnly) {
        switch (value) {
            case JsonNull n -> sb.append("null");
            case JsonBool b -> sb.append(b.value() ? "true" : "false");
            case JsonInt i -> sb.append(i.value().toString());
            case JsonFloat f -> sb.append(formatFloat(f.value()));
            case JsonString s -> writeString(s.value(), sb, asciiOnly);
            case JsonArray a -> {
                sb.append('[');
                Iterator<JsonValue> it = a.values().iterator();
                while (it.hasNext()) {
                    write(it.next(), sb, itemSep, keySep, asciiOnly);
                    if (it.hasNext()) sb.append(itemSep);
                }
                sb.append(']');
            }
            case JsonObject o -> {
                sb.append('{');
                Iterator<Map.Entry<String, JsonValue>> it = o.members().entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, JsonValue> e = it.next();
                    writeString(e.getKey(), sb, asciiOnly);
                    sb.append(keySep);
                    write(e.getValue(), sb, itemSep, keySep, asciiOnly);
                    if (it.hasNext()) sb.append(itemSep);
                }
                sb.append('}');
            }
        }
    }

    static void writeString(String s, StringBuilder sb, boolean asciiOnly) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || (asciiOnly && c > 0x7e)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /**
     * Python {@code repr(float)}: the shortest round-tripping digits, fixed
     * notation for decimal exponents in [-4, 16), scientific otherwise
     * ({@code 1e-05}, {@code 1.5e+16}); always a fraction or an exponent so
     * the lexeme reads back as a float.
     */
    public static String formatFloat(double d) {
        if (d == 0.0) {
            return (1.0 / d < 0) ? "-0.0" : "0.0";
        }
        // Java 19+ Double.toString yields the shortest round-tripping digits.
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(d)));
        String digits = bd.unscaledValue().toString();
        // strip trailing zeros from the digit string, adjusting the exponent
        int scale = bd.scale();
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') { end--; scale--; }
        digits = digits.substring(0, end);
        // value = digits * 10^(-scale); decimal exponent of the first digit:
        int exp10 = digits.length() - 1 - scale;
        StringBuilder sb = new StringBuilder();
        if (d < 0) sb.append('-');
        if (exp10 >= -4 && exp10 < 16) {
            if (exp10 < 0) {
                sb.append("0.");
                for (int i = 0; i < -exp10 - 1; i++) sb.append('0');
                sb.append(digits);
            } else {
                int intLen = exp10 + 1;
                if (digits.length() <= intLen) {
                    sb.append(digits);
                    for (int i = digits.length(); i < intLen; i++) sb.append('0');
                    sb.append(".0");
                } else {
                    sb.append(digits, 0, intLen).append('.').append(digits, intLen, digits.length());
                }
            }
        } else {
            sb.append(digits.charAt(0));
            if (digits.length() > 1) sb.append('.').append(digits, 1, digits.length());
            sb.append('e').append(exp10 < 0 ? '-' : '+');
            int ae = Math.abs(exp10);
            if (ae < 10) sb.append('0');
            sb.append(ae);
        }
        return sb.toString();
    }
}
