package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

/** A provider-side failure (5xx). */
public class ServerError extends ProviderError {
    public ServerError(String message) { this(message, ErrorMeta.NONE); }
    public ServerError(String message, ErrorMeta meta) { super(message, ErrorCode.SERVER, meta); }
    protected ServerError(String message, ErrorCode code, ErrorMeta meta) { super(message, code, meta); }
}
