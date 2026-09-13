package dev.lm15.auth;

import dev.lm15.errors.AuthError;
import dev.lm15.errors.NotConfiguredError;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The stored xAI subscription login (AUTH-8): the lm15-owned store, then the
 * Pi agent store ({@code ~/.pi/agent/auth.json}), both with the entry
 * {@code {"xai": {"type": "oauth", "access", "expires" (ms), "refresh"?}}}.
 * A refresh writes back to whichever file the credential came from, under
 * that file's lock with a double-checked re-read: xAI rotates refresh
 * tokens, so a refresh that is not persisted to its source bricks it.
 */
public final class XaiStore {
    private XaiStore() {}

    public static final String PROVIDER = "xai";
    public static final String CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828";
    public static final String DEVICE_CODE_URL = "https://auth.x.ai/oauth2/device/code";
    public static final String TOKEN_URL = "https://auth.x.ai/oauth2/token";
    public static final String OAUTH_SCOPE = "openid profile email offline_access grok-cli:access api:access";
    public static final String LOGIN_HINT = Access.XAI_LOGIN_HINT;
    static final long DEFAULT_TOKEN_LIFETIME_S = 3600;

    /** A credential and the file it came from. */
    public record Loaded(LocalOAuthCredential credential, Path path) {}

    public static Path piAgentPath(Path home) { return home.resolve(".pi").resolve("agent").resolve("auth.json"); }

    /** The store paths, in order: the lm15-owned store, then the Pi agent store. */
    public static List<Path> storePaths(Map<String, String> env, Path home) {
        Map<String, String> e = env == null ? System.getenv() : env;
        Path h = home != null ? home : CredentialLock.home(e);
        return List.of(CredentialFileStore.defaultCredentialsPath(e, h), piAgentPath(h));
    }

    public static List<Path> storePaths() { return storePaths(null, null); }

    static List<Path> pathsFor(Path override, List<Path> defaults) {
        return override != null ? List.of(override) : (defaults != null ? defaults : storePaths());
    }

    public static LocalOAuthCredential entryToCredential(JsonObject entry) {
        if (entry == null) return null;
        String access = OAuthHttp.nonEmptyString(entry, "access");
        if (access == null) return null;
        JsonValue expires = entry.opt("expires");
        Long expiresAt = expires != null && expires.isInt() ? expires.asLong() : null;
        return new LocalOAuthCredential(access, OAuthHttp.nonEmptyString(entry, "refresh"), expiresAt);
    }

    public static JsonObject credentialToEntry(LocalOAuthCredential credential, JsonObject current) {
        JsonBuilder entry = OAuthHttp.builder(current);
        entry.put("type", "oauth");
        entry.put("access", credential.accessToken());
        if (credential.refreshToken() != null) entry.put("refresh", credential.refreshToken());
        if (credential.expiresAt() != null) entry.put("expires", credential.expiresAt());
        return entry.build();
    }

    /** The first store holding an xAI entry, with its path; NotConfiguredError naming every path checked. */
    public static Loaded loadWithSource(List<Path> paths) {
        for (Path path : paths) {
            JsonObject data = OAuthHttp.readJsonFileOrNull(path);
            LocalOAuthCredential credential = data == null ? null : entryToCredential(data.optObject(PROVIDER));
            if (credential != null) return new Loaded(credential, path);
        }
        List<String> checked = new ArrayList<>();
        for (Path p : paths) checked.add(p.toString());
        throw OAuthHttp.notConfigured(PROVIDER, "No xAI OAuth credential found (checked: " + String.join(", ", checked) + ").", LOGIN_HINT);
    }

    public static Loaded loadWithSource(Path override) { return loadWithSource(pathsFor(override, null)); }

    public static LocalOAuthCredential load(Path override) { return loadWithSource(override).credential(); }

    public static LocalOAuthCredential read(List<Path> paths) {
        try {
            return loadWithSource(paths).credential();
        } catch (NotConfiguredError e) {
            return null;
        }
    }

    /** True when a stored xAI subscription credential exists and is usable (fresh, or expired with a refresh token). Files only. */
    public static boolean usable(List<Path> paths) {
        LocalOAuthCredential credential = read(paths);
        return credential != null && credential.usable();
    }

    public static boolean usable(Path override) { return usable(pathsFor(override, null)); }

    static LocalOAuthCredential credentialFromTokenResponse(JsonObject payload, String previousRefreshToken) {
        String access = OAuthHttp.nonEmptyString(payload, "access_token");
        if (access == null) throw new IllegalStateException("xAI token response is missing access_token");
        String refresh = OAuthHttp.nonEmptyString(payload, "refresh_token");
        if (refresh == null) refresh = previousRefreshToken; // xAI may omit refresh_token when it does not rotate it
        JsonValue expiresIn = payload.opt("expires_in");
        double lifetime = expiresIn != null && expiresIn.isNumber() && expiresIn.asDouble() > 0 ? expiresIn.asDouble() : DEFAULT_TOKEN_LIFETIME_S;
        return new LocalOAuthCredential(access, refresh, (long) (System.currentTimeMillis() + lifetime * 1000 - OAuthHttp.REFRESH_SKEW_MS));
    }

    public static LocalOAuthCredential refresh(String refreshToken) {
        OAuthHttp.Reply reply = OAuthHttp.postForm(TOKEN_URL, List.of(Map.entry("grant_type", "refresh_token"),
            Map.entry("client_id", CLIENT_ID), Map.entry("refresh_token", refreshToken)));
        if (!reply.ok()) throw new IllegalStateException("xAI token refresh failed");
        return credentialFromTokenResponse(reply.body(), refreshToken);
    }

    /** Write the credential into a store file (default: lm15's own store). */
    public static void write(LocalOAuthCredential credential, Path storePath) {
        new CredentialFileStore(storePath != null ? storePath : CredentialFileStore.defaultCredentialsPath())
            .mutate(PROVIDER, current -> credentialToEntry(credential, current));
    }

    /** A usable access token, refreshing (and persisting to the source file) when expired. */
    public static String accessToken(List<Path> paths, boolean refresh) {
        Loaded loaded = loadWithSource(paths);
        LocalOAuthCredential credential = loaded.credential();
        if (!credential.expired()) return credential.accessToken();
        if (!refresh || credential.refreshToken() == null) {
            throw OAuthHttp.authError(PROVIDER, "xAI OAuth token is expired and no refresh token is available.", LOGIN_HINT);
        }
        String[] result = new String[1];
        new CredentialFileStore(loaded.path()).mutate(PROVIDER, current -> {
            // Double-checked refresh: another process may have refreshed (and rotated the refresh token) while we waited.
            LocalOAuthCredential fresh = entryToCredential(current);
            if (fresh != null && !fresh.expired()) {
                result[0] = fresh.accessToken();
                return null;
            }
            String refreshToken = fresh != null && fresh.refreshToken() != null ? fresh.refreshToken() : credential.refreshToken();
            if (refreshToken == null) throw OAuthHttp.authError(PROVIDER, "xAI OAuth token is expired and no refresh token is available.", LOGIN_HINT);
            LocalOAuthCredential refreshed;
            try {
                refreshed = refresh(refreshToken);
            } catch (AuthError e) {
                throw e;
            } catch (RuntimeException e) {
                throw OAuthHttp.authError(PROVIDER, "xAI OAuth token is expired and the refresh attempt failed.", LOGIN_HINT);
            }
            result[0] = refreshed.accessToken();
            return credentialToEntry(refreshed, current);
        });
        return result[0];
    }

    public static String accessToken(Path override) { return accessToken(pathsFor(override, null), true); }
}
