package dev.lm15.types;

/** Structured error information carried by stream/live error events. */
public record ErrorDetail(ErrorCode code, String message, String providerCode) {
    public ErrorDetail {
        Check.required(code, "error code");
        Check.text(message, "ErrorDetail.message");
        Check.optNonEmpty(providerCode, "ErrorDetail.provider_code");
    }

    public ErrorDetail(ErrorCode code, String message) { this(code, message, null); }
}
