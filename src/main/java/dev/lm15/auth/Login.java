package dev.lm15.auth;

import dev.lm15.errors.AuthError;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.registry.Registry;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The uniform login door (spec/auth.md AUTH-9): {@code login(provider)}
 * runs the flow lm15 owns (today: xAI's RFC 8628 device-code flow) and
 * returns the stored credential; every other provider fails typed, naming
 * the exact fix — the foreign CLI command that owns the flow or the console
 * URL where an API key is created. Nothing here prompts, opens a browser,
 * or spends money except the one flow explicitly asked for.
 */
public final class Login {
    private Login() {}

    /** Console URLs are guidance strings, not wire facts: drift costs a stale hint, never broken inference. */
    static final Map<String, String> KEY_CONSOLE_URLS = Map.of(
        "openai", "https://platform.openai.com/api-keys",
        "openai-chat", "https://platform.openai.com/api-keys",
        "anthropic", "https://console.anthropic.com",
        "gemini", "https://aistudio.google.com/apikey",
        "groq", "https://console.groq.com/keys",
        "openrouter", "https://openrouter.ai/keys",
        "xai", "https://console.x.ai");

    static final Map<String, String> CLI_LOGIN_HINTS = Map.of(
        "claude-code", Access.CLAUDE_CODE_LOGIN_HINT,
        "openai-codex", Access.OPENAI_CODEX_LOGIN_HINT);

    static final Set<String> KEYLESS_LOCAL_SERVERS = Set.of("ollama", "vllm", "sglang");

    /** One pending device authorization. The device code is never rendered. */
    public record DeviceAuthorization(String userCode, String verificationUri, String verificationUriComplete, double intervalS,
                                      double expiresInS, String deviceCode) {
        @Override public String toString() {
            return "DeviceAuthorization(user_code=" + userCode + ", verification_uri=" + verificationUri + ")";
        }
    }

    private static String httpsOrRaise(JsonValue raw) {
        if (raw != null && raw.isString()) {
            try {
                URI parsed = new URI(raw.asString());
                if ("https".equals(parsed.getScheme()) && parsed.getAuthority() != null && !parsed.getAuthority().isEmpty()) return raw.asString();
            } catch (java.net.URISyntaxException ignored) {
                // fall through
            }
        }
        throw new AuthError("xAI device authorization returned an untrusted verification URI.", ErrorMeta.of("xai"));
    }

    static DeviceAuthorization parseDeviceAuthorization(JsonObject payload) {
        String deviceCode = OAuthHttp.nonEmptyString(payload, "device_code");
        String userCode = OAuthHttp.nonEmptyString(payload, "user_code");
        if (deviceCode == null || userCode == null) throw new AuthError("xAI device authorization response is missing required fields.", ErrorMeta.of("xai"));
        JsonValue expiresIn = payload.opt("expires_in");
        if (expiresIn == null || !expiresIn.isNumber() || expiresIn.asDouble() <= 0) {
            throw new AuthError("xAI device authorization response is missing expires_in.", ErrorMeta.of("xai"));
        }
        JsonValue interval = payload.opt("interval");
        double intervalS = interval != null && interval.isNumber() && interval.asDouble() > 0 ? interval.asDouble() : 5.0;
        JsonValue complete = payload.opt("verification_uri_complete");
        return new DeviceAuthorization(userCode, httpsOrRaise(payload.opt("verification_uri")),
            complete != null && complete.isString() && !complete.asString().isEmpty() ? httpsOrRaise(complete) : null,
            intervalS, expiresIn.asDouble(), deviceCode);
    }

    /** Request an xAI device authorization; show the user code, then poll. */
    public static DeviceAuthorization startXaiDeviceLogin() {
        OAuthHttp.Reply reply = OAuthHttp.postForm(XaiStore.DEVICE_CODE_URL, List.of(Map.entry("client_id", XaiStore.CLIENT_ID),
            Map.entry("scope", XaiStore.OAUTH_SCOPE), Map.entry("referrer", "lm15")));
        if (!reply.ok()) {
            String detail = OAuthHttp.nonEmptyString(reply.body(), "error_description");
            if (detail == null) detail = OAuthHttp.nonEmptyString(reply.body(), "error");
            throw new AuthError("xAI device authorization failed: " + (detail == null ? "request failed" : detail), ErrorMeta.of("xai"));
        }
        return parseDeviceAuthorization(reply.body());
    }

    /** Poll the token endpoint until the user approves; return the credential. */
    public static LocalOAuthCredential pollXaiDeviceLogin(DeviceAuthorization device, DevicePolling.Sleeper sleep) {
        Object value = DevicePolling.poll(() -> {
            OAuthHttp.Reply reply = OAuthHttp.postForm(XaiStore.TOKEN_URL, List.of(Map.entry("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                Map.entry("client_id", XaiStore.CLIENT_ID), Map.entry("device_code", device.deviceCode())));
            if (reply.ok()) return new DevicePolling.Complete(XaiStore.credentialFromTokenResponse(reply.body(), null));
            String error = OAuthHttp.nonEmptyString(reply.body(), "error");
            if ("authorization_pending".equals(error)) return new DevicePolling.Pending();
            if ("slow_down".equals(error)) {
                JsonValue interval = reply.body().opt("interval");
                return new DevicePolling.SlowDown(interval != null && interval.isNumber() && interval.asDouble() > 0 ? interval.asDouble() : null);
            }
            if ("access_denied".equals(error) || "authorization_denied".equals(error)) return new DevicePolling.Failed("xAI device authorization was denied.");
            if ("expired_token".equals(error)) return new DevicePolling.Failed("xAI device code expired before it was approved.");
            String detail = OAuthHttp.nonEmptyString(reply.body(), "error_description");
            if (detail == null) detail = error == null ? "request failed" : error;
            return new DevicePolling.Failed("xAI device token polling failed: " + detail);
        }, device.intervalS(), device.expiresInS(), "xai", true, sleep, DevicePolling.MONOTONIC);
        return (LocalOAuthCredential) value;
    }

    /** Interactive device-code login; persists the credential (to lm15's own store unless {@code storePath} overrides it) and returns it. */
    public static LocalOAuthCredential xai(Path storePath, Consumer<String> echo) {
        DeviceAuthorization device = startXaiDeviceLogin();
        String target = device.verificationUriComplete() != null ? device.verificationUriComplete() : device.verificationUri();
        echo.accept("Open " + target + " and enter code: " + device.userCode());
        LocalOAuthCredential credential = pollXaiDeviceLogin(device, DevicePolling.REAL_SLEEP);
        XaiStore.write(credential, storePath);
        return credential;
    }

    public static LocalOAuthCredential xai() { return xai(null, System.out::println); }

    /** Run the login flow lm15 owns for {@code provider}; fail typed otherwise. */
    public static LocalOAuthCredential login(String provider, Path credentialsPath, Consumer<String> echo) {
        String canonical = Registry.canonicalProvider(provider);
        if (canonical.equals("xai")) return xai(credentialsPath, echo);
        if (CLI_LOGIN_HINTS.containsKey(canonical)) {
            throw new UnsupportedFeatureError("lm15 does not own the '" + canonical + "' login flow — the provider CLI does. " + CLI_LOGIN_HINTS.get(canonical),
                ErrorMeta.of(canonical));
        }
        if (KEYLESS_LOCAL_SERVERS.contains(canonical)) {
            throw new UnsupportedFeatureError("'" + canonical + "' is a keyless local server — there is nothing to log into. "
                + "The router sends the placeholder key the server expects.", ErrorMeta.of(canonical));
        }
        if (KEY_CONSOLE_URLS.containsKey(canonical)) {
            throw new UnsupportedFeatureError("'" + canonical + "' offers no OAuth login flow — only manually created API keys. Create one at "
                + KEY_CONSOLE_URLS.get(canonical) + " and set it in the environment or RouterConfig(api_keys={\"" + canonical + "\": \"...\"}).",
                ErrorMeta.of(canonical));
        }
        throw new UnsupportedFeatureError("lm15 has no login flow for '" + provider + "'. Supply an API key via the environment or RouterConfig(api_keys=...).",
            ErrorMeta.of(canonical));
    }

    public static LocalOAuthCredential login(String provider) { return login(provider, null, System.out::println); }
}
