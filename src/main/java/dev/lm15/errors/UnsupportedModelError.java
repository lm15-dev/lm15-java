package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

/** The model is not found, unavailable, or unsupported by the provider. */
public class UnsupportedModelError extends InvalidRequestError {
    public UnsupportedModelError(String message) { this(message, ErrorMeta.NONE); }
    public UnsupportedModelError(String message, ErrorMeta meta) { super(message, ErrorCode.UNSUPPORTED_MODEL, meta); }
    protected UnsupportedModelError(String message, ErrorCode code, ErrorMeta meta) { super(message, code, meta); }
}
