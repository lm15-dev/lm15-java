package dev.lm15.json;

import java.math.BigDecimal;
import java.math.BigInteger;

/** A JSON float lexeme (a fraction or an exponent). Always finite (INV-001). */
public record JsonFloat(double value) implements JsonValue {
    public JsonFloat {
        if (!Double.isFinite(value)) {
            throw new JsonException("JSON numbers must be finite");
        }
    }

    /** True when the value has no fractional part (so it may coerce to an int field, INV-007). */
    public boolean isIntegral() {
        return value == Math.rint(value);
    }

    public BigInteger toBigInteger() {
        return new BigDecimal(value).toBigInteger();
    }

    @Override public String typeName() { return "float"; }
    @Override public String toString() { return JsonWriter.formatFloat(value); }
}
