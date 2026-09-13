package dev.lm15.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** PKCE, RFC 7636, S256 only ({@code plain} is a downgrade and is not offered). The Appendix B vector is a required test (AUTH-9). */
public final class Pkce {
    private Pkce() {}

    /** A verifier (secret until the code exchange; never rendered) and its S256 challenge. */
    public record Pair(String verifier, String challenge, String method) {
        @Override public String toString() { return "Pkce.Pair(challenge=" + challenge + ", method=" + method + ")"; }
    }

    /** S256 code challenge for {@code verifier} (RFC 7636 §4.2). */
    public static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fresh PKCE pair: 64 random bytes → 86-char base64url verifier. */
    public static Pair generate() {
        byte[] random = new byte[64];
        new SecureRandom().nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        return new Pair(verifier, challenge(verifier), "S256");
    }
}
