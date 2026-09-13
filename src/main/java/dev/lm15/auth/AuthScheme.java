package dev.lm15.auth;

import dev.lm15.types.ValidationException;

/** How a credential travels (spec/vocabularies.md AuthScheme, AUTH-10). */
public enum AuthScheme {
    BEARER("bearer"), X_API_KEY("x-api-key"), API_KEY("api-key"), QUERY_KEY("query-key"), SIGV4("sigv4");

    private final String wire;
    AuthScheme(String wire) { this.wire = wire; }
    public String wire() { return wire; }
    @Override public String toString() { return wire; }

    public static AuthScheme fromWire(String value) {
        for (AuthScheme s : values()) if (s.wire.equals(value)) return s;
        throw ValidationException.value("unknown auth_scheme: " + value);
    }
}
