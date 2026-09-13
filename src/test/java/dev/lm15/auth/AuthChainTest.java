package dev.lm15.auth;

import dev.lm15.errors.NotConfiguredError;
import dev.lm15.registry.Registry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AUTH-1 through the one chain the router and the doctor share; AUTH-5 on every rendered surface. */
class AuthChainTest {
    private static final String SENTINEL = "SECRET-SENTINEL-DO-NOT-PRINT";

    private static List<String> kinds(AuthChain.Resolved r) { return r.steps().stream().map(s -> s.kind() + ":" + s.state()).toList(); }

    @Test
    void sharedExplicitKeyShadowsEnv() {
        AuthChain.Resolved r = AuthChain.resolve(Registry.require("openai-chat").access(), Map.of("openai", CredentialProvider.of(SENTINEL)),
            Map.of("OPENAI_API_KEY", SENTINEL), null, null, null, null);
        assertTrue(r.configured());
        assertEquals("api_keys", r.source());
        assertEquals(List.of("api_keys:selected", "env:OPENAI_API_KEY:shadowed"), kinds(r));
        assertEquals(new Credential.ApiKey(SENTINEL), r.credential().get());
        assertTrue(r.steps().get(0).source().contains("via 'openai'"));
        assertFalse(Doctor.explainAuth("openai-chat", Map.of("openai", CredentialProvider.of(SENTINEL)), Map.of("OPENAI_API_KEY", SENTINEL), null, null, null, null)
            .describe().contains(SENTINEL));
    }

    @Test
    void overlappingEnvListsDoNotShareAndAmbiguityRefuses() {
        AuthChain.Resolved r = AuthChain.resolve(Registry.require("gemini").access(), Map.of("vertex-express", CredentialProvider.of(SENTINEL)),
            Map.of(), null, null, null, null);
        assertFalse(r.configured());
        assertNull(r.credential());
        NotConfiguredError e = assertThrows(NotConfiguredError.class, () -> AuthChain.resolve(Registry.require("meta-anthropic").access(),
            Map.of("meta", CredentialProvider.of(SENTINEL), "meta-chat", CredentialProvider.of(SENTINEL)), Map.of(), null, null, null, null));
        assertFalse(e.getMessage().contains(SENTINEL));
        // Duplicate spellings of one provider are refused, never resolved by map order.
        assertThrows(NotConfiguredError.class, () -> AuthChain.resolve(Registry.require("openai-chat").access(),
            Map.of("openai-chat", CredentialProvider.of(SENTINEL), "openai_chat", CredentialProvider.of(SENTINEL)), Map.of(), null, null, null, null));
    }

    @Test
    void placeholderForKeylessLocalServers() {
        AuthChain.Resolved r = AuthChain.resolve(Registry.require("vllm").access(), Map.of("ollama", CredentialProvider.of(SENTINEL)), Map.of(), null, null, null, null);
        assertEquals(List.of("api_keys:absent", "placeholder:selected"), kinds(r));
        assertEquals("placeholder", r.source());
    }

    @Test
    void storedXaiLoginBeatsEnvAndExplicitBeatsStored(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("credentials.json");
        long fresh = System.currentTimeMillis() + 3_600_000;
        Files.writeString(store, "{\"xai\": {\"type\": \"oauth\", \"access\": \"" + SENTINEL + "\", \"expires\": " + fresh + ", \"refresh\": \"" + SENTINEL + "\"}}");
        AccessPolicy xai = Registry.require("xai").access();
        AuthChain.Resolved subscription = AuthChain.resolve(xai, null, Map.of("XAI_API_KEY", SENTINEL), store, null, null, null);
        assertEquals(List.of("api_keys:absent", "oauth-file:selected", "env:XAI_API_KEY:shadowed"), kinds(subscription));
        assertEquals(new Credential.BearerToken(SENTINEL), subscription.credential().get());
        AuthChain.Resolved explicit = AuthChain.resolve(xai, Map.of("xai", CredentialProvider.of("k")), Map.of(), store, null, null, null);
        assertEquals(List.of("api_keys:selected", "oauth-file:shadowed", "env:XAI_API_KEY:absent"), kinds(explicit));
        Files.writeString(store, "{\"xai\": {\"type\": \"oauth\", \"access\": \"" + SENTINEL + "\", \"expires\": 1}}");
        AuthChain.Resolved rescued = AuthChain.resolve(xai, null, Map.of("XAI_API_KEY", SENTINEL), store, null, null, null);
        assertEquals(List.of("api_keys:absent", "oauth-file:absent", "env:XAI_API_KEY:selected"), kinds(rescued));
        String rendered = Doctor.explainAuth("xai", null, Map.of("XAI_API_KEY", SENTINEL), store, null, null, null).describe();
        assertFalse(rendered.contains(SENTINEL));
        assertTrue(rendered.contains("expired, NO refresh token"));
    }

    @Test
    void oauthPolicyNeverFallsBackToEnv(@TempDir Path tmp) {
        AuthChain.Resolved r = AuthChain.resolve(Registry.require("claude-code").access(), null, Map.of("ANTHROPIC_API_KEY", SENTINEL),
            tmp.resolve("missing.json"), null, null, null);
        assertEquals(List.of("oauth-file:absent"), kinds(r));
        assertFalse(r.configured());
        assertThrows(NotConfiguredError.class, () -> r.require(Registry.require("claude-code").access()));
    }
}
