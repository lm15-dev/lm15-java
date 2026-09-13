package dev.lm15.types;

import dev.lm15.json.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Shared validation primitives for the canonical types (spec/invariants.md). */
final class Check {
    private Check() {}

    static String text(String value, String field) {
        if (value == null) throw ValidationException.type(field + " must be a string");
        return value;
    }

    static String nonEmpty(String value, String field) {
        text(value, field);
        if (value.isEmpty()) throw ValidationException.value(field + " cannot be empty");
        return value;
    }

    static String optNonEmpty(String value, String field) {
        if (value != null && value.isEmpty()) throw ValidationException.value(field + " cannot be empty");
        return value;
    }

    static Integer positive(Integer value, String field) {
        if (value != null && value <= 0) throw ValidationException.value(field + " must be > 0");
        return value;
    }

    static Integer nonNegative(Integer value, String field) {
        if (value != null && value < 0) throw ValidationException.value(field + " must be >= 0");
        return value;
    }

    static Long nonNegative(Long value, String field) {
        if (value != null && value < 0) throw ValidationException.value(field + " must be >= 0");
        return value;
    }

    static int partIndex(int value) {
        if (value < 0) throw ValidationException.value("part_index must be >= 0");
        return value;
    }

    static JsonObject jsonObject(JsonObject value, String field, boolean required) {
        if (value == null && required) throw ValidationException.type(field + " must be a JSON object");
        return value;
    }

    /** INV-004: an empty {@code extensions} normalizes to absent. */
    static JsonObject extensions(JsonObject value) {
        if (value != null && value.isEmpty()) return null;
        return value;
    }

    static <T> List<T> list(Collection<? extends T> value, String field) {
        if (value == null) return List.of();
        for (T item : value) {
            if (item == null) throw ValidationException.type(field + " cannot contain null");
        }
        return List.copyOf(new ArrayList<>(value));
    }

    static List<ContinuationState> continuation(List<ContinuationState> value) {
        return list(value, "continuation");
    }

    static List<String> nonEmptyStrings(Collection<String> value, String field, String message) {
        List<String> out = list(value, field);
        for (String s : out) {
            if (s.isEmpty()) throw ValidationException.value(message);
        }
        return out;
    }

    static <E extends Enum<E>> E required(E value, String field) {
        if (value == null) throw ValidationException.value("unsupported " + field + ": null");
        return value;
    }
}
