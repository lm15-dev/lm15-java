package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

/** Rate limited by the provider (HTTP 429). */
public class RateLimitError extends ProviderError {
    public RateLimitError(String message) { this(message, ErrorMeta.NONE); }
    public RateLimitError(String message, ErrorMeta meta) { super(message, ErrorCode.RATE_LIMIT, meta); }
    protected RateLimitError(String message, ErrorCode code, ErrorMeta meta) { super(message, code, meta); }
}
