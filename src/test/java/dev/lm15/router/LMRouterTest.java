package dev.lm15.router;

import dev.lm15.errors.AmbiguousModelError;
import dev.lm15.errors.NotConfiguredError;
import dev.lm15.errors.UnknownModelError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonObject;
import dev.lm15.types.ErrorCode;
import dev.lm15.types.ModelInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three rungs, their precedence, and the refusals (router/resolution.json; lm15-python tests/test_router.py). */
class LMRouterTest {

    private static LMRouter hermetic() { return new LMRouter(RouterConfig.builder().env(Map.of()).build()); }

    private static LMRouter withCatalog(ModelInfo... infos) {
        return new LMRouter(RouterConfig.builder().env(Map.of()).catalog(List.of(infos)).build());
    }

    private static ModelInfo info(String id, String provider, String... aliases) {
        return new ModelInfo(id, provider, "openai_chat", List.of(aliases), null, null, null);
    }

    // ─── rung 1: prefix ───

    @Test void explicitPrefixWinsAndSplitsOnTheFirstColon() {
        Resolution r = hermetic().resolve("openai:ft:gpt-4.1:org:suffix");
        assertEquals("openai", r.provider());
        assertEquals("ft:gpt-4.1:org:suffix", r.model());
        assertEquals(Resolution.Source.PREFIX, r.source());
        assertEquals("openai:ft:gpt-4.1:org:suffix", r.requested());
        assertEquals("OpenAILM", r.adapter());
    }

    @Test void underscoreSpellingIsAnInputAliasOnly() {
        Resolution r = hermetic().resolve("openai_chat:llama-3.1-8b-instant");
        assertEquals("openai-chat", r.provider());
        assertEquals("OpenAIChatLM", r.adapter());
    }

    @Test void boundProviderRoutesWithItsPreset() {
        Resolution r = hermetic().resolve("groq:llama-3.3-70b-versatile");
        assertEquals("groq", r.provider());
        assertEquals("groq", r.compat());
        assertEquals("GROQ_API_KEY", r.envKey());
        assertTrue(r.describe().contains("compat preset 'groq'"));
    }

    @Test void prefixBeatsCatalog() {
        Resolution r = withCatalog(new ModelInfo("gpt-4.1-mini", "azure", "openai_responses")).resolve("openai:gpt-4.1-mini");
        assertEquals("openai", r.provider());
        assertEquals(Resolution.Source.PREFIX, r.source());
    }

    @Test void unroutableHeadFallsThroughToTheRules() {
        // An unroutable head is not a prefix: the whole string (colons and all) is a bare id for the later rungs.
        Resolution r = hermetic().resolve("claude-x:variant");
        assertEquals(Resolution.Source.RULE, r.source());
        assertEquals("claude-x:variant", r.model());
        assertEquals("anthropic", r.provider());
    }

    // ─── rung 2: catalog ───

    @Test void catalogMatchesByIdAndResolvesAliasesToTheCanonicalId() {
        LMRouter router = withCatalog(info("llama-3.3-70b-versatile", "groq", "llama3.3"));
        Resolution byId = router.resolve("llama-3.3-70b-versatile");
        assertEquals(Resolution.Source.CATALOG, byId.source());
        assertEquals("groq", byId.provider());
        Resolution byAlias = router.resolve("llama3.3");
        assertEquals("llama-3.3-70b-versatile", byAlias.model());
        assertEquals("llama3.3", byAlias.requested());
        assertSame(byAlias.modelInfo().id(), byId.modelInfo().id());
    }

    @Test void catalogBeatsRulesAndExactIdBeatsAlias() {
        assertEquals("azure", withCatalog(new ModelInfo("gpt-4.1-mini", "azure", "openai_responses")).resolve("gpt-4.1-mini").provider());
        Resolution r = withCatalog(info("other-model", "groq", "shared-name"), info("shared-name", "groq")).resolve("shared-name");
        assertEquals("shared-name", r.model());
    }

    @Test void ambiguousAcrossProvidersCarriesTheCandidatesInCatalogOrder() {
        LMRouter router = withCatalog(info("shared-model", "groq"), info("shared-model", "deepseek"), info("shared-model", "groq"));
        AmbiguousModelError e = assertThrows(AmbiguousModelError.class, () -> router.resolve("shared-model"));
        assertEquals("shared-model", e.model());
        assertEquals(List.of("groq", "deepseek"), e.providers());
        assertEquals(ErrorCode.AMBIGUOUS_MODEL, e.code());
        assertEquals("ambiguous_model", e.code().wire());
    }

