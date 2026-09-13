package dev.lm15.cloud;

import dev.lm15.auth.Credential;
import dev.lm15.wire.Wire;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 over the JDK's SHA-256 / HMAC (the reference's
 * {@code lm15/cloud/sigv4.py}; pinned by the AWS test suite in
 * auth/sigv4-vectors.json, all 34 vectors, three stages byte for byte).
 *
 * <ul>
 * <li>canonical request = method, URI-encoded path (dot segments removed as
 *     the SDKs do for non-S3 paths), sorted+encoded query, lowercase sorted
 *     headers with values trimmed and inner whitespace collapsed, the
 *     signed-header list, hex SHA-256 of the payload;</li>
 * <li>string to sign = {@code AWS4-HMAC-SHA256}, {@code x-amz-date}, the
 *     credential scope, hex SHA-256 of the canonical request;</li>
 * <li>signing key = HMAC chain {@code AWS4}+secret → date → region → service → {@code aws4_request};</li>
 * <li>{@code x-amz-content-sha256} is S3-only and is NOT added.</li>
 * </ul>
 */
final class SigV4Signer {
    private SigV4Signer() {}

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd").withZone(ZoneOffset.UTC);

    /** Python {@code urllib.parse.urlsplit}: (netloc, path, query). */
    record SplitUrl(String netloc, String path, String query) {}

    static SplitUrl split(String url) {
        String rest = url;
        int scheme = rest.indexOf("://");
        String netloc = "";
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
            int end = rest.length();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '/' || c == '?' || c == '#') { end = i; break; }
            }
            netloc = rest.substring(0, end);
            rest = rest.substring(end);
        }
        int hash = rest.indexOf('#');
        if (hash >= 0) rest = rest.substring(0, hash);
        int q = rest.indexOf('?');
        String path = q >= 0 ? rest.substring(0, q) : rest;
        String query = q >= 0 ? rest.substring(q + 1) : "";
        return new SplitUrl(netloc, path, query);
    }

    /** Python {@code urllib.parse.unquote}: %XX sequences → UTF-8 bytes → text (malformed escapes kept verbatim). */
    static String unquote(String text) {
        if (text.indexOf('%') < 0) return text;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '%' && i + 2 < raw.length && isHex(raw[i + 1]) && isHex(raw[i + 2])) {
                out.write((Character.digit(raw[i + 1], 16) << 4) | Character.digit(raw[i + 2], 16));
                i += 2;
            } else {
                out.write(raw[i]);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isHex(byte b) { return Character.digit(b, 16) >= 0; }

    private static String encode(String text) { return Wire.percentEncode(text, ""); }

    /** RFC 3986 §5.2.4 as the AWS SDKs apply it to non-S3 paths. */
    static String removeDotSegments(String path) {
        List<String> kept = new ArrayList<>();
        for (String segment : path.split("/", -1)) {
            if (segment.equals("..")) {
                if (!kept.isEmpty()) kept.remove(kept.size() - 1);
            } else if (!segment.isEmpty() && !segment.equals(".")) {
                kept.add(segment);
            }
        }
        String first = path.startsWith("/") ? "/" : "";
        String last = path.endsWith("/") && !kept.isEmpty() ? "/" : "";
        return first + String.join("/", kept) + last;
    }

    static String canonicalPath(String path) {
        if (path.isEmpty()) return "/";
        String normalized = removeDotSegments(path);
        if (normalized.isEmpty()) normalized = "/";
        StringBuilder sb = new StringBuilder();
        String[] segments = normalized.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(encode(unquote(segments[i])));
        }
        return sb.toString();
    }

    /** Python {@code parse_qsl(query, keep_blank_values=True)} then encode, sort, join. */
    static String canonicalQuery(String query) {
        List<String[]> pairs = new ArrayList<>();
        for (String nameValue : query.split("&", -1)) {
            if (nameValue.isEmpty()) continue;
            int eq = nameValue.indexOf('=');
            String name = eq >= 0 ? nameValue.substring(0, eq) : nameValue;
            String value = eq >= 0 ? nameValue.substring(eq + 1) : "";
            pairs.add(new String[] {encode(unquote(name.replace('+', ' '))), encode(unquote(value.replace('+', ' ')))});
        }
        pairs.sort((a, b) -> {
            int c = a[0].compareTo(b[0]);
            return c != 0 ? c : a[1].compareTo(b[1]);
        });
        StringBuilder sb = new StringBuilder();
        for (String[] p : pairs) {
            if (sb.length() > 0) sb.append('&');
            sb.append(p[0]).append('=').append(p[1]);
        }
        return sb.toString();
    }

    /** Trim and collapse inner whitespace runs to one space (Python {@code " ".join(value.split())}). */
    static String trim(String value) {
        String[] parts = value.trim().split("\\s+");
        if (parts.length == 1 && parts[0].isEmpty()) return "";
        return String.join(" ", parts);
    }

    static String hexSha256(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** (canonical_request, signed_headers) for already-complete headers (lowercase names, last duplicate wins). */
    static String[] canonicalize(String method, String url, Map<String, String> headers, byte[] payload) {
        SplitUrl parts = split(url);
        TreeMap<String, String> lowered = new TreeMap<>();
        for (Map.Entry<String, String> h : headers.entrySet()) lowered.put(h.getKey().toLowerCase(), trim(h.getValue()));
        String signed = String.join(";", lowered.keySet());
        StringBuilder canonicalHeaders = new StringBuilder();
        for (Map.Entry<String, String> h : lowered.entrySet()) canonicalHeaders.append(h.getKey()).append(':').append(h.getValue()).append('\n');
        String canonical = String.join("\n", method.toUpperCase(), canonicalPath(parts.path()), canonicalQuery(parts.query()),
            canonicalHeaders.toString(), signed, hexSha256(payload));
        return new String[] {canonical, signed};
    }

    static SigV4.Signature sign(String method, String url, List<Map.Entry<String, String>> headers, byte[] payload,
                                Credential.AwsCredentials credentials, String region, String service, Instant now) {
        String amzDate = AMZ_DATE.format(now);
        String date = DATE.format(now);
        SplitUrl parts = split(url);

        LinkedHashMap<String, String> toSign = new LinkedHashMap<>();
        for (Map.Entry<String, String> h : headers) {
            String name = h.getKey().toLowerCase();
            if (!name.equals("authorization")) toSign.put(name, h.getValue());
        }
        toSign.put("host", parts.netloc());
        toSign.put("x-amz-date", amzDate);
        toSign.remove("x-amz-security-token");
        if (credentials.sessionToken() != null && !credentials.sessionToken().isEmpty()) {
            toSign.put("x-amz-security-token", credentials.sessionToken());
        }

        String[] canonical = canonicalize(method, url, toSign, payload == null ? new byte[0] : payload);
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = String.join("\n", ALGORITHM, amzDate, scope, hexSha256(canonical[0].getBytes(StandardCharsets.UTF_8)));

        byte[] key = ("AWS4" + credentials.secretAccessKey()).getBytes(StandardCharsets.UTF_8);
        for (String piece : new String[] {date, region, service, "aws4_request"}) key = hmac(key, piece);
        String signature = hex(hmac(key, stringToSign));

        String authorization = ALGORITHM + " Credential=" + credentials.accessKeyId() + "/" + scope
            + ", SignedHeaders=" + canonical[1] + ", Signature=" + signature;
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, String> h : toSign.entrySet()) out.add(Map.entry(h.getKey(), trim(h.getValue())));
        out.add(Map.entry("authorization", authorization));
        return new SigV4.Signature(canonical[0], stringToSign, authorization, out);
    }
}
