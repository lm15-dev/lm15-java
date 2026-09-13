package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** HTTP status → error class (spec/vocabularies.md), code ↔ class, and header-derived metadata. */
public final class Errors {
    private Errors() {}

    /** Map an HTTP status + message to a typed ProviderError; unmatched statuses are the ProviderError fallback. */
    public static ProviderError mapHttpError(int status, String message, ErrorMeta meta) {
        ErrorMeta m = (meta == null ? ErrorMeta.NONE : meta).withStatus(status);
        if (status == 401 || status == 403) return new AuthError(message, m);
        if (status == 402) return new BillingError(message, m);
        if (status == 408 || status == 504) return new TimeoutError(message, m);
        if (status == 429) return new RateLimitError(message, m);
        if (status == 400 || status == 404 || status == 409 || status == 413 || status == 422) return new InvalidRequestError(message, m);
        if (status >= 500 && status <= 599) return new ServerError(message, m);
        return new ProviderError(message, m);
    }

    public static ProviderError mapHttpError(int status, String message, ErrorMeta meta, List<String> envKeys) {
        if (status == 401 || status == 403) {
            return new AuthError(message, (meta == null ? ErrorMeta.NONE : meta).withStatus(status), envKeys, null);
        }
        return mapHttpError(status, message, meta);
    }

    /** The class for a canonical code; unknown codes are ProviderError. */
    public static LM15Error forCode(ErrorCode code, String message, ErrorMeta meta) {
        return switch (code) {
            case AUTH -> new AuthError(message, meta);
            case BILLING -> new BillingError(message, meta);
            case RATE_LIMIT -> new RateLimitError(message, meta);
            case INVALID_REQUEST -> new InvalidRequestError(message, meta);
            case CONTEXT_LENGTH -> new ContextLengthError(message, meta);
            case TIMEOUT -> new TimeoutError(message, meta);
            case SERVER -> new ServerError(message, meta);
            case UNSUPPORTED_MODEL -> new UnsupportedModelError(message, meta);
            case UNSUPPORTED_FEATURE -> new UnsupportedFeatureError(message, meta);
            case NOT_CONFIGURED -> new NotConfiguredError(message, meta);
            case UNKNOWN_MODEL -> new UnknownModelError(message, null);
            case AMBIGUOUS_MODEL -> new AmbiguousModelError(message, null, null);
            case TRANSPORT -> new TransportError(message, meta);
            case LOCK_TIMEOUT -> new LockTimeoutError(message, null, null);
            case STREAM_ASSEMBLY -> new StreamAssemblyError(message, null);
            case PROVIDER -> new ProviderError(message, meta);
        };
    }

    /** Parse an HTTP Retry-After value: delta-seconds or an HTTP-date measured from now; null when unusable. */
    public static Double retryAfterSeconds(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            double seconds = Double.parseDouble(value.strip());
            return (Double.isFinite(seconds) && seconds >= 0) ? seconds : null;
        } catch (NumberFormatException ignored) {
            // fall through to the HTTP-date form
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME);
            double delta = (when.toInstant().toEpochMilli() - System.currentTimeMillis()) / 1000.0;
            return Math.max(0.0, delta);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final List<String> REQUEST_ID_HEADERS =
        List.of("x-request-id", "request-id", "x-amzn-requestid", "x-amz-request-id", "x-ms-request-id");

    /** Fill request id and retry hint from headers when the body did not say (2026-09-11); never invent. */
    public static void attachMetadata(LM15Error error, List<Map.Entry<String, String>> headers) {
        Double hint = error.retryAfter();
        if (hint != null && !(Double.isFinite(hint) && hint >= 0)) hint = null;
        error.setRetryAfter(hint);
        String retryHeader = null;
        String requestId = null;
        if (headers != null) {
            for (Map.Entry<String, String> h : headers) {
                String name = h.getKey().toLowerCase();
                if (retryHeader == null && name.equals("retry-after")) retryHeader = h.getValue();
            }
            for (String candidate : REQUEST_ID_HEADERS) {
                for (Map.Entry<String, String> h : headers) {
                    if (h.getKey().equalsIgnoreCase(candidate) && h.getValue() != null && !h.getValue().isEmpty()) {
                        requestId = h.getValue();
                        break;
                    }
                }
                if (requestId != null) break;
            }
        }
        if (hint == null) {
            Double seconds = retryAfterSeconds(retryHeader);
            if (seconds != null) error.setRetryAfter(seconds);
        }
        if (requestId != null) error.setRequestId(requestId);
    }
}