    @Test void ambiguousUnderOneProviderIsNeverPickedByInsertionOrder() {
        LMRouter router = withCatalog(info("groq-fast-1", "groq", "fast"), info("groq-fast-2", "groq", "fast"));
        AmbiguousModelError e = assertThrows(AmbiguousModelError.class, () -> router.resolve("fast"));
        assertEquals(List.of("groq"), e.providers());
    }

    @Test void catalogNamingAnUnroutableProviderIsUnknown() {
        UnknownModelError e = assertThrows(UnknownModelError.class,
            () -> withCatalog(info("mystery-model", "no-such-provider")).resolve("mystery-model"));
        assertEquals("mystery-model", e.model());
    }

    // ─── rung 3: rules ───

    @Test void defaultRulesRouteTheFamilies() {
        LMRouter router = hermetic();
        assertEquals("openai", router.resolve("gpt-4.1-mini").provider());
        assertEquals("anthropic", router.resolve("claude-haiku-4-5").provider());
        assertEquals("gemini", router.resolve("gemini-2.5-flash").provider());
        assertEquals("xai", router.resolve("grok-4").provider());
        assertEquals("openai", router.resolve("o3-mini").provider());
        assertEquals("gemini", router.resolve("veo-3").provider());
        Resolution r = router.resolve("gpt-4.1-mini");
        assertEquals(Resolution.Source.RULE, r.source());
        assertEquals("gpt-", r.rule().prefix());
        assertTrue(r.describe().contains("via built-in rule prefix='gpt-'"));
        assertEquals(12, LMRouter.DEFAULT_RULES.size());
    }

    @Test void customRulesReplaceTheDefaultsFirstMatchWins() {
        LMRouter router = new LMRouter(RouterConfig.builder().env(Map.of())
            .rules(List.of(new RouteRule("x-", "groq"), new RouteRule("x-y", "deepseek"))).build());
        assertEquals("groq", router.resolve("x-y-1").provider());
        assertThrows(UnknownModelError.class, () -> router.resolve("gpt-4.1-mini"));
    }

    // ─── unknown ───

    @Test void nothingMatchesIsUnknownModelWithTheStringAsRequested() {
        LMRouter router = hermetic();
        for (String model : List.of("totally-unknown-model", "", "nope:some-model", "anthropic:")) {
            UnknownModelError e = assertThrows(UnknownModelError.class, () -> router.resolve(model));
            assertEquals(model, e.model());
            assertEquals(ErrorCode.UNKNOWN_MODEL, e.code());
        }
        assertEquals("", assertThrows(UnknownModelError.class, () -> router.resolve(null)).model());
    }

    @Test void nearMissPrefixIsHinted() {
        UnknownModelError e = assertThrows(UnknownModelError.class, () -> hermetic().resolve("anthropc:zzz"));
        assertTrue(e.message().contains("Did you mean \"anthropic:zzz\"?"), e.message());
    }

    // ─── resolve is pure ───

    @Test void resolveRecordsWhichEnvKeyNeverTheValue() {
        LMRouter first = new LMRouter(RouterConfig.builder().env(Map.of("GOOGLE_API_KEY", "g")).build());
        Resolution r = first.resolve("gemini-2.5-flash");
        assertEquals("GOOGLE_API_KEY", r.envKey());
        assertTrue(!r.describe().contains("g\"") && !r.toString().contains("'g'"));
        assertEquals("GEMINI_API_KEY", hermetic().resolve("gemini-2.5-flash").envKey());
        LMRouter explicit = new LMRouter(RouterConfig.builder().env(Map.of()).apiKey("gemini", "k").build());
        assertNull(explicit.resolve("gemini-2.5-flash").envKey());
        assertNull(hermetic().resolve("claude-code:claude-sonnet-4-5").envKey());
        assertNull(hermetic().resolve("ollama:llama3").envKey());
    }

    // ─── config checks ───

    @Test void unknownProviderKeysAreRefusedWithTheNearMiss() {
        NotConfiguredError e = assertThrows(NotConfiguredError.class,
            () -> new LMRouter(RouterConfig.builder().env(Map.of()).apiKey("anthropc", "k").build()));
        assertTrue(e.message().contains("Did you mean 'anthropic'?"), e.message());
        assertThrows(NotConfiguredError.class, () -> new LMRouter(RouterConfig.builder().baseUrl("nope", "http://x").build()));
        assertThrows(NotConfiguredError.class, () -> new LMRouter(RouterConfig.builder().settings("nope", Map.of()).build()));
        assertThrows(NotConfiguredError.class,
            () -> new LMRouter(RouterConfig.builder().apiKey("openai_chat", "a").apiKey("openai-chat", "b").build()));
        new LMRouter(RouterConfig.builder().apiKey("openai_chat", "a").baseUrl("bedrock_anthropic", "http://x").build());
    }

