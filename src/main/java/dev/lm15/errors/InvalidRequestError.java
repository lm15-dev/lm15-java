package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

/** A bad request shape or invalid provider resource (400/404/409/413/422). */
public class InvalidRequestError extends ProviderError {
    public InvalidRequestError(String message) { this(message, ErrorMeta.NONE); }
    public InvalidRequestError(String message, ErrorMeta meta) { super(message, ErrorCode.INVALID_REQUEST, meta); }
    protected InvalidRequestError(String message, ErrorCode code, ErrorMeta meta) { super(message, code, meta); }
}
