package dev.lm15.dialects.openaichat;

import dev.lm15.errors.AuthError;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.Errors;
import dev.lm15.errors.LM15Error;
import dev.lm15.errors.ProviderError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBool;
import dev.lm15.json.JsonFloat;
import dev.lm15.json.JsonInt;
import dev.lm15.json.JsonNull;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.types.ErrorCode;
import dev.lm15.types.ErrorDetail;
import dev.lm15.wire.BuildContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The OpenAI error-envelope family shared by every Chat Completions server
 * (the reference's {@code OpenAILM._response_error_code_map},
 * {@code _stream_error_code_map}, {@code normalize_error}), copied as data.
 * Class + code + provider_code are pinned; messages are not.
 */
final class ChatErrors {
    private ChatErrors() {}

    /** A provider {@code error.code} → the canonical class (as its code) on the complete path; unknown → ServerError. */
    static final Map<String, ErrorCode> RESPONSE_ERROR_CODES;
    /** The stream error map: the response map plus the stream-only codes; unknown → ProviderError. */
    static final Map<String, ErrorCode> STREAM_ERROR_CODES;
    static final Set<String> MODEL_ERROR_CODES = Set.of("model_not_found", "model_not_available", "unsupported_model", "DeploymentNotFound");

    static {
        LinkedHashMap<String, ErrorCode> m = new LinkedHashMap<>();
        m.put("server_error", ErrorCode.SERVER);
        m.put("rate_limit_exceeded", ErrorCode.RATE_LIMIT);
        m.put("invalid_prompt", ErrorCode.INVALID_REQUEST);
        m.put("vector_store_timeout", ErrorCode.TIMEOUT);
        for (String code : List.of("invalid_image", "invalid_image_format", "invalid_base64_image", "invalid_image_url", "image_too_large",
            "image_too_small", "image_parse_error", "image_content_policy_violation", "invalid_image_mode", "image_file_too_large",
            "unsupported_image_media_type", "empty_image_file", "failed_to_download_image", "image_file_not_found")) {
            m.put(code, ErrorCode.INVALID_REQUEST);
        }
        m.put("model_not_found", ErrorCode.UNSUPPORTED_MODEL);
        m.put("model_not_available", ErrorCode.UNSUPPORTED_MODEL);
        m.put("unsupported_model", ErrorCode.UNSUPPORTED_MODEL);
        // Azure OpenAI: the model string IS the deployment name, so an unknown deployment is an unknown model (HTTP 404).
        m.put("DeploymentNotFound", ErrorCode.UNSUPPORTED_MODEL);
        RESPONSE_ERROR_CODES = Map.copyOf(m);
        LinkedHashMap<String, ErrorCode> s = new LinkedHashMap<>(m);
        s.put("context_length_exceeded", ErrorCode.CONTEXT_LENGTH);
        s.put("invalid_api_key", ErrorCode.AUTH);
        s.put("insufficient_quota", ErrorCode.BILLING);
        s.put("1113", ErrorCode.BILLING); // Z.AI insufficient balance (docs.z.ai api-code.md)
        s.put("exceeded_current_quota_error", ErrorCode.BILLING); // Moonshot: balance/quota, rides HTTP 429
        s.put("authentication_error", ErrorCode.AUTH);
        s.put("rate_limit_error", ErrorCode.RATE_LIMIT);
        STREAM_ERROR_CODES = Map.copyOf(s);
    }

    private static final List<String> MODEL_MARKERS = List.of("not found", "does not exist", "not exist", "not supported", "unsupported", "not available", "unknown");

    static boolean isModelError(String message, String... codes) {
        StringBuilder sb = new StringBuilder();
        if (message != null && !message.isEmpty()) sb.append(message);
        for (String c : codes) if (c != null && !c.isEmpty()) sb.append(' ').append(c);
        String lowered = sb.toString().toLowerCase();
        if (!lowered.contains("model")) return false;
        for (String marker : MODEL_MARKERS) if (lowered.contains(marker)) return true;
        return false;
    }

    /** The reference's {@code _provider_error}: a typed error carrying provider, provider_code and status. */
    static ProviderError providerError(BuildContext cx, ErrorCode code, String message, Integer status, String providerCode) {
        ErrorMeta meta = new ErrorMeta(cx.provider(), providerCode == null || providerCode.isEmpty() ? null : providerCode, status, null, null);
        if (code == ErrorCode.AUTH) return new AuthError(message, meta, cx.policy().envKeys(), null);
        LM15Error e = Errors.forCode(code, message, meta);
        return (ProviderError) e;
    }

