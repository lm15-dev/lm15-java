package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

/** The feature is not supported by this provider adapter (or the wire has no slot for the field; MAP-5..10). */
public class UnsupportedFeatureError extends CapabilityError {
    public UnsupportedFeatureError(String message) { this(message, ErrorMeta.NONE); }
    public UnsupportedFeatureError(String message, ErrorMeta meta) { super(message, ErrorCode.UNSUPPORTED_FEATURE, meta); }
    protected UnsupportedFeatureError(String message, ErrorCode code, ErrorMeta meta) { super(message, code, meta); }
}
