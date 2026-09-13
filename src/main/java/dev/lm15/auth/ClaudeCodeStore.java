package dev.lm15.auth;

import dev.lm15.errors.NotConfiguredError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonObject;

import java.nio.file.Path;

/**
 * The borrowed Claude Code login ({@code ~/.claude/.credentials.json},
 * spec/auth.md AUTH-8): {@code {"claudeAiOauth": {accessToken, expiresAt (ms),
 * refreshToken?}}}. A foreign format, revalidated, never cleaned. Refresh
 * runs under the lock with a double-checked re-read (AUTH-3/AUTH-4).
 */
public final class ClaudeCodeStore {
    private ClaudeCodeStore() {}

    public static final String PROVIDER = "claude-code";
    public static final String CLIENT_ID = "9d1c250a-e61b-44d5-88ed-5944d1962f5e";
    public static final String TOKEN_URL = "https://platform.claude.com/v1/oauth/token";
    public static final String LOGIN_HINT = Access.CLAUDE_CODE_LOGIN_HINT;

    public static Path defaultPath() { return Path.of(System.getProperty("user.home")).resolve(".claude").resolve(".credentials.json"); }

    static Path coerce(Path path) { return path != null ? path : defaultPath(); }

    /** Load the Claude Code OAuth credential, raising typed errors. */
    public static LocalOAuthCredential load(Path credentialsPath) {
        Path path = coerce(credentialsPath);
        JsonObject data = OAuthHttp.readJsonFile(path, PROVIDER, LOGIN_HINT);
        JsonObject raw = data.optObject("claudeAiOauth");
        if (raw == null) throw OAuthHttp.notConfigured(PROVIDER, "Credentials file at " + path + " has no claudeAiOauth section.", LOGIN_HINT);
        String access = OAuthHttp.nonEmptyString(raw, "accessToken");
        if (access == null) throw OAuthHttp.notConfigured(PROVIDER, "Credentials file at " + path + " has no access token.", LOGIN_HINT);
        return new LocalOAuthCredential(access, OAuthHttp.nonEmptyString(raw, "refreshToken"), OAuthHttp.integer(raw, "expiresAt"));
    }

    /** Optional-style loader: null when no usable credential exists. */
    public static LocalOAuthCredential read(Path credentialsPath) {
        try {
            return load(credentialsPath);
        } catch (NotConfiguredError e) {
            return null;
        }
    }

    public static LocalOAuthCredential refresh(String refreshToken) {
        OAuthHttp.Reply reply = OAuthHttp.postJson(TOKEN_URL, Json.obj("grant_type", "refresh_token", "client_id", CLIENT_ID, "refresh_token", refreshToken));
        String access = OAuthHttp.nonEmptyString(reply.body(), "access_token");
        String refresh = OAuthHttp.nonEmptyString(reply.body(), "refresh_token");
        Long expiresIn = OAuthHttp.integer(reply.body(), "expires_in");
        if (!reply.ok() || access == null || refresh == null || expiresIn == null) {
            throw new IllegalStateException("Claude Code token refresh response is missing required fields");
        }
        return new LocalOAuthCredential(access, refresh, System.currentTimeMillis() + expiresIn * 1000 - OAuthHttp.REFRESH_SKEW_MS);
    }

    private static void writeUnlocked(LocalOAuthCredential credential, Path path) {
        JsonObject data = OAuthHttp.readJsonFileOrNull(path);
        if (data == null) data = JsonObject.EMPTY;
        JsonBuilder current = OAuthHttp.builder(data.optObject("claudeAiOauth"));
        current.put("accessToken", credential.accessToken());
        if (credential.refreshToken() != null) current.put("refreshToken", credential.refreshToken());
        if (credential.expiresAt() != null) current.put("expiresAt", credential.expiresAt());
        CredentialLock.writePrivateJsonAtomic(path, data.with("claudeAiOauth", current.build()));
    }

    public static void write(LocalOAuthCredential credential, Path credentialsPath) {
        Path path = coerce(credentialsPath);
        CredentialLock.withLock(path, () -> writeUnlocked(credential, path));
    }

    /** A usable access token, refreshing it (under the lock, double-checked) when expired. */
    public static String accessToken(Path credentialsPath, boolean refresh) {
        LocalOAuthCredential credential = load(credentialsPath);
        if (!credential.expired()) return credential.accessToken();
        if (!refresh || credential.refreshToken() == null) {
            throw OAuthHttp.authError(PROVIDER, "Claude Code OAuth token is expired and no refresh token is available.", LOGIN_HINT);
        }
        Path path = coerce(credentialsPath);
        return CredentialLock.withLock(path, CredentialLock.DEFAULT_TIMEOUT, () -> {
            // Double-checked refresh: another process may have refreshed (and rotated the refresh token) while we waited.
            LocalOAuthCredential again = load(path);
            if (!again.expired()) return again.accessToken();
            if (again.refreshToken() == null) {
                throw OAuthHttp.authError(PROVIDER, "Claude Code OAuth token is expired and no refresh token is available.", LOGIN_HINT);
            }
            LocalOAuthCredential refreshed;
            try {
                refreshed = refresh(again.refreshToken());
            } catch (RuntimeException e) {
                throw OAuthHttp.authError(PROVIDER, "Claude Code OAuth token is expired and the refresh attempt failed.", LOGIN_HINT);
            }
            writeUnlocked(refreshed, path);
            return refreshed.accessToken();
        });
    }

    public static String accessToken(Path credentialsPath) { return accessToken(credentialsPath, true); }
}
