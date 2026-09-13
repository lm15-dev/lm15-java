package dev.lm15.json;

import java.math.BigInteger;
import java.util.Objects;

/** A JSON integer lexeme (no fraction, no exponent). Arbitrary precision, like the reference. */
public record JsonInt(BigInteger value) implements JsonValue {
    public JsonInt {
        Objects.requireNonNull(value, "value");
    }

    public static JsonInt of(long v) { return new JsonInt(BigInteger.valueOf(v)); }

    @Override public String typeName() { return "integer"; }
    @Override public String toString() { return value.toString(); }
}
