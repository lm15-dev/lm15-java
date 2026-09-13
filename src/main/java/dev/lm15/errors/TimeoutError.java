package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

/** The provider request timed out (408/504). */
public class TimeoutError extends ProviderError {
    public TimeoutError(String message) { this(message, ErrorMeta.NONE); }
    public TimeoutError(String message, ErrorMeta meta) { super(message, ErrorCode.TIMEOUT, meta); }
    protected TimeoutError(String message, ErrorCode code, ErrorMeta meta) { super(message, code, meta); }
}
