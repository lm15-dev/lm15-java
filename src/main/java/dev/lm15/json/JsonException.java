package dev.lm15.json;

/** A JSON parse or shape failure. The vet protocol reports it under its native name. */
public class JsonException extends RuntimeException {
    public JsonException(String message) { super(message); }
    public JsonException(String message, Throwable cause) { super(message, cause); }
}
