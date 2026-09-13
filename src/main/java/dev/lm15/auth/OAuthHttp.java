package dev.lm15.auth;

import dev.lm15.errors.AuthError;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.NotConfiguredError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonException;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.transport.HttpResponse;
import dev.lm15.transport.HttpTransport;
import dev.lm15.transport.Transport;
import dev.lm15.wire.TransportRequest;
import dev.lm15.wire.Wire;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Shared plumbing for the stored-login loaders: token-endpoint POSTs, credential-file reads, JWT payloads. Runtime only (network). */
final class OAuthHttp {
    private OAuthHttp() {}

    static final long REFRESH_SKEW_MS = 5L * 60 * 1000; // AUTH-3

    private static volatile Transport transport;

    /** The transport the login flows and refreshes use; tests may inject one. */
    static Transport transport() {
        Transport t = transport;
        if (t == null) {
            synchronized (OAuthHttp.class) {
                if (transport == null) transport = new HttpTransport();
                t = transport;
            }
        }
        return t;
    }

    static void setTransport(Transport t) { transport = t; }

    /** (ok, parsed body): a token endpoint's reply, even for 4xx, whose JSON carries protocol state. */
    record Reply(boolean ok, JsonObject body) {}

    static Reply postJson(String url, JsonObject payload) {
        List<Map.Entry<String, String>> headers = List.of(Map.entry("Content-Type", "application/json"), Map.entry("Accept", "application/json"));
        return send(new TransportRequest("POST", url, List.of(), headers, payload, null, Duration.ofSeconds(30)));
    }

    static Reply postForm(String url, List<Map.Entry<String, String>> pairs) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> p : pairs) {
            if (sb.length() > 0) sb.append('&');
            sb.append(Wire.formEncode(p.getKey())).append('=').append(Wire.formEncode(p.getValue()));
        }
        List<Map.Entry<String, String>> headers = List.of(Map.entry("Content-Type", "application/x-www-form-urlencoded"), Map.entry("Accept", "application/json"));
        return send(new TransportRequest("POST", url, List.of(), headers, null, sb.toString().getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(30)));
    }

    private static Reply send(TransportRequest request) {
        HttpResponse response = transport().send(request);
        JsonObject body;
        try {
            JsonValue v = Json.parse(response.text());
            body = v instanceof JsonObject o ? o : JsonObject.EMPTY;
        } catch (JsonException e) {
            body = JsonObject.EMPTY;
        }
        return new Reply(response.status() < 400, body);
    }

    static NotConfiguredError notConfigured(String provider, String message, String hint) {
        return new NotConfiguredError(message, ErrorMeta.of(provider), List.of(), hint);
    }

    static AuthError authError(String provider, String message, String hint) {
        return new AuthError(message, ErrorMeta.of(provider), List.of(), hint);
    }

    /** Read a credential file, raising typed errors instead of crashing. */
    static JsonObject readJsonFile(Path path, String provider, String hint) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw notConfigured(provider, "No credentials file at " + path + ".", hint);
        } catch (IOException e) {
            throw notConfigured(provider, "Could not read credentials file at " + path + ": " + e.getMessage(), hint);
        }
        JsonValue data;
        try {
            data = Json.parse(text);
        } catch (JsonException e) {
            throw notConfigured(provider, "Credentials file at " + path + " is not valid JSON.", hint);
        }
        if (!(data instanceof JsonObject o)) throw notConfigured(provider, "Credentials file at " + path + " has an unexpected shape.", hint);
        return o;
    }

    static JsonObject readJsonFileOrNull(Path path) {
        try {
            JsonValue v = Json.parse(Files.readString(path, StandardCharsets.UTF_8));
            return v instanceof JsonObject o ? o : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static String nonEmptyString(JsonObject o, String key) {
        JsonValue v = o == null ? null : o.opt(key);
        if (v == null || !v.isString() || v.asString().isEmpty()) return null;
        return v.asString();
    }

    static Long integer(JsonObject o, String key) {
        JsonValue v = o == null ? null : o.opt(key);
        if (v == null || !v.isNumber()) return null;
        try {
            return (long) v.asDouble();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static JsonObject decodeJwtPayload(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) throw new IllegalArgumentException("Invalid JWT");
        byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
        JsonValue v = Json.parse(new String(decoded, StandardCharsets.UTF_8));
        return v instanceof JsonObject o ? o : JsonObject.EMPTY;
    }

    /** The JWT's {@code exp} as epoch milliseconds minus the refresh skew, or null. */
    static Long jwtExpiresAtMs(String token) {
        try {
            JsonValue exp = decodeJwtPayload(token).opt("exp");
            if (exp != null && exp.isNumber()) return (long) (exp.asDouble() * 1000) - REFRESH_SKEW_MS;
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    static JsonBuilder builder(JsonObject seed) { return seed == null ? new JsonBuilder() : seed.toBuilder(); }
}
