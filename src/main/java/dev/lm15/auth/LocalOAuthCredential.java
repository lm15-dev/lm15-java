package dev.lm15.auth;

/**
 * A locally stored OAuth credential (a borrowed CLI file or the lm15-owned
 * store). {@code expiresAt} is epoch milliseconds; absent means no recorded
 * expiry. Token fields never render (AUTH-5).
 */
public record LocalOAuthCredential(String accessToken, String refreshToken, Long expiresAt, String accountId) {
    public LocalOAuthCredential(String accessToken, String refreshToken, Long expiresAt) { this(accessToken, refreshToken, expiresAt, null); }

    public boolean expired() { return expiresAt != null && System.currentTimeMillis() >= expiresAt; }

    /** Fresh, or expired with a refresh token to refresh it at request time. */
    public boolean usable() { return !expired() || (refreshToken != null && !refreshToken.isEmpty()); }

    @Override public String toString() {
        return "LocalOAuthCredential(<redacted>" + (expiresAt == null ? "" : ", expires_at=" + expiresAt)
            + (accountId == null ? "" : ", account_id=" + accountId) + ")";
    }
}
