package dev.lm15.dialects.anthropic;

import dev.lm15.errors.AuthError;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.Errors;
import dev.lm15.errors.LM15Error;
import dev.lm15.json.Json;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.types.ErrorCode;
import dev.lm15.types.ErrorDetail;
import dev.lm15.wire.BuildContext;

import java.util.List;
import java.util.Map;

/**
 * Error normalization for the Messages wire (the reference's
 * {@code normalize_error} and {@code _error_detail}): the provider's error
 * type → canonical class table, the message rules for context length and
 * unknown models, and the HTTP status fallback ({@code Errors.mapHttpError}).
 * Messages are never pinned; class, code and provider_code are.
 */
final class AnthropicErrors {
    private AnthropicErrors() {}

    /** Anthropic error {@code type} (and the foreign spellings seen on this wire) → canonical code. */
    static final Map<String, ErrorCode> ERROR_TYPE_CODES = Map.ofEntries(
        Map.entry("authentication_error", ErrorCode.AUTH),
        Map.entry("permission_error", ErrorCode.AUTH),
        Map.entry("billing_error", ErrorCode.BILLING),
        Map.entry("rate_limit_error", ErrorCode.RATE_LIMIT),
        Map.entry("request_too_large", ErrorCode.INVALID_REQUEST),
        Map.entry("not_found_error", ErrorCode.INVALID_REQUEST),
        Map.entry("resource_not_found_error", ErrorCode.INVALID_REQUEST), // Moonshot's Anthropic wire
        Map.entry("DeploymentNotFound", ErrorCode.UNSUPPORTED_MODEL),     // Azure Foundry: top-level code, model string = deployment
        Map.entry("invalid_authentication_error", ErrorCode.AUTH),       // Moonshot's Anthropic wire
        Map.entry("invalid_request_error", ErrorCode.INVALID_REQUEST),
        Map.entry("api_error", ErrorCode.SERVER),
        Map.entry("overloaded_error", ErrorCode.SERVER),
        Map.entry("timeout_error", ErrorCode.TIMEOUT));

    static boolean isContextLengthMessage(String msg) {
        String lowered = msg.toLowerCase();
        return lowered.contains("prompt is too long")
            || lowered.contains("too many tokens")
            || lowered.contains("context window")
            || lowered.contains("context length")
            || (lowered.contains("token") && (lowered.contains("limit") || lowered.contains("exceed")));
    }

    private static final List<String> MODEL_ERROR_MARKERS = List.of(
        "not found", "does not exist", "not exist", "not supported", "unsupported", "not available", "unknown");

    static boolean isModelError(String message) {
        String lowered = message.toLowerCase();
        if (!lowered.contains("model")) return false;
        for (String marker : MODEL_ERROR_MARKERS) if (lowered.contains(marker)) return true;
        return false;
    }

    /** The stream-error detail: class by the type table, refined by the message rules. */
    static ErrorDetail errorDetail(String providerCode, String message) {
        ErrorCode code = ERROR_TYPE_CODES.getOrDefault(providerCode, ErrorCode.PROVIDER);
        if (isContextLengthMessage(message)) code = ErrorCode.CONTEXT_LENGTH;
        else if (providerCode.equals("not_found_error") && isModelError(message)) code = ErrorCode.UNSUPPORTED_MODEL;
        String text = !message.isEmpty() ? message : !providerCode.isEmpty() ? providerCode : "provider error";
        return new ErrorDetail(code, text, providerCode.isEmpty() ? "provider" : providerCode);
    }

    private static LM15Error typed(BuildContext cx, ErrorCode code, String message, int status, String providerCode, String requestId) {
        ErrorMeta meta = new ErrorMeta(cx.provider(), emptyToNull(providerCode), status, emptyToNull(requestId), null);
        if (code == ErrorCode.AUTH) return new AuthError(message, meta, cx.policy().envKeys(), null);
        return Errors.forCode(code, message, meta);
    }

    private static String emptyToNull(String s) { return s == null || s.isEmpty() ? null : s; }

    static LM15Error normalize(BuildContext cx, int status, String body) {
        String msg;
        String errType;
        String requestId;
        try {
            JsonValue data = Json.parse(body);
            JsonValue inner = data instanceof JsonObject d ? d.get("error") : null;
            // Anthropic's envelope nests under `error`; Azure Foundry's gateway
            // uses top-level {code, message} before a deployment is reached.
            // Keep both shapes on one wire.
            JsonValue err = (inner instanceof JsonObject || inner instanceof JsonString) ? inner : data instanceof JsonObject ? data : JsonObject.EMPTY;
            if (err instanceof JsonObject e) {
                JsonValue m = e.get("message");
                // A present non-string message is not readable as text (the
                // reference fails on `.lower()` and falls to the raw-body branch).
                if (m != null && !(m instanceof JsonString)) throw new IllegalStateException("message is not a string");
                msg = m == null ? "" : m.asString();
                String t = AnthropicResponses.truthyString(e, "type");
                if (t == null) t = AnthropicResponses.truthyString(e, "code");
                errType = t == null ? "" : t;
            } else {
                msg = err.asString();
                errType = "";
            }
            String rid = data instanceof JsonObject d ? AnthropicResponses.truthyString(d, "request_id") : null;
            requestId = rid == null ? "" : rid;

            if (isContextLengthMessage(msg)) {
                return typed(cx, ErrorCode.CONTEXT_LENGTH, msg, status, errType, requestId);
            }
            // `resource_not_found_error` is Moonshot's spelling of the same 404 on
            // its Anthropic wire; the message rule decides, as on the chat wire.
            if (errType.equals("DeploymentNotFound")
                || ((errType.equals("not_found_error") || errType.equals("resource_not_found_error")) && isModelError(msg))) {
                return typed(cx, ErrorCode.UNSUPPORTED_MODEL, msg, status, errType, requestId);
            }
            ErrorCode code = ERROR_TYPE_CODES.get(errType);
            if (code != null) return typed(cx, code, msg, status, errType, requestId);
            if (!errType.isEmpty() && !msg.contains(errType)) msg = msg + " (" + errType + ")";
        } catch (RuntimeException e) {
            String stripped = body.strip();
            msg = stripped.length() > 500 ? stripped.substring(0, 500) : stripped;
            if (msg.isEmpty()) msg = "HTTP " + status;
            errType = "";
            requestId = "";
        }
        ErrorMeta meta = new ErrorMeta(cx.provider(), emptyToNull(errType), null, emptyToNull(requestId), null);
        return Errors.mapHttpError(status, msg, meta, cx.policy().envKeys());
    }
}
