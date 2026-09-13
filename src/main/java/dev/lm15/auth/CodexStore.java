package dev.lm15.auth;

import dev.lm15.errors.NotConfiguredError;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The borrowed OpenAI Codex CLI login ({@code ~/.codex/auth.json}, AUTH-8):
 * {@code {"tokens": {access_token, refresh_token?, account_id?, id_token?}}}.
 * The access token is a JWT whose {@code exp} sets the expiry and whose
 * {@code https://api.openai.com/auth.chatgpt_account_id} claim is the
 * account id the {@code chatgpt-account-id} header carries.
 */
public final class CodexStore {
    private CodexStore() {}

    public static final String PROVIDER = "openai-codex";
    public static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    public static final String TOKEN_URL = "https://auth.openai.com/oauth/token";
    public static final String JWT_CLAIM_PATH = "https://api.openai.com/auth";
    public static final String LOGIN_HINT = Access.OPENAI_CODEX_LOGIN_HINT;

    public static Path defaultPath() { return Path.of(System.getProperty("user.home")).resolve(".codex").resolve("auth.json"); }

    static Path coerce(Path path) { return path != null ? path : defaultPath(); }

    public static String extractChatgptAccountId(String token) {
        try {
            JsonObject claim = OAuthHttp.decodeJwtPayload(token).optObject(JWT_CLAIM_PATH);
            return OAuthHttp.nonEmptyString(claim, "chatgpt_account_id");
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static LocalOAuthCredential load(Path authPath) {
        Path path = coerce(authPath);
        JsonObject data = OAuthHttp.readJsonFile(path, PROVIDER, LOGIN_HINT);
        JsonObject tokens = data.optObject("tokens");
        if (tokens == null) throw OAuthHttp.notConfigured(PROVIDER, "Credentials file at " + path + " has no tokens section.", LOGIN_HINT);
        String access = OAuthHttp.nonEmptyString(tokens, "access_token");
        if (access == null) throw OAuthHttp.notConfigured(PROVIDER, "Credentials file at " + path + " has no access token.", LOGIN_HINT);
        String accountId = OAuthHttp.nonEmptyString(tokens, "account_id");
        if (accountId == null) accountId = extractChatgptAccountId(access);
        return new LocalOAuthCredential(access, OAuthHttp.nonEmptyString(tokens, "refresh_token"), OAuthHttp.jwtExpiresAtMs(access), accountId);
    }

    public static LocalOAuthCredential read(Path authPath) {
        try {
            return load(authPath);
        } catch (NotConfiguredError e) {
            return null;
        }
    }

    public static LocalOAuthCredential refresh(String refreshToken) {
        OAuthHttp.Reply reply = OAuthHttp.postForm(TOKEN_URL, List.of(Map.entry("grant_type", "refresh_token"),
            Map.entry("refresh_token", refreshToken), Map.entry("client_id", CLIENT_ID)));
        String access = OAuthHttp.nonEmptyString(reply.body(), "access_token");
        String refresh = OAuthHttp.nonEmptyString(reply.body(), "refresh_token");
        if (refresh == null) refresh = refreshToken;
        if (!reply.ok() || access == null) throw new IllegalStateException("Codex token refresh response is missing required fields");
        return new LocalOAuthCredential(access, refresh, OAuthHttp.jwtExpiresAtMs(access), extractChatgptAccountId(access));
    }

    private static void writeUnlocked(LocalOAuthCredential credential, Path path, String idToken) {
        JsonObject data = OAuthHttp.readJsonFileOrNull(path);
        if (data == null) data = JsonObject.EMPTY;
        JsonBuilder current = OAuthHttp.builder(data.optObject("tokens"));
        current.put("access_token", credential.accessToken());
        if (credential.refreshToken() != null) current.put("refresh_token", credential.refreshToken());
        if (credential.accountId() != null) current.put("account_id", credential.accountId());
        if (idToken != null) current.put("id_token", idToken);
        JsonBuilder out = data.toBuilder().put("tokens", current.build());
        if (!data.has("auth_mode")) out.put("auth_mode", "chatgpt");
        out.put("last_refresh", Rfc3339.format(Instant.now()));
        CredentialLock.writePrivateJsonAtomic(path, out.build());
    }

    public static void write(LocalOAuthCredential credential, Path authPath, String idToken) {
        Path path = coerce(authPath);
        CredentialLock.withLock(path, () -> writeUnlocked(credential, path, idToken));
    }

    /** A usable credential, refreshing it (under the lock, double-checked) when expired. */
    public static LocalOAuthCredential credential(Path authPath, boolean refresh) {
        LocalOAuthCredential credential = load(authPath);
        if (!credential.expired()) return credential;
        if (!refresh || credential.refreshToken() == null) {
            throw OAuthHttp.authError(PROVIDER, "Codex CLI OAuth token is expired and no refresh token is available.", LOGIN_HINT);
        }
        Path path = coerce(authPath);
        return CredentialLock.withLock(path, CredentialLock.DEFAULT_TIMEOUT, () -> {
            LocalOAuthCredential again = load(path);
            if (!again.expired()) return again;
            if (again.refreshToken() == null) {
                throw OAuthHttp.authError(PROVIDER, "Codex CLI OAuth token is expired and no refresh token is available.", LOGIN_HINT);
            }
            LocalOAuthCredential refreshed;
            try {
                refreshed = refresh(again.refreshToken());
            } catch (RuntimeException e) {
                throw OAuthHttp.authError(PROVIDER, "Codex CLI OAuth token is expired and the refresh attempt failed.", LOGIN_HINT);
            }
            JsonObject original = OAuthHttp.readJsonFileOrNull(path);
            JsonObject tokens = original == null ? null : original.optObject("tokens");
            JsonValue idToken = tokens == null ? null : tokens.opt("id_token");
            writeUnlocked(refreshed, path, idToken != null && idToken.isString() ? idToken.asString() : null);
            return refreshed;
        });
    }

    public static String accessToken(Path authPath) { return credential(authPath, true).accessToken(); }
}
