package dev.lm15.json;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {
    @Test void intsAndFloatsStayApart() {
        JsonObject o = Json.parseObject("{\"a\":1,\"b\":1.0,\"c\":1e5,\"d\":-0.0,\"e\":12345678901234567890}");
        assertInstanceOf(JsonInt.class, o.get("a"));
        assertInstanceOf(JsonFloat.class, o.get("b"));
        assertInstanceOf(JsonFloat.class, o.get("c"));
        assertEquals(new BigInteger("12345678901234567890"), ((JsonInt) o.get("e")).value());
        assertEquals("{\"a\":1,\"b\":1.0,\"c\":100000.0,\"d\":-0.0,\"e\":12345678901234567890}", o.toJson());
    }

    @Test void floatsPrintLikePythonRepr() {
        assertEquals("1e+16", JsonWriter.formatFloat(1e16));
        assertEquals("1e-05", JsonWriter.formatFloat(1e-5));
        assertEquals("0.0001", JsonWriter.formatFloat(0.0001));
        assertEquals("1.2345678901234568e+17", JsonWriter.formatFloat(123456789012345680.0));
        assertEquals("10000000.0", JsonWriter.formatFloat(1e7));
        assertEquals("0.1", JsonWriter.formatFloat(0.1));
    }

    @Test void keyOrderIsKeptAndDuplicatesLastWins() {
        JsonObject o = Json.parseObject("{\"z\":1,\"a\":2,\"z\":3}");
        assertEquals(List.of("z", "a"), List.copyOf(o.keys()));
        assertEquals(3, o.get("z").asInt());
        assertEquals("{\"z\":3,\"a\":2}", o.toJson());
    }

    @Test void stringsRoundTripEscapes() {
        String text = "{\"s\":\"a\\\"b\\\\c\\n\\u00e9\\ud83d\\ude00\"}";
        JsonObject o = Json.parseObject(text);
        assertEquals("a\"b\\c\né😀", o.get("s").asString());
        assertEquals(o, Json.parse(o.toJson()));
        assertEquals("{\"s\": \"a\\\"b\\\\c\\n\\u00e9\\ud83d\\ude00\"}", Json.writePythonStyle(o));
    }

    @Test void rejectsMalformed() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":1,}"));
        assertThrows(JsonException.class, () -> Json.parse("[1 2]"));
        assertThrows(JsonException.class, () -> Json.parse("01"));
        assertThrows(JsonException.class, () -> Json.parse("NaN"));
        assertThrows(JsonException.class, () -> Json.parse("{} x"));
    }

    @Test void objAndBuilderConvertJavaValues() {
        JsonObject o = Json.obj("i", 1, "f", 2.5, "b", true, "n", null, "l", List.of("x"), "m", java.util.Map.of("k", 1L));
        assertEquals("{\"i\":1,\"f\":2.5,\"b\":true,\"n\":null,\"l\":[\"x\"],\"m\":{\"k\":1}}", o.toJson());
        JsonObject b = new JsonBuilder().put("a", 1).putIfPresent("b", null).putIfNonEmpty("c", "").putIfNonEmpty("d", List.of()).put("e", "x").build();
        assertEquals("{\"a\":1,\"e\":\"x\"}", b.toJson());
    }

    @Test void equalityIgnoresKeyOrder() {
        assertEquals(Json.parse("{\"a\":1,\"b\":2}"), Json.parse("{\"b\":2,\"a\":1}"));
        assertNotEquals(Json.parse("{\"a\":1}"), Json.parse("{\"a\":1.0}"));
    }
}
