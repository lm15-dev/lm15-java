package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

/** The input exceeds the model's context window. */
public class ContextLengthError extends InvalidRequestError {
    public ContextLengthError(String message) { this(message, ErrorMeta.NONE); }
    public ContextLengthError(String message, ErrorMeta meta) { super(message, ErrorCode.CONTEXT_LENGTH, meta); }
    protected ContextLengthError(String message, ErrorCode code, ErrorMeta meta) { super(message, code, meta); }
}
