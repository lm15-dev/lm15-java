package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

/** A network failure at the LM layer (retryable). */
public class TransportError extends LM15Error {
    public TransportError(String message) { this(message, ErrorMeta.NONE); }
    public TransportError(String message, ErrorMeta meta) { super(message, ErrorCode.TRANSPORT, meta); }
    protected TransportError(String message, ErrorCode code, ErrorMeta meta) { super(message, code, meta); }
}
