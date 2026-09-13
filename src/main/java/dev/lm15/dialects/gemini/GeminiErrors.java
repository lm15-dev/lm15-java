package dev.lm15.dialects.gemini;

import dev.lm15.errors.AuthError;
import dev.lm15.errors.BillingError;
import dev.lm15.errors.ContextLengthError;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.Errors;
import dev.lm15.errors.InvalidRequestError;
import dev.lm15.errors.ProviderError;
import dev.lm15.errors.RateLimitError;
import dev.lm15.errors.ServerError;
import dev.lm15.errors.TimeoutError;
import dev.lm15.errors.UnsupportedModelError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.types.ErrorDetail;
import dev.lm15.wire.BuildContext;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** The Gemini error code table (google.rpc.Status names → classes) and the two normalizers built on it. */
final class GeminiErrors {
    private GeminiErrors() {}

    /** {@code error.status} → class (copied as data from the reference's {@code _error_status_map}). */
    static final Map<String, BiFunction<String, ErrorMeta, ProviderError>> STATUS_MAP = Map.of(
        "INVALID_ARGUMENT", InvalidRequestError::new,
        "FAILED_PRECONDITION", BillingError::new,
        "PERMISSION_DENIED", AuthError::new,
        "UNAUTHENTICATED", AuthError::new,
        "NOT_FOUND", InvalidRequestError::new,
        "RESOURCE_EXHAUSTED", RateLimitError::new,
        "INTERNAL", ServerError::new,
        "UNAVAILABLE", ServerError::new,
        "DEADLINE_EXCEEDED", TimeoutError::new);

    static boolean isContextLengthMessage(String message) {
        String lowered = message.toLowerCase();
        return (lowered.contains("token") && (lowered.contains("limit") || lowered.contains("exceed")))
            || lowered.contains("too long") || lowered.contains("context is too long") || lowered.contains("context length");
    }

    private static final List<String> MODEL_ERROR_MARKERS = List.of(
        "not found", "does not exist", "not exist", "not supported", "unsupported", "not available", "unknown");

    static boolean isModelError(String message) {
        String lowered = message.toLowerCase();
        if (!lowered.contains("model")) return false;
        for (String marker : MODEL_ERROR_MARKERS) if (lowered.contains(marker)) return true;
        return false;
    }

    /** The reference's {@code _provider_error}: class + message + provider/status/provider_code (env keys on AuthError). */
    static ProviderError providerError(BiFunction<String, ErrorMeta, ProviderError> ctor, String message, BuildContext cx, Integer status, String providerCode) {
        ErrorMeta meta = ErrorMeta.of(cx.provider()).withStatus(status).withProviderCode(providerCode == null || providerCode.isEmpty() ? null : providerCode);
        ProviderError error = ctor.apply(message, meta);
        if (error instanceof AuthError) return new AuthError(message, meta, cx.policy().envKeys(), null);
        return error;
    }

    /** A stream/live error envelope → ErrorDetail, refined by the same rules as the HTTP path. */
    static ErrorDetail errorDetail(BuildContext cx, String providerCode, String message) {
        BiFunction<String, ErrorMeta, ProviderError> ctor = STATUS_MAP.getOrDefault(providerCode, ProviderError::new);
        if (isContextLengthMessage(message)) ctor = ContextLengthError::new;
        else if ("NOT_FOUND".equals(providerCode) && isModelError(message)) ctor = UnsupportedModelError::new;
        ProviderError probe = ctor.apply(message, ErrorMeta.of(cx.provider()));
        String text = !message.isEmpty() ? message : (providerCode != null && !providerCode.isEmpty() ? providerCode : "provider error");
        return new ErrorDetail(probe.code(), text, providerCode == null || providerCode.isEmpty() ? "provider" : providerCode);
    }

    /** {@code str(err.get("status") or err.get("code") or "provider")} over an error envelope that may not be an object. */
    static String envelopeCode(JsonValue err) {
        if (!(err instanceof JsonObject e)) return "provider";
        if (GeminiJson.truthy(e.get("status"))) return GeminiJson.str(e.get("status"));
        if (GeminiJson.truthy(e.get("code"))) return GeminiJson.str(e.get("code"));
        return "provider";
    }

    static String envelopeMessage(JsonValue err) {
        return err instanceof JsonObject e ? GeminiJson.strOrEmpty(e.get("message")) : "";
    }

    /** An HTTP error body → the typed provider error (the reference's {@code normalize_error}). */
    static ProviderError normalize(BuildContext cx, int status, String body) {
        String msg;
        String errStatus;
        try {
            JsonValue data = Json.parse(body);
            JsonValue err = data instanceof JsonObject d ? d.get("error") : null;
            if (err == null) err = JsonObject.EMPTY;
            if (err instanceof JsonObject e) {
                JsonValue m = e.get("message");
                if (m == null || m.isNull()) msg = "";
                else if (m.isString()) msg = m.asString();
                else throw new IllegalStateException("message is not a string"); // Python: .lower() on a non-str raises → fallback branch
                errStatus = GeminiJson.strOrEmpty(e.get("status"));
            } else {
                msg = GeminiJson.str(err);
                errStatus = "";
            }
            if (isContextLengthMessage(msg)) return providerError(ContextLengthError::new, msg, cx, status, errStatus);
            if (errStatus.equals("NOT_FOUND") && isModelError(msg)) return providerError(UnsupportedModelError::new, msg, cx, status, errStatus);
            BiFunction<String, ErrorMeta, ProviderError> ctor = STATUS_MAP.get(errStatus);
            if (ctor != null) return providerError(ctor, msg, cx, status, errStatus);
            if (!errStatus.isEmpty() && !msg.contains(errStatus)) msg = msg + " (" + errStatus + ")";
        } catch (RuntimeException e) {
            String stripped = body.strip();
            msg = stripped.isEmpty() ? "HTTP " + status : stripped.substring(0, Math.min(500, stripped.length()));
            errStatus = "";
        }
        ErrorMeta meta = ErrorMeta.of(cx.provider()).withProviderCode(errStatus.isEmpty() ? null : errStatus);
        return Errors.mapHttpError(status, msg, meta, cx.policy().envKeys());
    }
}