    /** {@code _response_error}: an error envelope inside a 2xx body. */
    static ProviderError responseError(BuildContext cx, String code, String message) {
        ErrorCode cls = RESPONSE_ERROR_CODES.getOrDefault(code, ErrorCode.SERVER);
        String msg = !message.isEmpty() ? message : !code.isEmpty() ? code : "provider error";
        return providerError(cx, cls, msg, null, code);
    }

    /** {@code _error_detail}: the stream error event's detail. */
    static ErrorDetail errorDetail(String providerCode, String message) {
        ErrorCode cls = STREAM_ERROR_CODES.getOrDefault(providerCode, ErrorCode.PROVIDER);
        String msg = !message.isEmpty() ? message : !providerCode.isEmpty() ? providerCode : "provider error";
        return new ErrorDetail(cls, msg, providerCode.isEmpty() ? "provider" : providerCode);
    }

    /** {@code OpenAILM.normalize_error}: extract message and code from the OpenAI error shape, refine by code, fall back to the HTTP status. */
    static LM15Error normalize(BuildContext cx, int status, String body) {
        String msg;
        String providerCode;
        try {
            JsonValue data = Json.parse(body);
            if (!(data instanceof JsonObject obj)) throw new IllegalStateException("not an object");
            JsonValue errV = obj.has("error") ? obj.get("error") : JsonObject.EMPTY;
            String code = "";
            String errType = "";
            if (errV instanceof JsonObject err) {
                JsonValue m = err.get("message");
                msg = m == null ? "" : pyStr(m);
                code = truthy(err.get("code")) ? pyStr(err.get("code")) : "";
                errType = truthy(err.get("type")) ? pyStr(err.get("type")) : "";
            } else {
                msg = pyStr(errV);
            }
            providerCode = !code.isEmpty() ? code : !errType.isEmpty() ? errType : null;
            if (code.equals("context_length_exceeded")) {
                return providerError(cx, ErrorCode.CONTEXT_LENGTH, msg, status, providerCode);
            }
            if (MODEL_ERROR_CODES.contains(code) || (status == 404 && isModelError(msg, code, errType))) {
                return providerError(cx, ErrorCode.UNSUPPORTED_MODEL, msg, status, providerCode);
            }
            // Billing before rate-limit: both can ride HTTP 429, and only one is retryable.
            if (code.equals("insufficient_quota") || code.equals("1113") || errType.equals("insufficient_quota") || errType.equals("exceeded_current_quota_error")) {
                return providerError(cx, ErrorCode.BILLING, msg, status, providerCode);
            }
            if (code.equals("invalid_api_key") || errType.equals("authentication_error")) {
                return providerError(cx, ErrorCode.AUTH, msg, status, providerCode);
            }
            if (code.equals("rate_limit_exceeded") || errType.equals("rate_limit_error")) {
                return providerError(cx, ErrorCode.RATE_LIMIT, msg, status, providerCode);
            }
            if (!code.isEmpty() && !msg.contains(code)) msg = msg + " (" + code + ")";
        } catch (RuntimeException e) {
            String stripped = body == null ? "" : body.strip();
            msg = stripped.length() > 500 ? stripped.substring(0, 500) : stripped;
            if (msg.isEmpty()) msg = "HTTP " + status;
            providerCode = null;
        }
        ErrorMeta meta = ErrorMeta.of(cx.provider()).withProviderCode(providerCode);
        return Errors.mapHttpError(status, msg, meta, cx.policy().envKeys());
    }

    // ─── Python-shaped helpers shared by the readers ───

    /** Python truthiness of a JSON value (absent, null, false, 0, "", [], {} are false). */
    static boolean truthy(JsonValue v) {
        if (v == null || v instanceof JsonNull) return false;
        if (v instanceof JsonBool b) return b.value();
        if (v instanceof JsonInt i) return i.value().signum() != 0;
        if (v instanceof JsonFloat f) return f.value() != 0.0;
        if (v instanceof JsonString s) return !s.value().isEmpty();
        if (v instanceof JsonArray a) return !a.isEmpty();
        if (v instanceof JsonObject o) return !o.isEmpty();
        return true;
    }

    /** Python {@code str(value)}: a string verbatim; otherwise its rendering. */
    static String pyStr(JsonValue v) {
        if (v == null || v instanceof JsonNull) return "None";
        if (v instanceof JsonString s) return s.value();
        if (v instanceof JsonBool b) return b.value() ? "True" : "False";
        return v.toJson();
    }

    /** Python {@code type(value).__name__} for a JSON value. */
    static String pyTypeName(JsonValue v) {
        if (v == null || v instanceof JsonNull) return "NoneType";
        if (v instanceof JsonString) return "str";
        if (v instanceof JsonBool) return "bool";
        if (v instanceof JsonInt) return "int";
        if (v instanceof JsonFloat) return "float";
        if (v instanceof JsonArray) return "list";
        return "dict";
    }
}
