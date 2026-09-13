package dev.lm15.cloud;

import dev.lm15.auth.AccessPolicy;
import dev.lm15.auth.Credential;
import dev.lm15.auth.CredentialPolicy;
import dev.lm15.auth.CredentialProvider;
import dev.lm15.auth.Rfc3339;
import dev.lm15.errors.AuthError;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.LM15Error;
import dev.lm15.errors.NotConfiguredError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonException;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.types.ValidationException;
import dev.lm15.wire.Wire;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The three cloud credential chains as data over the ten rung kinds
 * (spec/auth.md AUTH-1 {@code aws-chain} / {@code azure-chain} /
 * {@code gcp-chain}, AUTH-11; the reference's {@code lm15/cloud/chains.py}).
 *
 * <p>{@link #explain} is the offline doctor walk (AUTH-7): every rung is
 * {@code selected} / {@code shadowed} / {@code absent} / {@code unprobed};
 * no network, no subprocess. {@link #credentialProvider} is the AUTH-2
 * provider: resolves once, caches until the AUTH-3 skew window. Rungs that
 * are declared but not implemented raise {@code NotConfiguredError} naming
 * the gap; they never fall through silently.
 */
public final class Chains {
    private Chains() {}

    public static final Set<String> RUNG_KINDS = Set.of("env", "ini-profile", "json-file", "subprocess", "http-metadata",
        "http-token-exchange", "sigv4-sts", "unsigned-sts", "jwt-rs256", "file-cache");

    static final long SKEW_SECONDS = 300; // AUTH-3
    static final String GCP_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    static final String GCP_TOKEN_URL = "https://oauth2.googleapis.com/token";
    static final String GCP_STS_URL = "https://sts.googleapis.com/v1/token";
    static final String JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    static final String CLIENT_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    private static final Map<String, String> FORM = Map.of("content-type", "application/x-www-form-urlencoded");

    // ─── Rungs and steps ─────────────────────────────────────────────

    /** A probe verdict: {@code usable} (offline-selectable), {@code configured} (needs network/subprocess), {@code absent}. */
    public record Probe(String verdict, String detail) {
        static Probe usable(String detail) { return new Probe("usable", detail); }
        static Probe configured(String detail) { return new Probe("configured", detail); }
        static Probe absent(String detail) { return new Probe("absent", detail); }
    }

    public record Rung(String name, String kind, String source, String needs, Function<ChainContext, Probe> probe,
                       Function<ChainContext, Credential> acquire) {
        public Rung {
            if (!RUNG_KINDS.contains(kind)) throw ValidationException.value("unknown rung kind '" + kind + "'");
        }
    }

    public record Step(String kind, String source, String detail, String state) {}

    // ─── Helpers ─────────────────────────────────────────────────────

    private static Instant expiresFrom(Instant now, JsonValue seconds) {
        Double v = numberOf(seconds);
        return v == null ? null : now.plusSeconds((long) v.doubleValue());
    }

    /** Python {@code int(float(x))} tolerance: a number, or a numeric string; null otherwise. */
    private static Double numberOf(JsonValue v) {
        if (v == null) return null;
        try {
            if (v.isNumber()) return v.asDouble();
            if (v.isString()) return Double.parseDouble(v.asString().strip());
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    static JsonObject jsonBody(byte[] body) {
        try {
            JsonValue v = Json.parse(new String(body, StandardCharsets.UTF_8));
            return v instanceof JsonObject o ? o : JsonObject.EMPTY;
        } catch (JsonException e) {
            return JsonObject.EMPTY;
        }
    }

    static JsonObject jsonBody(String text) { return jsonBody(text.getBytes(StandardCharsets.UTF_8)); }

    /** A non-empty string member, or null. */
    static String str(JsonObject o, String key) {
        JsonValue v = o.get(key);
        if (v == null || !v.isString()) return null;
        String s = v.asString();
        return s.isEmpty() ? null : s;
    }

    /** Any non-null scalar member as text (Python {@code str(x)}), or null. */
    static String text(JsonObject o, String key) {
        JsonValue v = o.opt(key);
        if (v == null) return null;
        if (v.isString()) return v.asString();
        return v.toJson();
    }

    private static boolean truthy(JsonObject o, String key) {
        JsonValue v = o.opt(key);
        return v != null && !Json.isEmpty(v) && !(v.isBool() && !v.asBool()) && !(v.isNumber() && v.asDouble() == 0);
    }

    static byte[] form(List<Map.Entry<String, String>> pairs) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> p : pairs) {
            if (sb.length() > 0) sb.append('&');
            sb.append(Wire.formEncode(p.getKey())).append('=').append(Wire.formEncode(p.getValue()));
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String query(List<Map.Entry<String, String>> pairs) {
        return new String(form(pairs), StandardCharsets.US_ASCII);
    }

    private static JsonObject exchange(ChainContext ctx, String method, String url, Map<String, String> headers, byte[] body, String what) {
        ChainContext.HttpResult r = ctx.http().call(method, url, headers, body, 30.0);
        JsonObject data = jsonBody(r.body());
        if (r.status() < 200 || r.status() >= 300) throw new AuthError(what + ": HTTP " + r.status());
        return data;
    }

    static Credential.BearerToken bearerFromOauth(JsonObject data, Instant now, String what) {
        String token = str(data, "access_token");
        if (token == null) throw new AuthError(what + ": no valid access_token in response");
        Instant expires = null;
        Double on = numberOf(data.opt("expires_on"));
        if (on != null) expires = Instant.ofEpochSecond((long) on.doubleValue());
        if (expires == null && numberOf(data.opt("expires_in")) != null) expires = expiresFrom(now, data.opt("expires_in"));
        return new Credential.BearerToken(token, expires);
    }

    private static Credential.BearerToken bearer(String token, Instant expires) {
        return new Credential.BearerToken(token, expires);
    }

    // ─── AWS ─────────────────────────────────────────────────────────

    record AwsConfig(Ini creds, Ini conf, String profile) {}

    static AwsConfig awsConfig(ChainContext ctx) {
        String profile = ctx.has("AWS_PROFILE") ? ctx.env("AWS_PROFILE") : "default";
        try {
            Ini creds = Ini.parse(ctx.read(ctx.has("AWS_SHARED_CREDENTIALS_FILE") ? ctx.env("AWS_SHARED_CREDENTIALS_FILE") : "~/.aws/credentials"));
            Ini conf = Ini.parse(ctx.read(ctx.has("AWS_CONFIG_FILE") ? ctx.env("AWS_CONFIG_FILE") : "~/.aws/config"));
            return new AwsConfig(creds, conf, profile);
        } catch (Ini.Malformed e) {
            throw new NotConfiguredError("malformed AWS profile configuration; check the AWS config and credentials files");
        }
    }

    static Map<String, String> awsProfileSection(Ini conf, String profile) {
        String name = profile.equals("default") ? profile : "profile " + profile;
        if (conf.hasSection(name)) return conf.section(name);
        if (conf.hasSection(profile)) return conf.section(profile);
        return Map.of();
    }

    private static String get(Map<String, String> section, String key) {
        String v = section.get(key);
        return v == null || v.isEmpty() ? null : v;
    }

    static Credential.AwsCredentials awsStatic(Map<String, String> section) {
        String key = get(section, "aws_access_key_id");
        String secret = get(section, "aws_secret_access_key");
        if (key == null || secret == null) return null;
        return new Credential.AwsCredentials(key, secret, get(section, "aws_session_token"), null);
    }

    static Credential.AwsCredentials awsFromResponse(JsonObject d) {
        Instant expires = null;
        JsonValue raw = d.opt("Expiration");
        if (raw == null) raw = d.opt("expiration");
        if (raw != null && raw.isString()) {
            expires = Rfc3339.parse(raw.asString());
        } else if (raw != null && raw.isNumber()) {
            double n = raw.asDouble();
            expires = n > 1e11 ? Instant.ofEpochMilli((long) n) : Instant.ofEpochSecond((long) n);
        }
        String key = firstStr(d, "AccessKeyId", "accessKeyId");
        String secret = firstStr(d, "SecretAccessKey", "secretAccessKey");
        if (key == null || secret == null) throw new AuthError("AWS credential response lacks access key id or secret access key");
        return new Credential.AwsCredentials(key, secret, firstStr(d, "SessionToken", "Token", "sessionToken"), expires);
    }

    private static String firstStr(JsonObject d, String... keys) {
        for (String k : keys) {
            String v = str(d, k);
            if (v != null) return v;
        }
        return null;
    }

    static Credential.AwsCredentials stsXmlCredentials(byte[] raw) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(raw));
            NodeList nodes = doc.getElementsByTagNameNS("https://sts.amazonaws.com/doc/2011-06-15/", "Credentials");
            if (nodes.getLength() == 0) throw new AuthError("STS: no Credentials in response");
            Element node = (Element) nodes.item(0);
            Function<String, String> getText = tag -> {
                NodeList n = node.getElementsByTagNameNS("https://sts.amazonaws.com/doc/2011-06-15/", tag);
                return n.getLength() == 0 ? "" : n.item(0).getTextContent();
            };
            String expiration = getText.apply("Expiration");
            String session = getText.apply("SessionToken");
            return new Credential.AwsCredentials(getText.apply("AccessKeyId"), getText.apply("SecretAccessKey"),
                session.isEmpty() ? null : session, expiration.isEmpty() ? null : Rfc3339.parse(expiration));
        } catch (LM15Error e) {
            throw e;
        } catch (Exception e) {
            throw new AuthError("STS: unparsable response");
        }
    }

    private static Credential.AwsCredentials awsSourceCredentials(ChainContext ctx, Map<String, String> section, int depth) {
        if (depth > 5) throw new AuthError("assume-role: source_profile chain too deep");
        String sourceProfile = get(section, "source_profile");
        if (sourceProfile != null) {
            AwsConfig cfg = awsConfig(ctx);
            LinkedHashMap<String, String> sub = new LinkedHashMap<>(awsProfileSection(cfg.conf(), sourceProfile));
            if (cfg.creds().hasSection(sourceProfile)) sub.putAll(cfg.creds().section(sourceProfile));
            if (get(sub, "role_arn") != null) return assumeRole(ctx, sub, depth + 1);
            Credential.AwsCredentials stat = awsStatic(sub);
            if (stat == null) throw new NotConfiguredError("assume-role: source_profile '" + sourceProfile + "' has no keys");
            return stat;
        }
        String source = get(section, "credential_source");
        if ("Environment".equals(source)) {
            Credential.AwsCredentials stat = envAws(ctx);
            if (stat == null) throw new NotConfiguredError("assume-role: credential_source=Environment but AWS_ACCESS_KEY_ID is not set");
            return stat;
        }
        if ("EcsContainer".equals(source)) {
            Credential.AwsCredentials got = containerAcquire(ctx);
            if (got == null) throw new NotConfiguredError("assume-role: credential_source=EcsContainer but no container endpoint is configured");
            return got;
        }
        if ("Ec2InstanceMetadata".equals(source)) {
            Credential.AwsCredentials got = imdsAcquire(ctx);
            if (got == null) throw new NotConfiguredError("assume-role: credential_source=Ec2InstanceMetadata but IMDS answered nothing");
            return got;
        }
        throw new NotConfiguredError("assume-role: profile needs source_profile or credential_source");
    }

    private static String awsRegion(ChainContext ctx, Map<String, String> section) {
        String region = ctx.settings().get("region");
        if (region == null || region.isEmpty()) region = ctx.env("AWS_REGION");
        if (region == null || region.isEmpty()) region = ctx.env("AWS_DEFAULT_REGION");
        if ((region == null || region.isEmpty()) && section != null) region = get(section, "region");
        return region == null || region.isEmpty() ? "us-east-1" : region;
    }

    private static String sessionName() { return "lm15-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    static Credential.AwsCredentials assumeRole(ChainContext ctx, Map<String, String> section, int depth) {
        Credential.AwsCredentials source = awsSourceCredentials(ctx, section, depth);
        String region = awsRegion(ctx, section);
        String url = "https://sts." + region + ".amazonaws.com/";
        List<Map.Entry<String, String>> pairs = new ArrayList<>(List.of(Map.entry("Action", "AssumeRole"), Map.entry("Version", "2011-06-15"),
            Map.entry("RoleArn", section.get("role_arn")),
            Map.entry("RoleSessionName", get(section, "role_session_name") != null ? section.get("role_session_name") : sessionName())));
        if (get(section, "external_id") != null) pairs.add(Map.entry("ExternalId", section.get("external_id")));
        if (get(section, "duration_seconds") != null) pairs.add(Map.entry("DurationSeconds", section.get("duration_seconds")));
        byte[] body = form(pairs);
        SigV4.Signature signed = SigV4.sign("POST", url, List.of(Map.entry("content-type", "application/x-www-form-urlencoded")), body,
            source, region, "sts", ctx.clock().now());
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> h : signed.headers()) headers.put(h.getKey(), h.getValue());
        ChainContext.HttpResult r = ctx.http().call("POST", url, headers, body, 30.0);
        if (r.status() >= 400) throw new AuthError("STS AssumeRole: HTTP " + r.status());
        return stsXmlCredentials(r.body());
    }

    static Credential.AwsCredentials envAws(ChainContext ctx) {
        if (ctx.has("AWS_ACCESS_KEY_ID") && ctx.has("AWS_SECRET_ACCESS_KEY")) {
            return new Credential.AwsCredentials(ctx.env("AWS_ACCESS_KEY_ID"), ctx.env("AWS_SECRET_ACCESS_KEY"),
                ctx.has("AWS_SESSION_TOKEN") ? ctx.env("AWS_SESSION_TOKEN") : null, null);
        }
        return null;
    }

    /** (token file, role arn, session name) or null. */
    private static String[] webIdentityConfig(ChainContext ctx) {
        String tokenFile = ctx.env("AWS_WEB_IDENTITY_TOKEN_FILE");
        String role = ctx.env("AWS_ROLE_ARN");
        String session = ctx.has("AWS_ROLE_SESSION_NAME") ? ctx.env("AWS_ROLE_SESSION_NAME") : "";
        if (!(ctx.has("AWS_WEB_IDENTITY_TOKEN_FILE") && ctx.has("AWS_ROLE_ARN"))) {
            AwsConfig cfg = awsConfig(ctx);
            Map<String, String> section = awsProfileSection(cfg.conf(), cfg.profile());
            tokenFile = get(section, "web_identity_token_file");
            role = get(section, "role_arn");
            session = get(section, "role_session_name") != null ? section.get("role_session_name") : "";
            if (!(tokenFile != null && role != null && get(section, "source_profile") == null && get(section, "credential_source") == null)) return null;
        }
        return new String[] {tokenFile, role, session};
    }

    private static Credential webIdentityAcquire(ChainContext ctx) {
        String[] cfg = webIdentityConfig(ctx);
        if (cfg == null) return null;
        String token = ctx.read(cfg[0]);
        if (token == null) throw new NotConfiguredError("web identity token file " + cfg[0] + " is unreadable");
        String region = awsRegion(ctx, null);
        byte[] body = form(List.of(Map.entry("Action", "AssumeRoleWithWebIdentity"), Map.entry("Version", "2011-06-15"), Map.entry("RoleArn", cfg[1]),
            Map.entry("RoleSessionName", cfg[2].isEmpty() ? sessionName() : cfg[2]), Map.entry("WebIdentityToken", token.strip())));
        ChainContext.HttpResult r = ctx.http().call("POST", "https://sts." + region + ".amazonaws.com/", FORM, body, 30.0);
        if (r.status() >= 400) throw new AuthError("STS AssumeRoleWithWebIdentity: HTTP " + r.status());
        return stsXmlCredentials(r.body());
    }

    static String sha1Hex(String text) {
        try {
            return SigV4Signer.hex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(String text) { return SigV4Signer.hexSha256(text.getBytes(StandardCharsets.UTF_8)); }

    private static Map<String, String> ssoConfig(ChainContext ctx) {
        AwsConfig cfg = awsConfig(ctx);
        LinkedHashMap<String, String> section = new LinkedHashMap<>(awsProfileSection(cfg.conf(), cfg.profile()));
        if (get(section, "sso_session") != null) {
            String name = section.get("sso_session");
            Map<String, String> sess = cfg.conf().hasSection("sso-session " + name) ? cfg.conf().section("sso-session " + name) : Map.of();
            if (get(sess, "sso_start_url") == null) return null;
            LinkedHashMap<String, String> out = new LinkedHashMap<>(sess);
            out.putAll(section);
            out.put("cache_key", sha1Hex(name));
            out.put("session_name", name);
            return out;
        }
        if (get(section, "sso_start_url") != null) {
            section.put("cache_key", sha1Hex(section.get("sso_start_url")));
            return section;
        }
        return null;
    }

    private static Credential ssoAcquire(ChainContext ctx) {
        Map<String, String> cfg = ssoConfig(ctx);
        if (cfg == null) return null;
        String raw = ctx.read("~/.aws/sso/cache/" + cfg.get("cache_key") + ".json");
        if (raw == null) throw new NotConfiguredError("IAM Identity Center: no cached token; run `aws sso login`", null, List.of(), "aws sso login");
        JsonObject token = jsonBody(raw);
        Instant now = ctx.clock().now();
        Instant expires = str(token, "expiresAt") != null ? Rfc3339.parse(token.get("expiresAt").asString()) : null;
        String access = str(token, "accessToken");
        String ssoRegion = get(cfg, "sso_region") != null ? cfg.get("sso_region") : "us-east-1";
        if (access == null || (expires != null && expires.getEpochSecond() - now.getEpochSecond() <= SKEW_SECONDS)) {
            if (!(str(token, "refreshToken") != null && str(token, "clientId") != null && str(token, "clientSecret") != null)) {
                throw new NotConfiguredError("IAM Identity Center: token expired and not refreshable; run `aws sso login`", null, List.of(), "aws sso login");
            }
            JsonObject body = Json.obj("clientId", token.get("clientId"), "clientSecret", token.get("clientSecret"),
                "grantType", "refresh_token", "refreshToken", token.get("refreshToken"));
            JsonObject data = exchange(ctx, "POST", "https://oidc." + ssoRegion + ".amazonaws.com/token", Map.of("content-type", "application/json"),
                Json.writeBytes(body), "sso-oidc CreateToken");
            access = str(data, "accessToken");
            if (access == null) throw new AuthError("sso-oidc CreateToken: no accessToken");
        }
        String account = get(cfg, "sso_account_id");
        String role = get(cfg, "sso_role_name");
        if (account == null || role == null) throw new NotConfiguredError("IAM Identity Center: profile needs sso_account_id and sso_role_name");
        String q = query(List.of(Map.entry("role_name", role), Map.entry("account_id", account)));
        ChainContext.HttpResult r = ctx.http().call("GET", "https://portal.sso." + ssoRegion + ".amazonaws.com/federation/credentials?" + q,
            Map.of("x-amz-sso_bearer_token", access), null, 30.0);
        if (r.status() >= 400) throw new AuthError("sso GetRoleCredentials: HTTP " + r.status());
        JsonObject creds = jsonBody(r.body()).optObject("roleCredentials");
        return awsFromResponse(creds == null ? JsonObject.EMPTY : creds);
    }

    private static String loginConfig(ChainContext ctx) {
        AwsConfig cfg = awsConfig(ctx);
        return get(awsProfileSection(cfg.conf(), cfg.profile()), "login_session");
    }

    private static Credential.AwsCredentials loginCached(ChainContext ctx) {
        String session = loginConfig(ctx);
        if (session == null) return null;
        String directory = ctx.has("AWS_LOGIN_CACHE_DIRECTORY") ? ctx.env("AWS_LOGIN_CACHE_DIRECTORY") : "~/.aws/login/cache";
        String raw = ctx.read(directory + "/" + sha256Hex(session) + ".json");
        if (raw == null) return null;
        JsonObject token = jsonBody(raw).optObject("accessToken");
        if (token == null || str(token, "accessKeyId") == null) return null;
        JsonBuilder b = new JsonBuilder().put("AccessKeyId", token.get("accessKeyId"));
        if (token.opt("secretAccessKey") != null) b.put("SecretAccessKey", token.get("secretAccessKey"));
        if (token.opt("sessionToken") != null) b.put("SessionToken", token.get("sessionToken"));
        if (token.opt("expiresAt") != null) b.put("Expiration", token.get("expiresAt"));
        return awsFromResponse(b.build());
    }

    private static Credential loginAcquire(ChainContext ctx) {
        if (loginConfig(ctx) == null) return null;
        Credential.AwsCredentials cached = loginCached(ctx);
        if (cached != null && !cached.isExpired(ctx.clock().now())) return cached;
        // Refresh needs the signin CreateOAuth2Token call with a DPoP proof over the cached EC key: a stated gap.
        throw new NotConfiguredError("AWS login session expired; run `aws login`", null, List.of(), "aws login");
    }

    private static Credential processAcquire(ChainContext ctx) {
        AwsConfig cfg = awsConfig(ctx);
        String command = get(awsProfileSection(cfg.conf(), cfg.profile()), "credential_process");
        if (command == null || ctx.run() == null) return null;
        String out = ctx.run().run(Shlex.split(command), 60.0);
        JsonObject data = jsonBody(out);
        if (!isOne(data.opt("Version"))) throw new AuthError("credential_process: output Version must be 1");
        return awsFromResponse(data);
    }

    private static boolean isOne(JsonValue v) { return v != null && v.isNumber() && v.asDouble() == 1.0; }

    static final Set<String> CONTAINER_ALLOWED = Set.of("169.254.170.2", "169.254.170.23", "fd00:ec2::23", "localhost");

    static String containerConfig(ChainContext ctx) {
        String rel = ctx.env("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI");
        String full = ctx.env("AWS_CONTAINER_CREDENTIALS_FULL_URI");
        if (rel != null && !rel.isEmpty()) {
            if (!rel.startsWith("/") || rel.startsWith("//") || rel.chars().anyMatch(c -> "\\\r\n\t#".indexOf(c) >= 0)) {
                throw new NotConfiguredError("container credentials relative URI must be an absolute path");
            }
            return "http://169.254.170.2" + rel;
        }
        if (full != null && !full.isEmpty()) {
            URI parsed;
            try {
                parsed = new URI(full);
            } catch (java.net.URISyntaxException e) {
                throw new NotConfiguredError("container credentials URI must be HTTP(S), without userinfo or fragment");
            }
            String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase();
            String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase();
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            if (!(scheme.equals("http") || scheme.equals("https")) || host.isEmpty() || parsed.getUserInfo() != null || parsed.getFragment() != null) {
                throw new NotConfiguredError("container credentials URI must be HTTP(S), without userinfo or fragment");
            }
            boolean loopback = isLoopback(host);
            if (!scheme.equals("https") && !loopback && !CONTAINER_ALLOWED.contains(host)) {
                throw new NotConfiguredError("Unsupported host '" + host + "'. Can only retrieve metadata from a loopback address or one of these hosts: "
                    + String.join(", ", new TreeMap<>(Map.of("169.254.170.2", "", "169.254.170.23", "", "fd00:ec2::23", "", "localhost", "")).keySet()));
            }
            return full;
        }
        return null;
    }

    private static boolean isLoopback(String host) {
        try {
            if (host.contains(":")) return java.net.InetAddress.getByName(host).isLoopbackAddress() && host.matches("[0-9a-fA-F:]+");
            if (!host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return false;
            return java.net.InetAddress.getByName(host).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    static Credential.AwsCredentials containerAcquire(ChainContext ctx) {
        String url = containerConfig(ctx);
        if (url == null || ctx.http() == null) return null;
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        String token = ctx.env("AWS_CONTAINER_AUTHORIZATION_TOKEN");
        String tokenFile = ctx.env("AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE");
        if (tokenFile != null && !tokenFile.isEmpty() && (token == null || token.isEmpty())) {
            String read = ctx.read(tokenFile);
            token = read == null ? "" : read.strip();
        }
        if (token != null && !token.isEmpty()) headers.put("Authorization", token);
        ChainContext.HttpResult r = ctx.http().call("GET", url, headers, null, 5.0);
        if (r.status() >= 400) throw new AuthError("container credentials: HTTP " + r.status());
        return awsFromResponse(jsonBody(r.body()));
    }

    private static boolean imdsDisabled(ChainContext ctx) {
        String v = ctx.env("AWS_EC2_METADATA_DISABLED");
        return v != null && v.strip().equalsIgnoreCase("true");
    }

    static Credential.AwsCredentials imdsAcquire(ChainContext ctx) {
        if (imdsDisabled(ctx) || ctx.http() == null) return null;
        String base = ctx.env("AWS_EC2_METADATA_SERVICE_ENDPOINT");
        if (base == null || base.isEmpty()) {
            String mode = ctx.env("AWS_EC2_METADATA_SERVICE_ENDPOINT_MODE");
            base = mode != null && mode.equalsIgnoreCase("ipv6") ? "http://[fd00:ec2::254]" : "http://169.254.169.254";
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        ChainContext.HttpResult tok;
        try {
            tok = ctx.http().call("PUT", base + "/latest/api/token", Map.of("X-aws-ec2-metadata-token-ttl-seconds", "21600"), null, 1.0);
        } catch (RuntimeException e) {
            return null; // not on EC2: the rung is absent, not an error
        }
        if (tok.status() != 200) return null;
        Map<String, String> headers = Map.of("X-aws-ec2-metadata-token", new String(tok.body(), StandardCharsets.UTF_8));
        ChainContext.HttpResult role = ctx.http().call("GET", base + "/latest/meta-data/iam/security-credentials/", headers, null, 1.0);
        String roleText = new String(role.body(), StandardCharsets.UTF_8).strip();
        if (role.status() != 200 || roleText.isEmpty()) return null;
        String roleName = roleText.lines().findFirst().orElse("").strip();
        ChainContext.HttpResult r = ctx.http().call("GET", base + "/latest/meta-data/iam/security-credentials/" + roleName, headers, null, 1.0);
        if (r.status() != 200) return null;
        JsonObject data = jsonBody(r.body());
        String code = str(data, "Code");
        if (code != null && !code.equals("Success")) throw new AuthError("IMDS rejected the credential request");
        return awsFromResponse(data);
    }

    private static List<Rung> awsChain(AccessPolicy policy) {
        String doorKey = policy.envKeys().isEmpty() ? null : policy.envKeys().get(0);
        List<Rung> rungs = new ArrayList<>();
        if (doorKey != null) {
            boolean bearer = doorKey.equals("AWS_BEARER_TOKEN_BEDROCK");
            rungs.add(new Rung("env:" + doorKey, "env", "env $" + doorKey, "",
                ctx -> ctx.has(doorKey) ? Probe.usable("set (value never shown)") : Probe.absent("not set"),
                ctx -> !ctx.has(doorKey) ? null : bearer ? new Credential.BearerToken(ctx.env(doorKey)) : new Credential.ApiKey(ctx.env(doorKey))));
        }
        rungs.add(new Rung("env:AWS_ACCESS_KEY_ID", "env", "env $AWS_ACCESS_KEY_ID (+SECRET, +SESSION_TOKEN)", "",
            ctx -> envAws(ctx) != null ? Probe.usable("set (values never shown)") : Probe.absent("AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY not set"),
            Chains::envAws));
        rungs.add(new Rung("assume-role", "sigv4-sts", "profile assume-role via STS", "network", ctx -> {
            AwsConfig cfg = awsConfig(ctx);
            Map<String, String> section = awsProfileSection(cfg.conf(), cfg.profile());
            if (get(section, "role_arn") != null && (get(section, "source_profile") != null || get(section, "credential_source") != null)) {
                return Probe.configured("profile '" + cfg.profile() + "' assumes " + section.get("role_arn") + " (STS call at request time)");
            }
            return Probe.absent("profile '" + cfg.profile() + "' has no role_arn with a source");
        }, ctx -> {
            AwsConfig cfg = awsConfig(ctx);
            Map<String, String> section = awsProfileSection(cfg.conf(), cfg.profile());
            if (get(section, "role_arn") != null && (get(section, "source_profile") != null || get(section, "credential_source") != null)) {
                return assumeRole(ctx, section, 0);
            }
            return null;
        }));
        rungs.add(new Rung("web-identity", "unsigned-sts", "web identity via STS", "network", ctx -> {
            String[] cfg = webIdentityConfig(ctx);
            return cfg != null ? Probe.configured("token file " + cfg[0] + " → " + cfg[1] + " (STS call at request time)")
                : Probe.absent("AWS_WEB_IDENTITY_TOKEN_FILE / AWS_ROLE_ARN not set");
        }, Chains::webIdentityAcquire));
        rungs.add(new Rung("sso", "file-cache", "IAM Identity Center (~/.aws/sso/cache)", "network", ctx -> {
            Map<String, String> cfg = ssoConfig(ctx);
            if (cfg == null) return Probe.absent("no sso_session / sso_start_url in the profile");
            boolean cached = ctx.exists("~/.aws/sso/cache/" + cfg.get("cache_key") + ".json");
            return Probe.configured(cached ? "cached token present (GetRoleCredentials at request time)" : "no cached token; run `aws sso login`");
        }, Chains::ssoAcquire));
        rungs.add(new Rung("shared-credentials-file", "ini-profile", "~/.aws/credentials", "", ctx -> {
            AwsConfig cfg = awsConfig(ctx);
            Map<String, String> section = cfg.creds().hasSection(cfg.profile()) ? cfg.creds().section(cfg.profile()) : Map.of();
            return awsStatic(section) != null ? Probe.usable("profile '" + cfg.profile() + "' (values never shown)")
                : Probe.absent("no keys for profile '" + cfg.profile() + "'");
        }, ctx -> {
            AwsConfig cfg = awsConfig(ctx);
            return cfg.creds().hasSection(cfg.profile()) ? awsStatic(cfg.creds().section(cfg.profile())) : null;
        }));
        rungs.add(new Rung("login", "file-cache", "aws login session (~/.aws/login/cache)", "", ctx -> {
            if (loginConfig(ctx) == null) return Probe.absent("no login_session in the profile");
            Credential.AwsCredentials cached = loginCached(ctx);
            if (cached != null && !cached.isExpired(ctx.clock().now())) return Probe.usable("cached short-term credentials are fresh");
            return Probe.configured("cached credentials missing or expired; refresh needs `aws login`");
        }, Chains::loginAcquire));
        rungs.add(new Rung("credential_process", "subprocess", "profile credential_process", "subprocess", ctx -> {
            AwsConfig cfg = awsConfig(ctx);
            String cmd = get(awsProfileSection(cfg.conf(), cfg.profile()), "credential_process");
            if (cmd == null) return Probe.absent("no credential_process in the profile");
            List<String> argv = Shlex.split(cmd);
            String executable = argv.isEmpty() ? "" : argv.get(0);
            if (ctx.onPath(executable) == null) return Probe.absent("credential_process '" + executable + "' is not on PATH");
            return Probe.configured("credential_process configured (run at request time)");
        }, Chains::processAcquire));
        rungs.add(new Rung("config-file", "ini-profile", "~/.aws/config static keys", "", ctx -> {
            AwsConfig cfg = awsConfig(ctx);
            return awsStatic(awsProfileSection(cfg.conf(), cfg.profile())) != null
                ? Probe.usable("static keys in config for profile '" + cfg.profile() + "'") : Probe.absent("no static keys in config");
        }, ctx -> {
            AwsConfig cfg = awsConfig(ctx);
            return awsStatic(awsProfileSection(cfg.conf(), cfg.profile()));
        }));
        rungs.add(new Rung("container", "http-metadata", "container credentials endpoint", "network", ctx -> {
            String url;
            try {
                url = containerConfig(ctx);
            } catch (NotConfiguredError e) {
                return Probe.absent(firstLine(e.message()));
            }
            return url != null ? Probe.configured("container endpoint configured (HTTP at request time)") : Probe.absent("no AWS_CONTAINER_CREDENTIALS_* URI");
        }, Chains::containerAcquire));
        rungs.add(new Rung("imds", "http-metadata", "EC2 instance metadata (IMDSv2)", "network",
            ctx -> imdsDisabled(ctx) ? Probe.absent("AWS_EC2_METADATA_DISABLED=true") : Probe.configured("instance metadata probed at request time"),
            Chains::imdsAcquire));
        return rungs;
    }

    static String firstLine(String text) {
        if (text == null) return "";
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    // ─── Azure ───────────────────────────────────────────────────────

    static String azureAuthority(ChainContext ctx) {
        String v = ctx.settings().get("authority_host");
        if (v == null || v.isEmpty()) v = ctx.env("AZURE_AUTHORITY_HOST");
        if (v == null || v.isEmpty()) v = "https://login.microsoftonline.com";
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    static String azureScope(ChainContext ctx) {
        String v = ctx.settings().get("scope");
        return v == null || v.isEmpty() ? "https://ai.azure.com/.default" : v;
    }

    static String azureTokenUrl(ChainContext ctx, String tenant) {
        return azureAuthority(ctx) + "/" + tenant + "/oauth2/v2.0/token";
    }

    /**
     * The Entra client assertion: RS256 (as azure-identity signs it), {@code x5t}
     * = base64url SHA-1 of the DER certificate, claims in MSAL's order, 600 s lifetime.
     */
    public static String azureCertificateAssertion(ChainContext ctx, String tenant, String clientId, String pem, String jti, boolean sendChain) {
        java.security.PrivateKey key = Rs256.loadPrivateKey(pem);
        byte[] der = Rs256.certificateDer(pem);
        long now = ctx.clock().now().getEpochSecond();
        JsonBuilder header = new JsonBuilder().put("alg", "RS256").put("typ", "JWT");
        try {
            header.put("x5t", Rs256.b64url(MessageDigest.getInstance("SHA-1").digest(der)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        if (sendChain) header.put("x5c", new JsonArray(List.of(new JsonString(Base64.getEncoder().encodeToString(der)))));
        JsonObject payload = new JsonBuilder().put("aud", azureTokenUrl(ctx, tenant)).put("iss", clientId).put("sub", clientId)
            .put("exp", now + 600).put("iat", now).put("jti", jti != null ? jti : UUID.randomUUID().toString()).build();
        return Rs256.jwtEncode(header.build(), payload, key);
    }

    private static String azureEnvironmentKind(ChainContext ctx) {
        if (!(ctx.has("AZURE_TENANT_ID") && ctx.has("AZURE_CLIENT_ID"))) return null;
        if (ctx.has("AZURE_CLIENT_SECRET")) return "secret";
        if (ctx.has("AZURE_CLIENT_CERTIFICATE_PATH")) return "certificate";
        return null;
    }

    /** (token URL, form pairs) for the environment service principal. */
    public static Map.Entry<String, List<Map.Entry<String, String>>> azureEnvironmentRequest(ChainContext ctx, String jti) {
        String kind = azureEnvironmentKind(ctx);
        String tenant = ctx.env("AZURE_TENANT_ID");
        String client = ctx.env("AZURE_CLIENT_ID");
        if (tenant == null || client == null) throw new NotConfiguredError("Azure environment credential needs AZURE_TENANT_ID and AZURE_CLIENT_ID");
        String url = azureTokenUrl(ctx, tenant);
        String scope = azureScope(ctx);
        if ("secret".equals(kind)) {
            return Map.entry(url, List.of(Map.entry("client_id", client), Map.entry("scope", scope),
                Map.entry("client_secret", ctx.env("AZURE_CLIENT_SECRET")), Map.entry("grant_type", "client_credentials")));
        }
        if ("certificate".equals(kind)) {
            String certPath = ctx.env("AZURE_CLIENT_CERTIFICATE_PATH");
            String pem = ctx.read(certPath);
            if (pem == null) throw new NotConfiguredError("AZURE_CLIENT_CERTIFICATE_PATH " + certPath + " is unreadable");
            if (ctx.has("AZURE_CLIENT_CERTIFICATE_PASSWORD")) {
                throw new NotConfiguredError("password-protected certificates are not supported; decrypt with `openssl pkey`", null, List.of(),
                    "openssl pkey -in cert.pem -out cert-plain.pem");
            }
            String chain = ctx.env("AZURE_CLIENT_SEND_CERTIFICATE_CHAIN");
            boolean sendChain = chain != null && (chain.equalsIgnoreCase("1") || chain.equalsIgnoreCase("true"));
            String assertion = azureCertificateAssertion(ctx, tenant, client, pem, jti, sendChain);
            return Map.entry(url, List.of(Map.entry("client_id", client), Map.entry("scope", scope),
                Map.entry("client_assertion_type", CLIENT_ASSERTION_TYPE), Map.entry("client_assertion", assertion),
                Map.entry("grant_type", "client_credentials")));
        }
        throw new NotConfiguredError("Azure environment credential needs AZURE_CLIENT_SECRET or AZURE_CLIENT_CERTIFICATE_PATH");
    }

    private static Credential azureEnvironmentAcquire(ChainContext ctx) {
        if (azureEnvironmentKind(ctx) == null) return null;
        var req = azureEnvironmentRequest(ctx, null);
        JsonObject data = exchange(ctx, "POST", req.getKey(), FORM, form(req.getValue()), "Entra client credentials");
        return bearerFromOauth(data, ctx.clock().now(), "Entra");
    }

    private static boolean azureWorkloadConfig(ChainContext ctx) {
        return ctx.has("AZURE_FEDERATED_TOKEN_FILE") && ctx.has("AZURE_CLIENT_ID") && ctx.has("AZURE_TENANT_ID");
    }

    private static Credential azureWorkloadAcquire(ChainContext ctx) {
        if (!azureWorkloadConfig(ctx)) return null;
        String token = ctx.read(ctx.env("AZURE_FEDERATED_TOKEN_FILE"));
        if (token == null) throw new NotConfiguredError("AZURE_FEDERATED_TOKEN_FILE " + ctx.env("AZURE_FEDERATED_TOKEN_FILE") + " is unreadable");
        List<Map.Entry<String, String>> pairs = List.of(Map.entry("client_id", ctx.env("AZURE_CLIENT_ID")), Map.entry("scope", azureScope(ctx)),
            Map.entry("client_assertion_type", CLIENT_ASSERTION_TYPE), Map.entry("client_assertion", token.strip()),
            Map.entry("grant_type", "client_credentials"));
        JsonObject data = exchange(ctx, "POST", azureTokenUrl(ctx, ctx.env("AZURE_TENANT_ID")), FORM, form(pairs), "Entra workload identity");
        return bearerFromOauth(data, ctx.clock().now(), "Entra");
    }

    static String azureMsiFlavor(ChainContext ctx) {
        if (ctx.has("IDENTITY_ENDPOINT")) {
            if (ctx.has("IDENTITY_HEADER")) return ctx.has("IDENTITY_SERVER_THUMBPRINT") ? "service-fabric" : "app-service";
            if (ctx.has("IMDS_ENDPOINT")) return "azure-arc";
        }
        if (ctx.has("MSI_ENDPOINT")) return ctx.has("MSI_SECRET") ? "azure-ml" : "cloud-shell";
        return "imds";
    }

    private static String stripSuffix(String s, String suffix) { return s.endsWith(suffix) ? s.substring(0, s.length() - suffix.length()) : s; }

    private static Credential azureMsiAcquire(ChainContext ctx) {
        if (ctx.http() == null) return null;
        String flavor = azureMsiFlavor(ctx);
        String resource = stripSuffix(azureScope(ctx), "/.default");
        String clientId = ctx.has("AZURE_CLIENT_ID") ? ctx.env("AZURE_CLIENT_ID") : null;
        Instant now = ctx.clock().now();
        switch (flavor) {
            case "imds": {
                List<Map.Entry<String, String>> q = new ArrayList<>(List.of(Map.entry("api-version", "2018-02-01"), Map.entry("resource", resource)));
                if (clientId != null) q.add(Map.entry("client_id", clientId));
                ChainContext.HttpResult r;
                try {
                    r = ctx.http().call("GET", "http://169.254.169.254/metadata/identity/oauth2/token?" + query(q), Map.of("Metadata", "true"), null, 1.0);
                } catch (RuntimeException e) {
                    return null; // not on Azure: absent
                }
                if (r.status() != 200) return null;
                return bearerFromOauth(jsonBody(r.body()), now, "managed identity");
            }
            case "app-service": {
                List<Map.Entry<String, String>> q = new ArrayList<>(List.of(Map.entry("api-version", "2019-08-01"), Map.entry("resource", resource)));
                if (clientId != null) q.add(Map.entry("client_id", clientId));
                JsonObject data = exchange(ctx, "GET", ctx.env("IDENTITY_ENDPOINT") + "?" + query(q), Map.of("X-IDENTITY-HEADER", ctx.env("IDENTITY_HEADER")), null,
                    "App Service managed identity");
                return bearerFromOauth(data, now, "managed identity");
            }
            case "cloud-shell": {
                LinkedHashMap<String, String> h = new LinkedHashMap<>();
                h.put("Metadata", "true");
                h.put("content-type", "application/x-www-form-urlencoded");
                JsonObject data = exchange(ctx, "POST", ctx.env("MSI_ENDPOINT"), h, form(List.of(Map.entry("resource", resource))), "Cloud Shell managed identity");
                return bearerFromOauth(data, now, "managed identity");
            }
            case "azure-ml": {
                List<Map.Entry<String, String>> q = new ArrayList<>(List.of(Map.entry("api-version", "2017-09-01"), Map.entry("resource", resource)));
                if (clientId != null) q.add(Map.entry("clientid", clientId));
                JsonObject data = exchange(ctx, "GET", ctx.env("MSI_ENDPOINT") + "?" + query(q), Map.of("secret", ctx.env("MSI_SECRET")), null, "Azure ML managed identity");
                return bearerFromOauth(data, now, "managed identity");
            }
            case "azure-arc": {
                String url = ctx.env("IDENTITY_ENDPOINT") + "?" + query(List.of(Map.entry("api-version", "2019-11-01"), Map.entry("resource", resource)));
                ChainContext.HttpResult r = ctx.http().call("GET", url, Map.of("Metadata", "true"), null, 5.0);
                String challenge = r.headers().getOrDefault("www-authenticate", "");
                if (r.status() != 401 || !challenge.contains("realm=")) throw new AuthError("Azure Arc managed identity: expected a 401 challenge, got " + r.status());
                String keyPath = challenge.substring(challenge.indexOf("realm=") + 6).strip();
                if (keyPath.startsWith("\"")) keyPath = keyPath.substring(1);
                if (keyPath.endsWith("\"")) keyPath = keyPath.substring(0, keyPath.length() - 1);
                boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
                Path directory = windows ? Path.of(ctx.has("PROGRAMDATA") ? ctx.env("PROGRAMDATA") : "C:/ProgramData").resolve("AzureConnectedMachineAgent").resolve("Tokens")
                    : Path.of("/var/opt/azcmagent/tokens");
                Path path = Path.of(keyPath);
                if (path.getParent() == null || !path.getParent().equals(directory) || !keyPath.endsWith(".key")) {
                    throw new AuthError("Azure Arc managed identity: invalid challenge file location");
                }
                if (ctx.files() == null) {
                    try {
                        if (!path.toRealPath().getParent().equals(directory.toRealPath())) throw new AuthError("Azure Arc managed identity: invalid challenge file location");
                    } catch (java.io.IOException e) {
                        throw new AuthError("Azure Arc managed identity: challenge file missing or too large");
                    }
                }
                String secret = ctx.read(keyPath);
                if (secret == null || secret.length() > 4096) throw new AuthError("Azure Arc managed identity: challenge file missing or too large");
                LinkedHashMap<String, String> h = new LinkedHashMap<>();
                h.put("Metadata", "true");
                h.put("Authorization", "Basic " + secret.strip());
                return bearerFromOauth(exchange(ctx, "GET", url, h, null, "Azure Arc managed identity"), now, "managed identity");
            }
            default:
                throw new NotConfiguredError("Service Fabric managed identity (TLS thumbprint pinning) is not supported; use a certificate or secret");
        }
    }

    private static Credential azCliAcquire(ChainContext ctx) {
        if (ctx.run() == null || ctx.onPath("az") == null) return null;
        List<String> argv = new ArrayList<>(List.of("az", "account", "get-access-token", "--output", "json", "--scope", azureScope(ctx)));
        if (ctx.has("AZURE_TENANT_ID")) { argv.add("--tenant"); argv.add(ctx.env("AZURE_TENANT_ID")); }
        JsonObject data = jsonBody(ctx.run().run(argv, 30.0));
        JsonValue token = data.opt("accessToken");
        if (token == null) return null;
        JsonBuilder b = new JsonBuilder().put("access_token", token);
        if (data.opt("expires_on") != null) b.put("expires_on", data.get("expires_on"));
        Credential.BearerToken parsed = bearerFromOauth(b.build(), ctx.clock().now(), "Azure CLI");
        Instant expires = parsed.expiresAt();
        if (expires == null && data.opt("expiresOn") != null) {
            try {
                expires = java.time.LocalDateTime.parse(text(data, "expiresOn").replace(' ', 'T')).atZone(java.time.ZoneId.systemDefault()).toInstant();
            } catch (RuntimeException e) {
                expires = null;
            }
        }
        return bearer(parsed.value(), expires);
    }

    private static Credential pwshAcquire(ChainContext ctx) {
        if (ctx.run() == null || ctx.onPath("pwsh") == null) return null;
        String resource = stripSuffix(azureScope(ctx), "/.default").replace("'", "''");
        String script = "Get-AzAccessToken -ResourceUrl '" + resource + "' -AsSecureString:$false | ConvertTo-Json -Compress";
        JsonObject data = jsonBody(ctx.run().run(List.of("pwsh", "-NoProfile", "-NonInteractive", "-Command", script), 30.0));
        JsonValue token = data.opt("Token");
        return token == null ? null : bearerFromOauth(Json.obj("access_token", token), ctx.clock().now(), "Azure PowerShell");
    }

    private static Credential azdAcquire(ChainContext ctx) {
        if (ctx.run() == null || ctx.onPath("azd") == null) return null;
        JsonObject data = jsonBody(ctx.run().run(List.of("azd", "auth", "token", "--output", "json", "--scope", azureScope(ctx)), 30.0));
        JsonValue token = data.opt("token");
        if (token == null) return null;
        Credential.BearerToken parsed = bearerFromOauth(Json.obj("access_token", token), ctx.clock().now(), "Azure Developer CLI");
        Instant expires = null;
        if (str(data, "expiresOn") != null) {
            try {
                expires = Rfc3339.parse(data.get("expiresOn").asString());
            } catch (RuntimeException e) {
                expires = null;
            }
        }
        return bearer(parsed.value(), expires);
    }

    /** {@code AZURE_TOKEN_CREDENTIALS=prod|dev|<CredentialName>} narrowing. */
    private static boolean narrowed(ChainContext ctx, String name, boolean developer) {
        String value = ctx.env("AZURE_TOKEN_CREDENTIALS");
        value = value == null ? "" : value.strip().toLowerCase();
        if (value.isEmpty()) return false;
        if (value.equals("prod")) return developer;
        if (value.equals("dev")) return !developer;
        return !value.equals(name.toLowerCase());
    }

    private static Function<ChainContext, Credential> guard(Function<ChainContext, Credential> fn, String name, boolean developer) {
        return ctx -> narrowed(ctx, name, developer) ? null : fn.apply(ctx);
    }

    private static Function<ChainContext, Probe> cliProbe(String name, String label, String command) {
        return ctx -> {
            if (narrowed(ctx, name, true)) return Probe.absent("excluded by AZURE_TOKEN_CREDENTIALS");
            if (ctx.onPath(command) == null) return Probe.absent(command + " is not on PATH");
            return Probe.configured(label + " run at request time");
        };
    }

    private static List<Rung> azureChain(AccessPolicy policy) {
        String doorKey = policy.envKeys().isEmpty() ? null : policy.envKeys().get(0);
        List<Rung> rungs = new ArrayList<>();
        if (doorKey != null) {
            rungs.add(new Rung("env:" + doorKey, "env", "env $" + doorKey, "",
                ctx -> ctx.has(doorKey) ? Probe.usable("set (value never shown)") : Probe.absent("not set"),
                ctx -> ctx.has(doorKey) ? new Credential.ApiKey(ctx.env(doorKey)) : null));
        }
        rungs.add(new Rung("environment", "http-token-exchange", "Entra service principal from AZURE_* env", "network", ctx -> {
            if (narrowed(ctx, "EnvironmentCredential", false)) return Probe.absent("excluded by AZURE_TOKEN_CREDENTIALS");
            String kind = azureEnvironmentKind(ctx);
            return kind != null ? Probe.configured("service principal by " + kind + " (token exchange at request time)")
                : Probe.absent("AZURE_TENANT_ID/AZURE_CLIENT_ID + secret or certificate not set");
        }, guard(Chains::azureEnvironmentAcquire, "EnvironmentCredential", false)));
        rungs.add(new Rung("workload-identity", "http-token-exchange", "Entra workload identity", "network", ctx -> {
            if (narrowed(ctx, "WorkloadIdentityCredential", false)) return Probe.absent("excluded by AZURE_TOKEN_CREDENTIALS");
            return azureWorkloadConfig(ctx) ? Probe.configured("federated token file present (exchange at request time)") : Probe.absent("AZURE_FEDERATED_TOKEN_FILE not set");
        }, guard(Chains::azureWorkloadAcquire, "WorkloadIdentityCredential", false)));
        rungs.add(new Rung("managed-identity", "http-metadata", "Azure managed identity", "network", ctx -> {
            if (narrowed(ctx, "ManagedIdentityCredential", false)) return Probe.absent("excluded by AZURE_TOKEN_CREDENTIALS");
            return Probe.configured("managed identity (" + azureMsiFlavor(ctx) + ") probed at request time");
        }, guard(Chains::azureMsiAcquire, "ManagedIdentityCredential", false)));
        rungs.add(new Rung("az", "subprocess", "az account get-access-token", "subprocess", cliProbe("AzureCliCredential", "`az`", "az"),
            guard(Chains::azCliAcquire, "AzureCliCredential", true)));
        rungs.add(new Rung("pwsh", "subprocess", "Azure PowerShell Get-AzAccessToken", "subprocess", cliProbe("AzurePowerShellCredential", "`pwsh`", "pwsh"),
            guard(Chains::pwshAcquire, "AzurePowerShellCredential", true)));
        rungs.add(new Rung("azd", "subprocess", "azd auth token", "subprocess", cliProbe("AzureDeveloperCliCredential", "`azd`", "azd"),
            guard(Chains::azdAcquire, "AzureDeveloperCliCredential", true)));
        return rungs;
    }

    // ─── Google Cloud ────────────────────────────────────────────────

    /** (token_uri, JWT) for a {@code service_account} file: header typ/alg/kid, claims iat, exp=iat+3600, iss, aud, scope. */
    public static Map.Entry<String, String> gcpServiceAccountAssertion(ChainContext ctx, JsonObject info, String scope) {
        String privateKey = text(info, "private_key");
        if (privateKey == null) throw new NotConfiguredError("service_account file lacks private_key");
        java.security.PrivateKey key = Rs256.loadPrivateKey(privateKey);
        long now = ctx.clock().now().getEpochSecond();
        String tokenUri = str(info, "token_uri") != null ? info.get("token_uri").asString() : GCP_TOKEN_URL;
        JsonBuilder header = new JsonBuilder().put("alg", "RS256").put("typ", "JWT");
        if (truthy(info, "private_key_id")) header.put("kid", text(info, "private_key_id"));
        JsonObject payload = new JsonBuilder().put("iat", now).put("exp", now + 3600).put("iss", text(info, "client_email"))
            .put("aud", tokenUri).put("scope", scope).build();
        return Map.entry(tokenUri, Rs256.jwtEncode(header.build(), payload, key));
    }

    private static JsonObject gcpCredentialFile(ChainContext ctx, String path) {
        String raw = ctx.read(path);
        if (raw == null) return null;
        try {
            JsonValue v = Json.parse(raw);
            return v instanceof JsonObject o ? o : null;
        } catch (JsonException e) {
            throw new NotConfiguredError(path + ": not valid JSON");
        }
    }

    private static Credential.BearerToken gcpFromInfo(ChainContext ctx, JsonObject info, String where) {
        String kind = str(info, "type");
        Instant now = ctx.clock().now();
        if ("authorized_user".equals(kind)) {
            for (String k : new String[] {"refresh_token", "client_id", "client_secret"}) {
                if (!truthy(info, k)) throw new NotConfiguredError(where + ": authorized_user file lacks " + k);
            }
            List<Map.Entry<String, String>> pairs = List.of(Map.entry("grant_type", "refresh_token"), Map.entry("client_id", text(info, "client_id")),
                Map.entry("client_secret", text(info, "client_secret")), Map.entry("refresh_token", text(info, "refresh_token")));
            String tokenUri = truthy(info, "token_uri") ? text(info, "token_uri") : GCP_TOKEN_URL;
            return bearerFromOauth(exchange(ctx, "POST", tokenUri, FORM, form(pairs), "Google OAuth refresh"), now, "Google OAuth");
        }
        if ("service_account".equals(kind)) {
            var assertion = gcpServiceAccountAssertion(ctx, info, GCP_SCOPE);
            JsonObject data = exchange(ctx, "POST", assertion.getKey(), FORM,
                form(List.of(Map.entry("grant_type", JWT_BEARER), Map.entry("assertion", assertion.getValue()))), "Google service account");
            return bearerFromOauth(data, now, "Google service account");
        }
        if ("external_account".equals(kind)) return gcpExternalAccount(ctx, info, where);
        if ("impersonated_service_account".equals(kind)) {
            JsonObject source = info.optObject("source_credentials");
            if (source == null) throw new NotConfiguredError(where + ": impersonated_service_account lacks source_credentials");
            Credential.BearerToken base = gcpFromInfo(ctx, source, where + ".source_credentials");
            JsonArray delegates = info.optArray("delegates");
            return gcpImpersonate(ctx, base, text(info, "service_account_impersonation_url"), delegates == null ? JsonArray.EMPTY : delegates);
        }
        throw new NotConfiguredError(where + ": credential type '" + kind + "' is not supported by lm15 (external_account_authorized_user and gdch_service_account are stated gaps)");
    }

    private static Credential.BearerToken gcpImpersonate(ChainContext ctx, Credential.BearerToken source, String url, JsonArray delegates) {
        JsonObject body = Json.obj("delegates", delegates, "scope", Json.arr(GCP_SCOPE), "lifetime", "3600s");
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put("content-type", "application/json");
        h.put("authorization", "Bearer " + source.value());
        JsonObject data = exchange(ctx, "POST", url, h, Json.writeBytes(body), "generateAccessToken");
        String token = str(data, "accessToken");
        if (token == null) throw new AuthError("generateAccessToken: no accessToken");
        Instant expires = truthy(data, "expireTime") ? Rfc3339.parse(text(data, "expireTime")) : null;
        return bearer(token, expires);
    }

    private static Credential.BearerToken gcpExternalAccount(ChainContext ctx, JsonObject info, String where) {
        JsonObject source = info.optObject("credential_source");
        if (source == null) source = JsonObject.EMPTY;
        if (source.has("environment_id")) {
            throw new NotConfiguredError(where + ": external_account with an AWS credential_source is a stated gap in lm15; use a file/url/executable source or a service account");
        }
        String subject = null;
        JsonObject fmt = source.optObject("format");
        if (fmt == null) fmt = JsonObject.EMPTY;
        if (truthy(source, "file")) {
            String raw = ctx.read(text(source, "file"));
            if (raw == null) throw new NotConfiguredError(where + ": subject token file " + text(source, "file") + " is unreadable");
            subject = raw.strip();
        } else if (truthy(source, "url")) {
            LinkedHashMap<String, String> h = new LinkedHashMap<>();
            JsonObject hs = source.optObject("headers");
            if (hs != null) for (String k : hs.keys()) h.put(k, text(hs, k));
            ChainContext.HttpResult r = ctx.http().call("GET", text(source, "url"), h, null, 30.0);
            if (r.status() >= 400) throw new AuthError(where + ": subject token url HTTP " + r.status());
            subject = new String(r.body(), StandardCharsets.UTF_8).strip();
        } else if (truthy(source, "executable")) {
            if (ctx.run() == null) throw new NotConfiguredError(where + ": executable credential source needs subprocess access");
            if (!"1".equals(ctx.env("GOOGLE_EXTERNAL_ACCOUNT_ALLOW_EXECUTABLES"))) {
                throw new NotConfiguredError(where + ": set GOOGLE_EXTERNAL_ACCOUNT_ALLOW_EXECUTABLES=1 to allow the executable source");
            }
            JsonObject exe = source.get("executable").asObject();
            Double millis = numberOf(exe.opt("timeout_millis"));
            String out = ctx.run().run(Shlex.split(text(exe, "command")), (millis == null ? 30000 : millis) / 1000);
            JsonObject data = jsonBody(out);
            JsonValue success = data.opt("success");
            if (success != null && success.isBool() && !success.asBool()) throw new AuthError("external account executable reported failure");
            subject = str(data, "id_token") != null ? str(data, "id_token") : str(data, "saml_response");
            fmt = Json.obj("type", "text");
        }
        if (subject == null) throw new NotConfiguredError(where + ": external_account credential_source is not file/url/executable");
        if ("json".equals(str(fmt, "type"))) {
            String field = fmt.optString("subject_token_field_name");
            String v = text(jsonBody(subject), field == null ? "" : field);
            subject = v == null ? "" : v;
        }
        JsonObject body = Json.obj("grantType", "urn:ietf:params:oauth:grant-type:token-exchange", "audience", text(info, "audience"), "scope", GCP_SCOPE,
            "requestedTokenType", "urn:ietf:params:oauth:token-type:access_token", "subjectToken", subject, "subjectTokenType", text(info, "subject_token_type"));
        String tokenUrl = truthy(info, "token_url") ? text(info, "token_url") : GCP_STS_URL;
        Credential.BearerToken token = bearerFromOauth(exchange(ctx, "POST", tokenUrl, Map.of("content-type", "application/json"), Json.writeBytes(body), "Google STS exchange"),
            ctx.clock().now(), "Google STS");
        if (truthy(info, "service_account_impersonation_url")) return gcpImpersonate(ctx, token, text(info, "service_account_impersonation_url"), JsonArray.EMPTY);
        return token;
    }

    private static boolean noGceCheck(ChainContext ctx) {
        String v = ctx.env("NO_GCE_CHECK");
        return v != null && (v.equalsIgnoreCase("1") || v.equalsIgnoreCase("true"));
    }

    private static Credential gcpMetadataAcquire(ChainContext ctx) {
        if (ctx.http() == null || noGceCheck(ctx)) return null;
        String host = ctx.has("GCE_METADATA_HOST") ? ctx.env("GCE_METADATA_HOST") : ctx.has("GCE_METADATA_ROOT") ? ctx.env("GCE_METADATA_ROOT") : "metadata.google.internal";
        ChainContext.HttpResult r;
        try {
            r = ctx.http().call("GET", "http://" + host + "/computeMetadata/v1/instance/service-accounts/default/token", Map.of("Metadata-Flavor", "Google"), null, 1.0);
        } catch (RuntimeException e) {
            return null; // not on GCE: absent
        }
        if (r.status() != 200) return null;
        return bearerFromOauth(jsonBody(r.body()), ctx.clock().now(), "GCE metadata");
    }

    private static Credential gcloudAcquire(ChainContext ctx) {
        if (ctx.run() == null || ctx.onPath("gcloud") == null) return null;
        String token = ctx.run().run(List.of("gcloud", "auth", "print-access-token"), 30.0).strip();
        return token.isEmpty() ? null : new Credential.BearerToken(token);
    }

    private static String adcFilePath(ChainContext ctx) {
        String base = ctx.has("CLOUDSDK_CONFIG") ? ctx.env("CLOUDSDK_CONFIG") : "~/.config/gcloud";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/application_default_credentials.json";
    }

    private static Function<ChainContext, Probe> fileProbe(String label, Function<ChainContext, String> pathFn) {
        return ctx -> {
            String path = pathFn.apply(ctx);
            if (path == null || path.isEmpty()) return Probe.absent(label + " not set");
            JsonObject info = gcpCredentialFile(ctx, path);
            if (info == null) return Probe.absent(path + " missing or unreadable");
            String type = text(info, "type");
            return Probe.configured((type == null ? "?" : type) + " credentials in " + path + " (token exchange at request time)");
        };
    }

    private static Function<ChainContext, Credential> fileAcquire(Function<ChainContext, String> pathFn) {
        return ctx -> {
            String path = pathFn.apply(ctx);
            if (path == null || path.isEmpty()) return null;
            JsonObject info = gcpCredentialFile(ctx, path);
            return info != null ? gcpFromInfo(ctx, info, path) : null;
        };
    }

    private static List<Rung> gcpChain(AccessPolicy policy) {
        String doorKey = policy.envKeys().isEmpty() ? null : policy.envKeys().get(0);
        List<Rung> rungs = new ArrayList<>();
        if (doorKey != null) {
            rungs.add(new Rung("env:" + doorKey, "env", "env $" + doorKey, "",
                ctx -> ctx.has(doorKey) ? Probe.usable("set (value never shown)") : Probe.absent("not set"),
                ctx -> ctx.has(doorKey) ? new Credential.ApiKey(ctx.env(doorKey)) : null));
        }
        Function<ChainContext, String> envPath = ctx -> ctx.env("GOOGLE_APPLICATION_CREDENTIALS");
        Function<ChainContext, String> adcPath = Chains::adcFilePath;
        rungs.add(new Rung("adc-env", "json-file", "GOOGLE_APPLICATION_CREDENTIALS file", "network", fileProbe("GOOGLE_APPLICATION_CREDENTIALS", envPath), fileAcquire(envPath)));
        rungs.add(new Rung("adc-file", "json-file", "gcloud application default credentials file", "network", fileProbe("ADC file", adcPath), fileAcquire(adcPath)));
        rungs.add(new Rung("metadata", "http-metadata", "GCE metadata server", "network",
            ctx -> noGceCheck(ctx) ? Probe.absent("NO_GCE_CHECK set") : Probe.configured("GCE metadata server probed at request time"),
            Chains::gcpMetadataAcquire));
        rungs.add(new Rung("gcloud", "subprocess", "gcloud auth print-access-token", "subprocess",
            ctx -> ctx.onPath("gcloud") != null ? Probe.configured("`gcloud` run at request time") : Probe.absent("gcloud is not on PATH"),
            Chains::gcloudAcquire));
        return rungs;
    }

    // ─── Settings from the cloud profile (AUTH-10 fallbacks after env) ────

    /** AWS {@code region} from the active profile; GCP {@code project} from the ADC file; nothing for Azure. */
    public static Function<String, String> profileSettings(AccessPolicy policy, ChainContext ctx) {
        return name -> {
            if (policy.credentialPolicy() == CredentialPolicy.AWS_CHAIN && name.equals("region")) {
                AwsConfig cfg = awsConfig(ctx);
                String value = get(awsProfileSection(cfg.conf(), cfg.profile()), "region");
                if (value == null && cfg.creds().hasSection(cfg.profile())) value = get(cfg.creds().section(cfg.profile()), "region");
                return value;
            }
            if (policy.credentialPolicy() == CredentialPolicy.GCP_CHAIN && name.equals("project")) {
                for (String path : new String[] {ctx.env("GOOGLE_APPLICATION_CREDENTIALS"), adcFilePath(ctx)}) {
                    if (path == null || path.isEmpty()) continue;
                    JsonObject info = gcpCredentialFile(ctx, path);
                    if (info == null) continue;
                    String value = truthy(info, "quota_project_id") ? text(info, "quota_project_id") : truthy(info, "project_id") ? text(info, "project_id") : null;
                    if (value != null) return value;
                }
            }
            return null;
        };
    }

    // ─── Chains ──────────────────────────────────────────────────────

    public static List<Rung> chainFor(AccessPolicy policy) {
        return switch (policy.credentialPolicy()) {
            case AWS_CHAIN -> awsChain(policy);
            case AZURE_CHAIN -> azureChain(policy);
            case GCP_CHAIN -> gcpChain(policy);
            default -> throw ValidationException.value(policy.provider() + ": not a cloud chain policy");
        };
    }

    /** The AUTH-7 walk: (steps, configured). {@code explicit} = an api_keys entry exists (rung 0). */
    public static Map.Entry<List<Step>, Boolean> explain(AccessPolicy policy, ChainContext ctx, boolean explicit) {
        List<Step> steps = new ArrayList<>();
        boolean selected = explicit;
        steps.add(explicit ? new Step("api_keys", "explicit api_keys entry", "provided (value never shown)", "selected")
            : new Step("api_keys", "explicit api_keys entry", "not provided", "absent"));
        boolean unprobed = false;
        for (Rung rung : chainFor(policy)) {
            Probe probe;
            try {
                probe = rung.probe().apply(ctx);
            } catch (NotConfiguredError e) {
                probe = Probe.absent(firstLine(e.message()));
            }
            String state;
            if (probe.verdict().equals("absent")) {
                state = "absent";
            } else if (probe.verdict().equals("configured")) {
                state = selected ? "shadowed" : "unprobed";
                if (!selected) unprobed = true;
            } else {
                state = selected ? "shadowed" : "selected";
                selected = true;
            }
            steps.add(new Step(rung.name(), rung.source(), probe.detail(), state));
        }
        return Map.entry(steps, selected || unprobed);
    }

    /** Walk the chain online; the first rung that yields wins. Azure developer commands are tried through errors. */
    public static Credential resolve(AccessPolicy policy, ChainContext ctx) {
        boolean developerFailed = false;
        for (Rung rung : chainFor(policy)) {
            Credential got;
            try {
                got = rung.acquire().apply(ctx);
            } catch (AuthError e) {
                if (policy.credentialPolicy() == CredentialPolicy.AZURE_CHAIN && Set.of("az", "pwsh", "azd").contains(rung.name())) {
                    developerFailed = true;
                    continue;
                }
                throw e;
            }
            if (got != null) return got;
        }
        if (developerFailed) {
            throw new AuthError("Azure developer credentials failed; sign in with az, Azure PowerShell, or azd", ErrorMeta.of(policy.provider()));
        }
        throw new NotConfiguredError(policy.provider() + ": no credential found in the " + policy.credentialPolicy().wire() + " chain"
            + (policy.envKeys().isEmpty() ? "; configure the cloud SDK" : "; set " + policy.envKeys().get(0) + " or configure the cloud SDK"),
            ErrorMeta.of(policy.provider()), policy.envKeys(), null);
    }

    /** AUTH-2/AUTH-3: resolve once, hand out until the skew window, then re-resolve. In memory only. */
    static final class CachingProvider implements CredentialProvider {
        private final AccessPolicy policy;
        private final ChainContext ctx;
        private Credential value;

        CachingProvider(AccessPolicy policy, ChainContext ctx) { this.policy = policy; this.ctx = ctx; }

        @Override public synchronized Credential get() {
            Instant now = ctx.clock().now();
            if (value == null || value.isExpired(now)) {
                Credential fresh = resolve(policy, ctx);
                if (fresh.isExpired(now)) throw new AuthError("cloud credential is expired; renew the configured credential source", ErrorMeta.of(policy.provider()));
                // CLI output without an expiry cannot safely be cached forever.
                boolean cacheable = fresh instanceof Credential.ApiKey || fresh instanceof Credential.AwsCredentials
                    || (fresh instanceof Credential.BearerToken b && b.expiresAt() != null);
                value = cacheable ? fresh : null;
                return fresh;
            }
            return value;
        }

        @Override public String toString() { return "<cloud credential provider for " + policy.provider() + ">"; }
    }

    /** Provider id + the identity-selecting settings (AUTH-3). */
    public static String cacheKey(AccessPolicy policy, ChainContext ctx) {
        List<String> parts = new ArrayList<>(List.of(policy.provider(), orEmpty(ctx.env("AWS_PROFILE")), orEmpty(ctx.env("AZURE_TENANT_ID")),
            orEmpty(ctx.env("AZURE_CLIENT_ID")), orEmpty(ctx.env("GOOGLE_APPLICATION_CREDENTIALS")), orEmpty(ctx.env("CLOUDSDK_CONFIG")), ctx.home().toString()));
        for (Map.Entry<String, String> e : new TreeMap<>(ctx.settings()).entrySet()) parts.add(e.getKey() + "=" + e.getValue());
        return sha256Hex(String.join("\u001f", parts));
    }

    private static String orEmpty(String s) { return s == null ? "" : s; }

    public static CredentialProvider credentialProvider(AccessPolicy policy, ChainContext ctx) {
        return new CachingProvider(policy, ctx);
    }

    // ─── Harness ops (PROTOCOL.md token_exchange_build / token_exchange_parse) ──

    /** The exact token-exchange request a rung would send under the context's clock. */
    public static JsonObject tokenExchangeBuild(AccessPolicy policy, String rung, JsonObject inputs, ChainContext ctx) {
        if (rung.equals("adc-env") || rung.equals("adc-file") || rung.equals("service-account")) {
            JsonObject info = inputs.optObject("credential_file");
            if (info == null) throw ValidationException.value("token_exchange_build: credential_file is required");
            String scope = truthy(inputs, "scope") ? text(inputs, "scope") : GCP_SCOPE;
            var assertion = gcpServiceAccountAssertion(ctx, info, scope);
            return new JsonBuilder().put("method", "POST").put("url", assertion.getKey()).put("headers", Json.obj("content-type", "application/x-www-form-urlencoded"))
                .put("body_encoding", "form").put("body", Json.obj("grant_type", JWT_BEARER, "assertion", assertion.getValue())).build();
        }
        if (rung.equals("environment")) {
            var req = azureEnvironmentRequest(ctx, inputs.optString("jti"));
            JsonBuilder body = new JsonBuilder();
            for (Map.Entry<String, String> p : req.getValue()) body.put(p.getKey(), p.getValue());
            return new JsonBuilder().put("method", "POST").put("url", req.getKey()).put("headers", Json.obj("content-type", "application/x-www-form-urlencoded"))
                .put("body_encoding", "form").put("body", body.build()).build();
        }
        throw ValidationException.value("token_exchange_build: rung '" + rung + "' has no deterministic request");
    }

    /** The credential a rung produces from a pinned response body. */
    public static Credential tokenExchangeParse(AccessPolicy policy, String rung, int status, JsonObject body, ChainContext ctx) {
        Instant now = ctx.clock().now();
        switch (rung) {
            case "adc-env", "adc-file", "service-account", "environment", "workload-identity", "managed-identity", "metadata": {
                if (status < 200 || status >= 300) throw new AuthError(rung + ": HTTP " + status);
                return bearerFromOauth(body, now, rung);
            }
            case "credential_process": {
                if (status != 0 || !isOne(body.opt("Version"))) throw new AuthError("credential_process failed or returned an unsupported Version");
                return awsFromResponse(body);
            }
            case "imds", "container": {
                if (status < 200 || status >= 300) throw new AuthError(rung + ": HTTP " + status);
                return awsFromResponse(body);
            }
            default:
                throw ValidationException.value("token_exchange_parse: rung '" + rung + "' is not a parse vector");
        }
    }
}
