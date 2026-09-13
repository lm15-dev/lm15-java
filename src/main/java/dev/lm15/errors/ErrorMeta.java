package dev.lm15.errors;

/** Error metadata shared by every class: provider, provider code, HTTP status, request id, retry hint. */
public record ErrorMeta(String provider, String providerCode, Integer status, String requestId, Double retryAfter) {
    public static final ErrorMeta NONE = new ErrorMeta(null, null, null, null, null);

    public static ErrorMeta of(String provider) { return new ErrorMeta(provider, null, null, null, null); }

    public static ErrorMeta of(String provider, Integer status) { return new ErrorMeta(provider, null, status, null, null); }

    public ErrorMeta withProvider(String p) { return new ErrorMeta(p, providerCode, status, requestId, retryAfter); }
    public ErrorMeta withProviderCode(String c) { return new ErrorMeta(provider, c, status, requestId, retryAfter); }
    public ErrorMeta withStatus(Integer s) { return new ErrorMeta(provider, providerCode, s, requestId, retryAfter); }
    public ErrorMeta withRequestId(String r) { return new ErrorMeta(provider, providerCode, status, r, retryAfter); }
    public ErrorMeta withRetryAfter(Double r) { return new ErrorMeta(provider, providerCode, status, requestId, r); }
}