    @Test void configNeverRendersSecrets() {
        RouterConfig config = RouterConfig.builder().env(Map.of("OPENAI_API_KEY", "sk-secret")).apiKey("openai", "sk-explicit").build();
        String rendered = new LMRouter(config).toString();
        assertTrue(!rendered.contains("sk-secret") && !rendered.contains("sk-explicit"), rendered);
        assertTrue(rendered.contains("openai"));
    }

    // ─── the OpenAI-shaped door ───

    @Test void modelStringsWrittenForTheOtherLibraries() {
        assertEquals("gpt-4o-mini", LMRouter.openaiChatModelString("gpt-4o-mini"));
        assertEquals("openai-chat:gpt-4o-mini", LMRouter.openaiChatModelString("openai/gpt-4o-mini"));
        assertEquals("anthropic:claude-sonnet-4-5", LMRouter.openaiChatModelString("anthropic/claude-sonnet-4-5"));
        assertEquals("groq:openai/gpt-oss-20b", LMRouter.openaiChatModelString("groq/openai/gpt-oss-20b"));
        assertEquals("ollama:llama3", LMRouter.openaiChatModelString("ollama_chat/llama3"));
        assertEquals("anthropic:claude-sonnet-4-5", LMRouter.openaiChatModelString("anthropic:claude-sonnet-4-5"));
        assertEquals("openai/", LMRouter.openaiChatModelString("openai/"));
    }

    @Test void unlistedOrTwoDoorLitellmPrefixesAreRefusedByName() {
        UnknownModelError e = assertThrows(UnknownModelError.class, () -> LMRouter.openaiChatModelString("meta-llama/Llama-3-70b"));
        assertTrue(e.message().contains("meta-llama"));
        assertEquals("meta-llama/Llama-3-70b", e.model());
        assertThrows(UnknownModelError.class, () -> LMRouter.openaiChatModelString("bedrock/anthropic.claude-3"));
        assertTrue(!LMRouter.LITELLM_PROVIDER_PREFIXES.containsKey("bedrock") && !LMRouter.LITELLM_PROVIDER_PREFIXES.containsKey("vertex_ai"));
    }

    @Test void bareOpenAINameTakesTheChatDoorWhileTheRouterKeepsResponses() {
        LMRouter router = hermetic();
        Resolution door = router.resolveOpenAIChat("gpt-4o-mini");
        assertEquals("openai-chat", door.provider());
        assertEquals("gpt-4o-mini", door.model());
        assertEquals("openai", router.resolve("gpt-4o-mini").provider());
        assertEquals("groq", router.resolveOpenAIChat("groq/llama-3.3-70b-versatile").provider());
        assertEquals("anthropic", router.resolveOpenAIChat("claude-haiku-4-5").provider());
    }

    @Test void clientKeywordsAreRefusedWithTheRouterConfigPlaceNamed() {
        JsonArray messages = Json.arr(Json.obj("role", "user", "content", "Hi"));
        for (String key : List.of("api_key", "api_base", "timeout", "num_retries", "headers", "cache", "drop_params")) {
            NotConfiguredError e = assertThrows(NotConfiguredError.class,
                () -> LMRouter.splitOpenAIChatCall("gpt-4o-mini", messages, Json.obj(key, "x")));
            assertTrue(e.message().contains("'" + key + "' configures the client"), e.message());
            assertTrue(e.message().contains(LMRouter.CLIENT_KEYWORDS.get(key)), e.message());
            assertEquals(ErrorCode.NOT_CONFIGURED, e.code());
        }
        JsonObject body = LMRouter.splitOpenAIChatCall("gpt-4o-mini", messages, Json.obj("max_completion_tokens", 5));
        assertEquals(List.of("model", "messages", "max_completion_tokens"), List.copyOf(body.keys()));
        assertEquals("gpt-4o-mini", body.get("model").asString());
        NotConfiguredError viaRouter = assertThrows(NotConfiguredError.class,
            () -> hermetic().requestFromOpenAIChat("gpt-4o-mini", messages, Json.obj("api_key", "sk")));
        assertTrue(viaRouter.message().contains("api_key"));
    }
}
