package dev.lm15.transport;

import dev.lm15.json.Json;
import dev.lm15.json.JsonValue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** A buffered provider-level HTTP response: status, headers (verbatim pairs) and body bytes. */
public record HttpResponse(int status, List<Map.Entry<String, String>> headers, byte[] body) {
    public HttpResponse {
        headers = headers == null ? List.of() : List.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    /** The first value of a header, by case-insensitive name; null when absent. */
    public String header(String name) {
        for (Map.Entry<String, String> h : headers) if (h.getKey().equalsIgnoreCase(name)) return h.getValue();
        return null;
    }

    public String text() { return new String(body, StandardCharsets.UTF_8); }

    public JsonValue json() { return Json.parse(text()); }

    public static HttpResponse json(int status, byte[] body) {
        return new HttpResponse(status, List.of(Map.entry("content-type", "application/json")), body);
    }
}
