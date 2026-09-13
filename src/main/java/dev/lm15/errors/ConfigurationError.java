package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

/** A local SDK or provider-adapter configuration failure. */
public class ConfigurationError extends LM15Error {
    public ConfigurationError(String message) { this(message, ErrorMeta.NONE); }
    public ConfigurationError(String message, ErrorMeta meta) { super(message, ErrorCode.NOT_CONFIGURED, meta); }
    protected ConfigurationError(String message, ErrorCode code, ErrorMeta meta) { super(message, code, meta); }
}
