package dev.lm15.wire;

import dev.lm15.json.Json;
import dev.lm15.json.JsonValue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A request ready for a transport. {@code url} carries no query string; the
 * params are DECODED pairs (harness/PROTOCOL.md § Query parameter encoding:
 * encoding is the transport's job). Header names are lowercase, values
 * verbatim. A JSON body is kept as a value (the dialect's key order); a raw
 * body (multipart) is verbatim bytes under its {@code content-type}.
 */
public record TransportRequest(String method, String url, List<Map.Entry<String, String>> params,
                               List<Map.Entry<String, String>> headers, JsonValue body, byte[] raw, Duration readTimeout) {
    public TransportRequest {
        params = params == null ? List.of() : List.copyOf(params);
        headers = headers == null ? List.of() : List.copyOf(headers);
    }

    /** The bytes a transport sends: the raw bytes, else compact JSON (the bytes a signature covers). */
    public byte[] bodyBytes() {
        if (raw != null) return raw;
        if (body != null) return Json.write(body).getBytes(StandardCharsets.UTF_8);
        return new byte[0];
    }

    public boolean hasBody() { return body != null || raw != null; }

    /** The first value of a header, by case-insensitive name; null when absent. */
    public String header(String name) {
        for (Map.Entry<String, String> h : headers) if (h.getKey().equalsIgnoreCase(name)) return h.getValue();
        return null;
    }

    /** The URL with the params encoded (what actually goes on the wire). */
    public String fullUrl() {
        if (params.isEmpty()) return url;
        StringBuilder sb = new StringBuilder(url).append(url.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> p : params) {
            if (!first) sb.append('&');
            first = false;
            sb.append(Wire.formEncode(p.getKey())).append('=').append(Wire.formEncode(p.getValue()));
        }
        return sb.toString();
    }

    public TransportRequest withHeaders(List<Map.Entry<String, String>> h) { return new TransportRequest(method, url, params, h, body, raw, readTimeout); }
}
