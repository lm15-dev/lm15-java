package dev.lm15.auth;

import dev.lm15.types.ValidationException;

import java.time.Duration;
import java.time.Instant;

/**
 * A credential is a closed sum, not a string (spec/auth.md AUTH-2):
 * {@link ApiKey}, {@link BearerToken} or {@link AwsCredentials}. A plain
 * string anywhere a credential is accepted reads as an {@code ApiKey}.
 * {@code expiresAt} is UTC; absent means non-expiring. Secrecy (AUTH-5):
 * {@code toString} never shows values.
 */
public sealed interface Credential permits Credential.ApiKey, Credential.BearerToken, Credential.AwsCredentials {
    /** AUTH-3: a token inside the five-minute skew window counts as expired. */
    Duration EXPIRY_SKEW = Duration.ofSeconds(300);

    /** The {@code kind} discriminator: api_key, bearer_token, aws. */
    String kind();

    default boolean isExpired(Instant now) { return false; }

    static Credential of(String apiKey) { return new ApiKey(apiKey); }

    static boolean expired(Instant expiresAt, Instant now) {
        if (expiresAt == null) return false;
        Instant current = now != null ? now : Instant.now();
        return !Duration.between(current, expiresAt).minus(EXPIRY_SKEW).isPositive();
    }

    record ApiKey(String value) implements Credential {
        public ApiKey {
            if (value == null || value.isEmpty()) throw ValidationException.value("ApiKey.value must be a non-empty string");
        }
        @Override public String kind() { return "api_key"; }
        @Override public String toString() { return "ApiKey(<redacted>)"; }
    }

    record BearerToken(String value, Instant expiresAt) implements Credential {
        public BearerToken {
            if (value == null || value.isEmpty()) throw ValidationException.value("BearerToken.value must be a non-empty string");
        }
        public BearerToken(String value) { this(value, null); }
        @Override public String kind() { return "bearer_token"; }
        @Override public boolean isExpired(Instant now) { return expired(expiresAt, now); }
        @Override public String toString() {
            return "BearerToken(<redacted>" + (expiresAt == null ? "" : ", expires_at=" + Rfc3339.format(expiresAt)) + ")";
        }
    }

    record AwsCredentials(String accessKeyId, String secretAccessKey, String sessionToken, Instant expiresAt) implements Credential {
        public AwsCredentials {
            if (accessKeyId == null || accessKeyId.isEmpty() || secretAccessKey == null || secretAccessKey.isEmpty()) {
                throw ValidationException.value("AwsCredentials needs non-empty string access_key_id and secret_access_key");
            }
            if (sessionToken != null && sessionToken.isEmpty()) throw ValidationException.value("AwsCredentials.session_token must be a non-empty string");
        }
        public AwsCredentials(String accessKeyId, String secretAccessKey) { this(accessKeyId, secretAccessKey, null, null); }
        @Override public String kind() { return "aws"; }
        @Override public boolean isExpired(Instant now) { return expired(expiresAt, now); }
        @Override public String toString() {
            return "AwsCredentials(access_key_id=" + accessKeyId + ", <redacted>" + (expiresAt == null ? "" : ", expires_at=" + Rfc3339.format(expiresAt)) + ")";
        }
    }
}
