package dev.lm15.cloud;

import dev.lm15.auth.AccessPolicy;
import dev.lm15.auth.Credential;
import dev.lm15.json.Json;
import dev.lm15.json.JsonObject;
import dev.lm15.registry.Registry;
import dev.lm15.wire.Clock;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The offline chain walk (AUTH-7 over AUTH-1 cloud rungs), a parse vector, and the pinned RS256 assertion. */
class ChainsTest {
    private static final String SENTINEL = "SECRET-SENTINEL-DO-NOT-PRINT";

    private static List<String> kinds(List<Chains.Step> steps) { return steps.stream().map(s -> s.kind() + ":" + s.state()).toList(); }

    @Test
    void ssoProfileIsUnprobedOffline() {
        AccessPolicy policy = Registry.require("bedrock-anthropic").access();
        Path home = Path.of("/sandbox/home");
        Map<String, String> files = Map.of("~/.aws/config",
            "[profile work]\nsso_session = corp\nsso_account_id = 111122223333\nsso_role_name = Dev\nregion = us-east-1\n\n[sso-session corp]\nsso_start_url = https://corp.awsapps.com/start\nsso_region = us-east-1\n");
        ChainContext ctx = ChainContext.offline(Map.of("AWS_PROFILE", "work", "AWS_EC2_METADATA_DISABLED", "true", "HOME", home.toString()), home, files);
        Map.Entry<List<Chains.Step>, Boolean> walk = Chains.explain(policy, ctx, false);
        assertTrue(walk.getValue());
        assertEquals(List.of("api_keys:absent", "env:AWS_BEARER_TOKEN_BEDROCK:absent", "env:AWS_ACCESS_KEY_ID:absent", "assume-role:absent",
            "web-identity:absent", "sso:unprobed", "shared-credentials-file:absent", "login:absent", "credential_process:absent", "config-file:absent",
            "container:absent", "imds:absent"), kinds(walk.getKey()));
        assertEquals("us-east-1", Chains.profileSettings(policy, ctx).apply("region"));
    }

    @Test
    void bearerEnvBeatsStaticKeysAndNothingRendersTheSecret() {
        AccessPolicy policy = Registry.require("bedrock-chat").access();
        ChainContext ctx = ChainContext.offline(Map.of("AWS_BEARER_TOKEN_BEDROCK", SENTINEL, "AWS_ACCESS_KEY_ID", "AKIDEXAMPLE",
            "AWS_SECRET_ACCESS_KEY", SENTINEL), Path.of("/sandbox/home"), Map.of());
        Map.Entry<List<Chains.Step>, Boolean> walk = Chains.explain(policy, ctx, false);
        assertEquals("env:AWS_BEARER_TOKEN_BEDROCK:selected", kinds(walk.getKey()).get(1));
        assertEquals("env:AWS_ACCESS_KEY_ID:shadowed", kinds(walk.getKey()).get(2));
        assertEquals("imds:shadowed", kinds(walk.getKey()).get(kinds(walk.getKey()).size() - 1));
        assertFalse(walk.getKey().toString().contains(SENTINEL));
        assertEquals(new Credential.BearerToken(SENTINEL), Chains.chainFor(policy).get(0).acquire().apply(ctx));
    }

    @Test
    void credentialProcessParseVector() {
        ChainContext ctx = new ChainContext(Map.of(), null, null, null, null, Clock.fixed(Instant.parse("2026-09-03T12:00:00Z")));
        JsonObject body = Json.obj("Version", 1, "AccessKeyId", "AKIDEXAMPLE", "SecretAccessKey", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            "SessionToken", "SESSION-VECTOR-NOT-A-SECRET", "Expiration", "2026-09-03T13:00:00Z");
        Credential c = Chains.tokenExchangeParse(Registry.require("bedrock-anthropic").access(), "credential_process", 0, body, ctx);
        assertEquals(new Credential.AwsCredentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "SESSION-VECTOR-NOT-A-SECRET",
            Instant.parse("2026-09-03T13:00:00Z")), c);
        Credential msi = Chains.tokenExchangeParse(Registry.require("azure").access(), "managed-identity", 200,
            Json.obj("access_token", "TOKEN-VECTOR-MSI-NOT-A-SECRET", "expires_in", "3599", "expires_on", "1788440399"), ctx);
        assertEquals(new Credential.BearerToken("TOKEN-VECTOR-MSI-NOT-A-SECRET", Instant.parse("2026-09-03T12:59:59Z")), msi);
    }

    @Test
    void gcpServiceAccountAssertionIsPinnedByteForByte() throws Exception {
        Path key = Path.of("..", "lm15-contract", "auth", "test-keys", "rsa-2048-test-only.pem");
        Assumptions.assumeTrue(Files.exists(key), "the contract checkout supplies the corpus test key");
        String pem = String.join("\n", Files.readAllLines(key).stream().filter(l -> !l.startsWith("#")).toList()) + "\n";
        ChainContext ctx = new ChainContext(Map.of(), null, null, null, null, Clock.fixed(Instant.parse("2026-09-03T12:00:00Z")));
        JsonObject info = Json.obj("type", "service_account", "private_key_id", "lm15testkeyid0001", "private_key", pem,
            "client_email", "lm15-test@lm15-test-project.iam.gserviceaccount.com", "token_uri", "https://oauth2.googleapis.com/token");
        Map.Entry<String, String> assertion = Chains.gcpServiceAccountAssertion(ctx, info, Chains.GCP_SCOPE);
        assertEquals("https://oauth2.googleapis.com/token", assertion.getKey());
        assertTrue(assertion.getValue().startsWith("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6ImxtMTV0ZXN0a2V5aWQwMDAxIn0."
            + "eyJpYXQiOjE3ODg0MzY4MDAsImV4cCI6MTc4ODQ0MDQwMCwiaXNzIjoibG0xNS10ZXN0QGxtMTUtdGVzdC1wcm9qZWN0LmlhbS5nc2VydmljZWFjY291bnQuY29tIiwiYXVkIjoiaHR0cHM6Ly9vYXV0aDIuZ29vZ2xlYXBpcy5jb20vdG9rZW4iLCJzY29wZSI6Imh0dHBzOi8vd3d3Lmdvb2dsZWFwaXMuY29tL2F1dGgvY2xvdWQtcGxhdGZvcm0ifQ."
            + "bhhPzzBMvjuFVDE87H4hzlkqjOJv40cubzqKs_lIoq6m8opHNlYd3BPzgmtxqnl7NwggKGb9AzOvKvkveYexjSYDOBKh3BGZdK0CO8DGOWIZ6xpDL8IQ6DWtWQ-GKNU2cVppfgRxIPlQonzwCbYMR4Ny3issyBMwHCQjYAhHN_gAaYVMQ07wsGe5TenWfagYIzZTDpFS0pfGZrAP-CEsmrJeRGR_hOKzxk"));
    }
}
