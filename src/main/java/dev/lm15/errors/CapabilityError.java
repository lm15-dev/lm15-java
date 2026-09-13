package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

/** A local provider-adapter capability failure. */
public class CapabilityError extends LM15Error {
    public CapabilityError(String message) { this(message, ErrorMeta.NONE); }
    public CapabilityError(String message, ErrorMeta meta) { super(message, ErrorCode.UNSUPPORTED_FEATURE, meta); }
    protected CapabilityError(String message, ErrorCode code, ErrorMeta meta) { super(message, code, meta); }
}
