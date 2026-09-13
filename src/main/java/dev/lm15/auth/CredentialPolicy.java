package dev.lm15.auth;

import dev.lm15.types.ValidationException;

/** How a provider's credential is obtained (spec/auth.md AUTH-1). */
public enum CredentialPolicy {
    KEY("key"), OAUTH("oauth"), OAUTH_UNLESS_EXPLICIT("oauth-unless-explicit"),
    AWS_CHAIN("aws-chain"), AZURE_CHAIN("azure-chain"), GCP_CHAIN("gcp-chain");

    private final String wire;
    CredentialPolicy(String wire) { this.wire = wire; }
    public String wire() { return wire; }
    @Override public String toString() { return wire; }

    public boolean isCloudChain() { return this == AWS_CHAIN || this == AZURE_CHAIN || this == GCP_CHAIN; }

    public static CredentialPolicy fromWire(String value) {
        for (CredentialPolicy p : values()) if (p.wire.equals(value)) return p;
        throw ValidationException.value("unknown credential_policy: " + value);
    }
}
