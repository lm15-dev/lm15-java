package dev.lm15.types;

/**
 * A constructor or {@code fromJson} refusal (spec/invariants.md).
 *
 * <p>Not an {@link dev.lm15.errors.LM15Error}: the reference raises Python's
 * native {@code ValueError} / {@code TypeError} here and the vet protocol
 * reports those native names, so {@link #protocolName()} answers one of the
 * two. {@link Kind#VALUE} is a well-typed but forbidden value (INV-016,
 * INV-037, INV-044); {@link Kind#TYPE} a value of the wrong shape (INV-003,
 * INV-042).
 */
public class ValidationException extends IllegalArgumentException {
    public enum Kind { VALUE, TYPE }

    private final Kind kind;

    public ValidationException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public static ValidationException value(String message) {
        return new ValidationException(Kind.VALUE, message);
    }

    public static ValidationException type(String message) {
        return new ValidationException(Kind.TYPE, message);
    }

    public Kind kind() { return kind; }

    /** The exception name the vet protocol reports: {@code ValueError} or {@code TypeError}. */
    public String protocolName() {
        return kind == Kind.VALUE ? "ValueError" : "TypeError";
    }
}
