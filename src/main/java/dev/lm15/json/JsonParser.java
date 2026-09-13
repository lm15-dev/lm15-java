package dev.lm15.json;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** A strict RFC 8259 parser that keeps object key order and number lexeme kinds. */
final class JsonParser {
    private final String text;
    private int pos;

    JsonParser(String text) {
        this.text = text;
    }

    JsonValue parseDocument() {
        skipWhitespace();
        JsonValue value = parseValue();
        skipWhitespace();
        if (pos != text.length()) {
            throw error("trailing characters after JSON document");
        }
        return value;
    }

    private JsonValue parseValue() {
        if (pos >= text.length()) throw error("unexpected end of JSON");
        char c = text.charAt(pos);
        switch (c) {
            case '{': return parseObject();
            case '[': return parseArray();
            case '"': return new JsonString(parseString());
            case 't': expectWord("true"); return JsonBool.TRUE;
            case 'f': expectWord("false"); return JsonBool.FALSE;
            case 'n': expectWord("null"); return JsonNull.INSTANCE;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                throw error("unexpected character '" + c + "'");
        }
    }

    private JsonObject parseObject() {
        pos++; // {
        LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') { pos++; return new JsonObject(members); }
        while (true) {
            skipWhitespace();
            if (peek() != '"') throw error("expected a string key");
            String key = parseString();
            skipWhitespace();
            if (peek() != ':') throw error("expected ':'");
            pos++;
            skipWhitespace();
            members.put(key, parseValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == '}') { pos++; return new JsonObject(members); }
            throw error("expected ',' or '}'");
        }
    }

    private JsonArray parseArray() {
        pos++; // [
        List<JsonValue> items = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') { pos++; return new JsonArray(items); }
        while (true) {
            skipWhitespace();
            items.add(parseValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == ']') { pos++; return new JsonArray(items); }
            throw error("expected ',' or ']'");
        }
    }

    private String parseString() {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) throw error("unterminated string");
            char c = text.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= text.length()) throw error("unterminated escape");
                char e = text.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u': {
                        if (pos + 4 > text.length()) throw error("truncated \\u escape");
                        int code;
                        try {
                            code = Integer.parseInt(text.substring(pos, pos + 4), 16);
                        } catch (NumberFormatException ex) {
                            throw error("invalid \\u escape");
                        }
                        pos += 4;
                        sb.append((char) code);
                        break;
                    }
                    default: throw error("invalid escape '\\" + e + "'");
                }
            } else if (c < 0x20) {
                throw error("control character in string");
            } else {
                sb.append(c);
            }
        }
    }

    private JsonValue parseNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        if (pos >= text.length()) throw error("invalid number");
        char c = text.charAt(pos);
        if (c == '0') {
            pos++;
        } else if (c >= '1' && c <= '9') {
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        } else {
            throw error("invalid number");
        }
        boolean isFloat = false;
        if (pos < text.length() && text.charAt(pos) == '.') {
            isFloat = true;
            pos++;
            int digits = 0;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) { pos++; digits++; }
            if (digits == 0) throw error("invalid number: no digits after '.'");
        }
        if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
            isFloat = true;
            pos++;
            if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
            int digits = 0;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) { pos++; digits++; }
            if (digits == 0) throw error("invalid number: no exponent digits");
        }
        String lexeme = text.substring(start, pos);
        if (isFloat) {
            double d = Double.parseDouble(lexeme);
            if (!Double.isFinite(d)) throw error("number out of range: " + lexeme);
            return new JsonFloat(d);
        }
        return new JsonInt(new BigInteger(lexeme));
    }

    private void expectWord(String word) {
        if (!text.startsWith(word, pos)) throw error("invalid literal");
        pos += word.length();
    }

    private char peek() {
        if (pos >= text.length()) throw error("unexpected end of JSON");
        return text.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
            else break;
        }
    }

    private JsonException error(String message) {
        return new JsonException(message + " at offset " + pos);
    }
}
