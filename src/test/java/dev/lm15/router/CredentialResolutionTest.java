package dev.lm15.router;

import dev.lm15.auth.Credential;
import dev.lm15.auth.CredentialProvider;
import dev.lm15.errors.NotConfiguredError;
import dev.lm15.registry.Registry;
import dev.lm15.types.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The AUTH-1 chain over an explicit env map (spec/auth.md AUTH-1; § Shared explicit keys, ratified 2026-09-09). */
class CredentialResolutionTest {

    private static Map<String, CredentialProvider> keys(Object... kv) {
        LinkedHashMap<String, CredentialProvider> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], v == null ? null : v instanceof String s ? CredentialProvider.of(s) : (CredentialProvider) v);
        }
        return m;
    }

    private static CredentialResolution.Resolved resolve(String provider, Map<String, CredentialProvider> apiKeys, Map<String, String> env) {
        return CredentialResolution.resolve(Registry.require(provider).access(), apiKeys, env, null);
    }

    private static String value(CredentialResolution.Resolved r) { return ((Credential.ApiKey) r.credential().get()).value(); }

    @Test void explicitKeyBeatsEnv() {
        CredentialResolution.Resolved r = resolve("openai", keys("openai", "sk-explicit"), Map.of("OPENAI_API_KEY", "sk-env"));
        assertEquals("api_keys", r.source());
        assertEquals("openai", r.sourceKey());
        assertEquals("sk-explicit", value(r));
    }

    @Test void envKeysAreReadInDeclaredOrderFirstNonEmptyWins() {
        CredentialResolution.Resolved r = resolve("gemini", Map.of(), Map.of("GEMINI_API_KEY", "", "GOOGLE_API_KEY", "g"));
        assertEquals("env", r.source());
        assertEquals("GOOGLE_API_KEY", r.sourceKey());
        assertEquals("g", value(r));
        assertEquals("GEMINI_API_KEY", resolve("gemini", Map.of(), Map.of("GEMINI_API_KEY", "a", "GOOGLE_API_KEY", "g")).sourceKey());
    }

    @Test void hermeticEnvNeverReadsTheProcessEnvironment() {
        NotConfiguredError e = assertThrows(NotConfiguredError.class, () -> resolve("anthropic", Map.of(), Map.of()));
        assertEquals(ErrorCode.NOT_CONFIGURED, e.code());
        assertEquals(List.of("ANTHROPIC_API_KEY"), e.envKeys());
        assertEquals("anthropic", e.provider());
        assertTrue(e.message().contains("ANTHROPIC_API_KEY"));
    }

    @Test void keylessLocalServersFallToThePlaceholder() {
        CredentialResolution.Resolved r = resolve("ollama", Map.of(), Map.of());
        assertEquals("placeholder", r.source());
        assertEquals("ollama", value(r));
        assertEquals("EMPTY", value(resolve("vllm", Map.of(), Map.of())));
        assertEquals("api_keys", resolve("ollama", keys("ollama", "mine"), Map.of()).source());
    }

    @Test void anExplicitEntryServesItsSiblingWithTheIdenticalEnvKeys() {
        CredentialProvider shared = CredentialProvider.of("sk-one");
        CredentialResolution.Resolved r = resolve("openai-chat", keys("openai", shared), Map.of("OPENAI_API_KEY", "sk-env"));
        assertEquals("api_keys", r.source());
        assertEquals("openai", r.sourceKey());
        assertSame(shared, r.credential());
        // Overlapping lists are not identical: gemini does not supply vertex-express.
        assertEquals("env", resolve("vertex-express", keys("gemini", "g"), Map.of("GOOGLE_API_KEY", "v")).source());
        // Empty lists never join local servers or OAuth stores.
        assertEquals("placeholder", resolve("sglang", keys("vllm", "x"), Map.of()).source());
        assertNull(CredentialResolution.apiKeysSource(keys("vllm", "x"), "ollama"));
    }

    @Test void anExactEntryWinsRegardlessOfSharedCandidates() {
        CredentialResolution.Resolved r = resolve("openai-chat", keys("openai", "sk-a", "openai_chat", "sk-b"), Map.of());
        assertEquals("openai_chat", r.sourceKey());
        assertEquals("sk-b", value(r));
    }

    @Test void severalSharedCandidatesAreAmbiguousNeverChosenByOrderOrValue() {
        Map<String, CredentialProvider> both = keys("moonshotai", "same", "moonshotai-responses", "same");
        NotConfiguredError e = assertThrows(NotConfiguredError.class, () -> resolve("moonshotai-anthropic", both, Map.of()));
        assertTrue(e.message().contains("'moonshotai'") && e.message().contains("'moonshotai-responses'"), e.message());
        assertTrue(!e.message().contains("same"), e.message());
    }

    @Test void credentialProvidersAreNeverInvokedDuringSelection() {
        CredentialProvider explosive = () -> { throw new IllegalStateException("invoked"); };
        assertEquals("openai", CredentialResolution.apiKeysSource(keys("openai", explosive), "openai-chat"));
        assertSame(explosive, resolve("openai", keys("openai", explosive), Map.of()).credential());
    }

    @Test void anEmptyExplicitCredentialIsAFailureNotAFallback() {
        NotConfiguredError e = assertThrows(NotConfiguredError.class,
            () -> resolve("openai", keys("openai", null), Map.of("OPENAI_API_KEY", "sk-env")));
        assertTrue(e.message().contains("empty credential under 'openai'"), e.message());
        // The builder keeps the empty entry (as null) so the lookup, not the fallback, sees it; even resolve() reports it.
        RouterConfig config = RouterConfig.builder().env(Map.of("OPENAI_API_KEY", "sk-env")).apiKey("openai", "").build();
        assertTrue(config.apiKeys().containsKey("openai"));
        assertNull(config.apiKeys().get("openai"));
        assertThrows(NotConfiguredError.class, () -> new LMRouter(config).resolve("gpt-4.1-mini"));
    }

    @Test void storedLoginPoliciesDeferToTheAdapterLoader() {
        // oauth: an env var never substitutes; the chain never runs.
        CredentialResolution.Resolved oauth = resolve("claude-code", Map.of(), Map.of("ANTHROPIC_API_KEY", "sk"));
        assertEquals("stored-login", oauth.source());
        assertNull(oauth.credential());
        // oauth-unless-explicit with no stored login: the declared env key is consulted.
        CredentialResolution.Resolved xai = resolve("xai", Map.of(), Map.of("XAI_API_KEY", "x"));
        assertEquals("env", xai.source());
        assertEquals("x", value(xai));
        // and an explicit entry wins over everything.
        assertEquals("api_keys", resolve("xai", keys("xai", "explicit"), Map.of("XAI_API_KEY", "x")).source());
        // nothing anywhere: the adapter raises the typed login-hint error.
        assertEquals("stored-login", resolve("xai", Map.of(), Map.of()).source());
    }
}
