package dev.lm15.wire;

import dev.lm15.auth.Access;
import dev.lm15.auth.AccessPolicy;
import dev.lm15.auth.AuthScheme;
import dev.lm15.auth.Credential;
import dev.lm15.cloud.Hosts;
import dev.lm15.cloud.SigV4;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one path from a dialect build to a transport request (the reference's
 * {@code _emit}): credential invoked once (AUTH-2) → scheme (D1) → auth
 * header → host rewrites (AUTH-10) → content-type → SigV4 when the scheme
 * is sigv4. Nothing else touches headers.
 */
public final class Wire {
    private Wire() {}

    public static TransportRequest emit(WireRequest wire, BuildContext cx, Credential credential, Clock clock, String apiKeyHeader, boolean stream) {
        AccessPolicy policy = cx.policy();
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        for (Map.Entry<String, String> h : wire.headers) headers.add(Map.entry(h.getKey().toLowerCase(), h.getValue()));

        if (credential != null && !(credential instanceof Credential.AwsCredentials)) {
            Map.Entry<String, String> pair = Access.authHeader(policy, credential, apiKeyHeader);
            if (pair != null && headers.stream().noneMatch(h -> h.getKey().equalsIgnoreCase(pair.getKey()))) {
                headers.add(Map.entry(pair.getKey().toLowerCase(), pair.getValue()));
            }
        }

        String url = wire.absoluteUrl != null ? wire.absoluteUrl : joinUrl(cx.baseUrl(), wire.path);
        Hosts.Finished finished = Hosts.finishRequest(policy, cx.settings(), cx.baseUrl(), url, headers, wire.body, wire.params,
            wire.endpoint, stream, wire.model, credential);

        List<Map.Entry<String, String>> hdrs = new ArrayList<>(finished.headers());
        JsonValue body = finished.body();
        byte[] raw = wire.raw;
        if (body != null && hdrs.stream().noneMatch(h -> h.getKey().equalsIgnoreCase("content-type"))) {
            hdrs.add(Map.entry("content-type", "application/json"));
        }
        TransportRequest req = new TransportRequest(wire.method, finished.url(), finished.params(), hdrs, body, raw, wire.readTimeout);
        if (credential instanceof Credential.AwsCredentials aws) {
            if (Access.selectScheme(policy, aws) != AuthScheme.SIGV4) throw new IllegalStateException("unreachable");
            req = req.withHeaders(SigV4.signRequest(policy, cx.settings(), req, aws, clock.now()));
        }
        return req;
    }

    /** {@code base} without a trailing slash plus {@code path} with a leading one. */
    public static String joinUrl(String base, String path) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (path == null || path.isEmpty()) return b;
        return b + (path.startsWith("/") ? path : "/" + path);
    }

    /** {@code application/x-www-form-urlencoded} encoding of one component (Python {@code urlencode}). */
    public static String formEncode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    /**
     * RFC 3986 percent-encoding over UTF-8 for an id placed in a URL path
     * (MAP-11): unreserved bytes verbatim, {@code /} kept only for
     * resource-name dialects, everything else {@code %XX} uppercase.
     */
    public static String pathId(String value, boolean resourceName) {
        return percentEncode(value, resourceName ? "/" : "");
    }

    /** Python {@code urllib.parse.quote(value, safe=safe)}. */
    public static String percentEncode(String value, String safe) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~'
                || (c < 0x80 && safe.indexOf((char) c) >= 0)) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    /** The protocol's build_request shape: url without query, decoded params, lowercase headers, JSON body or body_b64. */
    public static JsonObject normalize(TransportRequest req) {
        dev.lm15.json.JsonBuilder params = new dev.lm15.json.JsonBuilder();
        for (Map.Entry<String, String> p : req.params()) params.put(p.getKey(), p.getValue());
        dev.lm15.json.JsonBuilder headers = new dev.lm15.json.JsonBuilder();
        for (Map.Entry<String, String> h : req.headers()) headers.put(h.getKey().toLowerCase(), h.getValue());
        dev.lm15.json.JsonBuilder out = new dev.lm15.json.JsonBuilder()
            .put("method", req.method()).put("url", req.url()).put("params", params.build()).put("headers", headers.build());
        if (req.body() != null) {
            out.put("body", req.body());
        } else if (req.raw() != null && req.raw().length > 0) {
            out.put("body", (Object) null);
            out.put("body_b64", java.util.Base64.getEncoder().encodeToString(req.raw()));
        } else {
            out.put("body", (Object) null);
        }
        return out.build();
    }
}
