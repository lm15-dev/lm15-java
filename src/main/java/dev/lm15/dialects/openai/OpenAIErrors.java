package dev.lm15.dialects.openai;

import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.Errors;
import dev.lm15.errors.LM15Error;
import dev.lm15.json.Json;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.types.ErrorCode;
import dev.lm15.types.ErrorDetail;
import dev.lm15.wire.BuildContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The OpenAI error envelopes → typed errors (the reference's code tables, copied as data). */
final class OpenAIErrors {
    private OpenAIErrors() {}

    static final String MODEL_LIST_HINT = "List the models your subscription accepts: call .list_models() on this client.";

    /** Codes seen inside a 2xx body's {@code error} object (the complete path); the fallback is ServerError. */
    static final Map<String, ErrorCode> RESPONSE_ERROR_CODE_MAP;
    /** Codes seen on stream error frames; the fallback is ProviderError. */
    static final Map<String, ErrorCode> STREAM_ERROR_CODE_MAP;
    static final Set<String> MODEL_ERROR_CODES = Set.of("model_not_found", "model_not_available", "unsupported_model", "DeploymentNotFound");

    static {
        LinkedHashMap<String, ErrorCode> r = new LinkedHashMap<>();
        r.put("server_error", ErrorCode.SERVER);
        r.put("rate_limit_exceeded", ErrorCode.RATE_LIMIT);
        r.put("invalid_prompt", ErrorCode.INVALID_REQUEST);
        r.put("vector_store_timeout", ErrorCode.TIMEOUT);
        for (String code : List.of("invalid_image", "invalid_image_format", "invalid_base64_image", "invalid_image_url", "image_too_large",
            "image_too_small", "image_parse_error", "image_content_policy_violation", "invalid_image_mode", "image_file_too_large",
            "unsupported_image_media_type", "empty_image_file", "failed_to_download_image", "image_file_not_found")) {
            r.put(code, ErrorCode.INVALID_REQUEST);
        }
        r.put("model_not_found", ErrorCode.UNSUPPORTED_MODEL);
        r.put("model_not_available", ErrorCode.UNSUPPORTED_MODEL);
        r.put("unsupported_model", ErrorCode.UNSUPPORTED_MODEL);
        // Azure OpenAI: the model string IS the deployment name, so an unknown deployment is an unknown model.
        r.put("DeploymentNotFound", ErrorCode.UNSUPPORTED_MODEL);
        RESPONSE_ERROR_CODE_MAP = Map.copyOf(r);

        LinkedHashMap<String, ErrorCode> s = new LinkedHashMap<>(r);
        s.put("context_length_exceeded", ErrorCode.CONTEXT_LENGTH);
        s.put("invalid_api_key", ErrorCode.AUTH);
        s.put("insufficient_quota", ErrorCode.BILLING);
        s.put("1113", ErrorCode.BILLING); // Z.AI insufficient balance
        s.put("exceeded_current_quota_error", ErrorCode.BILLING); // Moonshot: balance/quota, rides HTTP 429
        s.put("authentication_error", ErrorCode.AUTH);
        s.put("rate_limit_error", ErrorCode.RATE_LIMIT);
        STREAM_ERROR_CODE_MAP = Map.copyOf(s);
    }

    static boolean isModelError(String message, String... codes) {
        StringBuilder sb = new StringBuilder();
        if (message != null && !message.isEmpty()) sb.append(message);
        for (String c : codes) if (c != null && !c.isEmpty()) sb.append(' ').append(c);
        String lowered = sb.toString().toLowerCase();
        if (!lowered.contains("model")) return false;
        for (String marker : new String[] {"not found", "does not exist", "not exist", "not supported", "unsupported", "not available", "unknown"}) {
            if (lowered.contains(marker)) return true;
        }
        return false;
    }

    private static LM15Error typed(ErrorCode code, String provider, String message, Integer status, String providerCode) {
        ErrorMeta meta = ErrorMeta.of(provider).withStatus(status).withProviderCode(providerCode);
        return Errors.forCode(code, message, meta);
    }

    /** An {@code error} object inside a 2xx body (the complete path). */
    static LM15Error responseError(String provider, String code, String message) {
        ErrorCode cls = RESPONSE_ERROR_CODE_MAP.getOrDefault(code, ErrorCode.SERVER);
        String msg = !message.isEmpty() ? message : !code.isEmpty() ? code : "provider error";
        return typed(cls, provider, msg, null, code.isEmpty() ? null : code);
    }

    /** The detail of a stream / live error frame. */
    static ErrorDetail errorDetail(String providerCode, String message) {
        ErrorCode code = STREAM_ERROR_CODE_MAP.getOrDefault(providerCode, ErrorCode.PROVIDER);
        String msg = !message.isEmpty() ? message : !providerCode.isEmpty() ? providerCode : "provider error";
        return new ErrorDetail(code, msg, providerCode.isEmpty() ? "provider" : providerCode);
    }

    /** {@code error} / {@code response.error} frames on the Responses stream and the Realtime socket. */
    static ErrorDetail streamErrorDetail(JsonObject payload) {
        JsonValue err = payload.get("error");
        String providerCode;
        String message;
        if (err instanceof JsonObject e) {
            providerCode = Js.strOrEmpty(e.get("code"), e.get("type"));
            if (providerCode.isEmpty()) providerCode = Js.strOrEmpty(payload.get("code"));
            message = Js.strOrEmpty(e.get("message"), payload.get("message"));
        } else {
            providerCode = Js.strOrEmpty(payload.get("code"), payload.get("error_type"));
            message = Js.strOrEmpty(payload.get("message"));
        }
        if (providerCode.isEmpty()) providerCode = "provider";
        return errorDetail(providerCode, message);
    }

    /** The {@code {"detail": "..."}} envelope of the ChatGPT Codex backend; null when the body is not that shape. */
    static LM15Error normalizeDetailError(BuildContext cx, int status, String body) {
        JsonValue data;
        try {
            data = Json.parse(body);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(data instanceof JsonObject o)) return null;
        JsonValue dv = o.get("detail");
        if (!(dv instanceof dev.lm15.json.JsonString ds) || ds.value().isBlank()) return null;
        String detail = ds.value().strip();
        if (isModelError(detail)) return typed(ErrorCode.UNSUPPORTED_MODEL, cx.provider(), detail + "\n" + MODEL_LIST_HINT, status, null);
        return Errors.mapHttpError(status, detail, ErrorMeta.of(cx.provider()));
    }

    /** The OpenAI error shape → the typed provider error. */
    static LM15Error normalize(BuildContext cx, int status, String body) {
        String provider = cx.provider();
        if (ResponsesRequest.isCodex(cx)) {
            LM15Error detail = normalizeDetailError(cx, status, body);
            if (detail != null) return detail;
        }
        String msg;
        String providerCode;
        try {
            JsonValue data = Json.parse(body);
            JsonValue err = data instanceof JsonObject d ? d.get("error") : null;
            if (err == null || err.isNull()) err = JsonObject.EMPTY;
            String code;
            String errType;
            if (err instanceof JsonObject e) {
                msg = e.opt("message") == null ? "" : Js.pyStr(e.get("message"));
                code = Js.str(e, "code");
                errType = Js.str(e, "type");
            } else {
                msg = Js.pyStr(err);
                code = "";
                errType = "";
            }
            providerCode = !code.isEmpty() ? code : !errType.isEmpty() ? errType : null;
            if (code.equals("context_length_exceeded")) return typed(ErrorCode.CONTEXT_LENGTH, provider, msg, status, providerCode);
            if (MODEL_ERROR_CODES.contains(code) || (status == 404 && isModelError(msg, code, errType))) {
                return typed(ErrorCode.UNSUPPORTED_MODEL, provider, msg, status, providerCode);
            }
            // Billing before rate-limit: both can ride HTTP 429, and only one is retryable.
            if (code.equals("insufficient_quota") || code.equals("1113") || errType.equals("insufficient_quota") || errType.equals("exceeded_current_quota_error")) {
                return typed(ErrorCode.BILLING, provider, msg, status, providerCode);
            }
            if (code.equals("invalid_api_key") || errType.equals("authentication_error")) return typed(ErrorCode.AUTH, provider, msg, status, providerCode);
            if (code.equals("rate_limit_exceeded") || errType.equals("rate_limit_error")) return typed(ErrorCode.RATE_LIMIT, provider, msg, status, providerCode);
            if (!code.isEmpty() && !msg.contains(code)) msg = msg + " (" + code + ")";
        } catch (RuntimeException e) {
            String stripped = body.strip();
            msg = stripped.length() > 500 ? stripped.substring(0, 500) : stripped;
            if (msg.isEmpty()) msg = "HTTP " + status;
            providerCode = null;
        }
        return Errors.mapHttpError(status, msg, ErrorMeta.of(provider).withProviderCode(providerCode), cx.policy().envKeys());
    }
}
