package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

/**
 * Base of every lm15 error (spec/vocabularies.md § ErrorCode). The class
 * hierarchy shape is the family's; the mechanism is Java's unchecked
 * exceptions. Messages are never pinned by the contract; class and code are.
 *
 * <pre>
 * LM15Error
 * ├── TransportError
 * ├── LockTimeoutError
 * ├── StreamAssemblyError
 * ├── ConfigurationError
 * │   ├── NotConfiguredError
 * │   ├── UnknownModelError
 * │   └── AmbiguousModelError
 * ├── CapabilityError
 * │   └── UnsupportedFeatureError
 * └── ProviderError
 *     ├── AuthError
 *     ├── BillingError
 *     ├── RateLimitError
 *     ├── InvalidRequestError
 *     │   ├── ContextLengthError
 *     │   └── UnsupportedModelError
 *     ├── TimeoutError
 *     └── ServerError
 * </pre>
 */
public class LM15Error extends RuntimeException {
    private final String rawMessage;
    private final ErrorCode code;
    private final String provider;
    private final String providerCode;
    private final Integer status;
    private String requestId;
    private Double retryAfter;

    protected LM15Error(String message, ErrorCode code, ErrorMeta meta) {
        super(message == null ? "" : message);
        this.rawMessage = message == null ? "" : message;
        this.code = code;
        ErrorMeta m = meta == null ? ErrorMeta.NONE : meta;
        this.provider = m.provider();
        this.providerCode = m.providerCode();
        this.status = m.status();
        this.requestId = m.requestId();
        this.retryAfter = m.retryAfter();
    }

    /** The message field the contract pins (never decorated). */
    public String message() { return rawMessage; }

    /** The canonical ErrorCode. */
    public ErrorCode code() { return code; }

    public String provider() { return provider; }
    public String providerCode() { return providerCode; }
    public Integer status() { return status; }
    public String requestId() { return requestId; }
    /** Seconds; float-typed under the Number rule. */
    public Double retryAfter() { return retryAfter; }

    /** Filled from HTTP headers when the body did not say (2026-09-11); never replaces a body value. */
    public void setRequestId(String requestId) { if (this.requestId == null) this.requestId = requestId; }
    public void setRetryAfter(Double retryAfter) { this.retryAfter = retryAfter; }

    /** RateLimit, Timeout, Server, Transport and LockTimeout errors are safe to retry; lm15 never retries itself. */
    public boolean isRetryable() {
        return this instanceof RateLimitError || this instanceof TimeoutError || this instanceof ServerError
            || this instanceof TransportError || this instanceof LockTimeoutError;
    }

    public ErrorMeta meta() { return new ErrorMeta(provider, providerCode, status, requestId, retryAfter); }

    /** The canonical class name (what the vet protocol reports as {@code error.type}). */
    public String className() { return getClass().getSimpleName(); }
}
