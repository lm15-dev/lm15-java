package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

/** HTTP 402 — billing or quota. */
public class BillingError extends ProviderError {
    public BillingError(String message) { this(message, ErrorMeta.NONE); }
    public BillingError(String message, ErrorMeta meta) { super(message, ErrorCode.BILLING, meta); }
    protected BillingError(String message, ErrorCode code, ErrorMeta meta) { super(message, code, meta); }
}
