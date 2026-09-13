package dev.lm15.wire;

import dev.lm15.json.JsonValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a dialect builds BEFORE auth and host work: a path under the base URL
 * (leading slash), decoded params, dialect headers (never the credential),
 * a JSON or raw body, the endpoint name a host path override is keyed by,
 * and the wire model for hosts that place it in the path.
 */
public final class WireRequest {
    public String method;
    public String path;
    public final List<Map.Entry<String, String>> params = new ArrayList<>();
    public final List<Map.Entry<String, String>> headers = new ArrayList<>();
    public JsonValue body;
    public byte[] raw;
    /** The endpoint name for {@code host.paths}: messages, responses, chat/completions, generateContent. */
    public String endpoint;
    public String model;
    /** An absolute URL replacing base + path (the Gemini upload host). */
    public String absoluteUrl;
    public Duration readTimeout;

    public WireRequest(String method, String path) {
        this.method = method;
        this.path = path;
    }

    public static WireRequest post(String path, JsonValue body) {
        WireRequest w = new WireRequest("POST", path);
        w.body = body;
        return w;
    }

    public static WireRequest get(String path) { return new WireRequest("GET", path); }

    public static WireRequest delete(String path) { return new WireRequest("DELETE", path); }

    public static WireRequest json(String method, String path, JsonValue body) {
        WireRequest w = new WireRequest(method, path);
        w.body = body;
        return w;
    }

    /** A request with a verbatim body under {@code contentType} (multipart uploads). */
    public static WireRequest raw(String method, String path, String contentType, byte[] raw) {
        WireRequest w = new WireRequest(method, path);
        w.raw = raw;
        w.headers.add(Map.entry("content-type", contentType));
        return w;
    }

    public WireRequest param(String key, String value) { params.add(Map.entry(key, value)); return this; }
    public WireRequest header(String key, String value) { headers.add(Map.entry(key, value)); return this; }
    public WireRequest endpoint(String e) { endpoint = e; return this; }
    public WireRequest model(String m) { model = m; return this; }
    public WireRequest absolute(String url) { absoluteUrl = url; return this; }
}
