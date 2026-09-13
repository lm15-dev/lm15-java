package dev.lm15.dialects.openaichat;

import dev.lm15.compat.OpenAIChatCompat;
import dev.lm15.dialects.Dialects;
import dev.lm15.errors.AuthError;
import dev.lm15.errors.BillingError;
import dev.lm15.errors.InvalidRequestError;
import dev.lm15.errors.LM15Error;
import dev.lm15.errors.ProviderError;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonObject;
import dev.lm15.registry.Registry;
import dev.lm15.sse.SseEvent;
import dev.lm15.transport.HttpResponse;
import dev.lm15.types.*;
import dev.lm15.wire.BuildContext;
import dev.lm15.wire.WireRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAIChatDialectTest {
    private static final OpenAIChatDialect DIALECT = new OpenAIChatDialect();

    private static BuildContext cx(String provider, String preset, String model) {
        return new BuildContext(provider, Registry.require(provider).access(), null, OpenAIChatCompat.preset(preset), "https://example/v1", model, null);
    }

    private static Request request(String model, Config config, List<Tool> tools) {
        return new Request(model, List.of(Message.user("hi")), null, tools, config);
    }

    private static final FunctionTool LOOKUP = new FunctionTool("lookup", "look", Json.obj("type", "object", "properties", JsonObject.EMPTY));

    @Test
    void registryFindsBothDialectsReflectively() {
        assertInstanceOf(OpenAIChatDialect.class, Dialects.lookup("openai-chat"));
        assertInstanceOf(XaiDialect.class, Dialects.lookup("xai"));
    }

    @Test
    void presetsResolveWithAliasesAndOverrides() {
        assertEquals(15, OpenAIChatCompat.presetNames().size());
        assertSame(OpenAIChatCompat.preset("bedrock_mantle"), OpenAIChatCompat.preset("bedrock-mantle"));
        assertEquals("lmstudio", OpenAIChatCompat.preset("lm-studio").preset());
        assertEquals("openai", OpenAIChatCompat.preset("openai_chat").preset());
        assertEquals("zai", OpenAIChatCompat.preset("z.ai").preset());
        assertThrows(ValidationException.class, () -> OpenAIChatCompat.preset("nope"));
        OpenAIChatCompat bedrock = OpenAIChatCompat.preset("bedrock");
        assertEquals("send", bedrock.forcedToolChoice());
        assertEquals("reject", bedrock.forModel("openai.gpt-oss-20b").forcedToolChoice());
        assertEquals("reject", bedrock.forModel("openai.gpt-oss-20b").jsonSchema());
        assertEquals("send", bedrock.forModel("google.gemma-3").jsonSchema());
        assertEquals("reject", bedrock.forModel("google.gemma-3").forcedToolChoice());
        assertEquals(List.of("low", "high", "max"), OpenAIChatCompat.preset("moonshotai").reasoningEfforts());
    }

    @Test
    void buildWritesThePresetSpellings() {
        Config config = Config.builder().maxTokens(5).reasoning(new Reasoning(ReasoningEffort.LOW)).userId("u").build();
        JsonObject body = DIALECT.build(request("m", config, List.of()), true, cx("deepseek", "deepseek", "m")).body.asObject();
        assertEquals(List.of("model", "messages", "stream", "stream_options", "max_tokens", "thinking", "reasoning_effort", "user_id"), List.copyOf(body.keys()));
        assertEquals(Json.obj("type", "enabled"), body.get("thinking"));

        WireRequest w = DIALECT.build(request("m", Config.builder().reasoning(Reasoning.OFF).build(), List.of()), false, cx("openrouter", "openrouter", "m"));
        assertEquals("/chat/completions", w.path);
        assertEquals("chat/completions", w.endpoint);
        assertEquals(Json.obj("enabled", false), w.body.asObject().get("reasoning"));
    }

    @Test
    void buildRefusesWhatTheWireCannotCarry() {
        assertThrows(UnsupportedFeatureError.class, () -> DIALECT.build(request("m", Config.builder().topK(3).build(), List.of()), false, cx("vllm", "vllm", "m")));
        assertThrows(UnsupportedFeatureError.class, () -> DIALECT.build(request("m", Config.builder().reasoning(new Reasoning(ReasoningEffort.LOW)).build(), List.of()), false, cx("ollama", "ollama", "m")));
        assertThrows(UnsupportedFeatureError.class, () -> DIALECT.build(request("kimi-k3", Config.builder().reasoning(new Reasoning(ReasoningEffort.MEDIUM)).build(), List.of()), false, cx("moonshotai", "moonshotai", "kimi-k3")));
        assertThrows(UnsupportedFeatureError.class, () -> DIALECT.build(request("glm", Config.builder().toolChoice(ToolChoice.REQUIRED).build(), List.of(LOOKUP)), false, cx("zai", "zai", "glm")));
        assertThrows(UnsupportedFeatureError.class, () -> DIALECT.build(request("m", Config.DEFAULT, List.of(new BuiltinTool("web_search"))), false, cx("openai-chat", "openai", "m")));
        JsonObject groq = DIALECT.build(request("m", Config.DEFAULT, List.of(new BuiltinTool("web_search"))), false, cx("groq", "groq", "m")).body.asObject();
        assertEquals(Json.arr(Json.obj("type", "browser_search")), groq.get("tools"));
    }

    @Test
    void xaiRefusesItsMeasuredCells() {
        XaiDialect xai = new XaiDialect();
        BuildContext cx = cx("xai", "xai", "grok-4");
        assertThrows(UnsupportedFeatureError.class, () -> xai.build(request("grok-4", Config.builder().reasoning(Reasoning.OFF).build(), List.of()), false, cx));
        assertThrows(UnsupportedFeatureError.class, () -> xai.build(request("grok-4", Config.builder().logprobs(0).build(), List.of()), false, cx));
        assertThrows(UnsupportedFeatureError.class, () -> xai.build(request("grok-4", Config.builder().toolChoice(new ToolChoice(ToolChoiceMode.AUTO, List.of("lookup"), null)).build(), List.of(LOOKUP)), false, cx));
        JsonObject forced = xai.build(request("grok-4", Config.builder().toolChoice(new ToolChoice(ToolChoiceMode.REQUIRED, List.of("lookup"), null)).build(), List.of(LOOKUP)), false, cx).body.asObject();
        assertEquals(Json.obj("type", "function", "function", Json.obj("name", "lookup")), forced.get("tool_choice"));
        assertThrows(UnsupportedFeatureError.class, () -> xai.videoListRequest(cx, 5, null));
        assertNull(xai.videoResultFetch(cx, JsonObject.EMPTY));
        assertEquals("/videos/odd%20id%3F", xai.videoStatusRequest(cx, "odd id?").path);
    }

    @Test
    void parseResponseMapsPartsUsageAndUnmapped() {
        String body = "{\"id\":\"x\",\"model\":\"mm\",\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"weird\"}],\"reasoning\":\"r\"},"
            + "\"finish_reason\":\"odd\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":1}}}";
        Response r = DIALECT.parseResponse(request("m", Config.DEFAULT, List.of()), cx("openai-chat", "openai", "m"), HttpResponse.json(200, body.getBytes(StandardCharsets.UTF_8)));
        assertEquals("x", r.id());
        assertEquals("mm", r.model());
        assertEquals(List.of(new ThinkingPart("r"), new TextPart("a")), r.message().parts());
        assertEquals(FinishReason.STOP, r.finishReason());
        assertEquals(1, r.usage().cacheReadTokens());
        JsonArray unmapped = r.providerData().get("_lm15_unmapped").asArray();
        assertEquals(2, unmapped.size());
        assertEquals("choices[0].finish_reason", unmapped.get(1).asObject().optString("path"));
    }

    @Test
    void responseDoorRefusesSeveralChoicesUnlessNamed() {
        JsonObject body = Json.parseObject("{\"choices\":[{\"message\":{\"content\":\"a\"}},{\"message\":{\"content\":\"b\"}}]}");
        BuildContext cx = cx("openai-chat", "openai", "m");
        assertThrows(UnsupportedFeatureError.class, () -> DIALECT.responseFromOpenAIChat(cx, body, "m", null));
        assertEquals("b", DIALECT.responseFromOpenAIChat(cx, body, "m", 1).text());
        assertThrows(ValidationException.class, () -> DIALECT.responseFromOpenAIChat(cx, body, null, 0));
        assertThrows(ProviderError.class, () -> DIALECT.responseFromOpenAIChat(cx, Json.parseObject("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{}\"}}]}}]}"), "m", null));
    }

    @Test
    void streamFramesArePreCoalesceEvents() {
        Request req = request("m", Config.DEFAULT, List.of());
        BuildContext cx = cx("openai-chat", "openai", "m");
        List<StreamEvent> deltas = DIALECT.parseStreamEvent(req, cx, new SseEvent(null, "{\"choices\":[{\"delta\":{\"content\":\"hi\",\"tool_calls\":[{\"index\":1,\"id\":\"c\",\"function\":{\"name\":\"f\",\"arguments\":\"{\"}}]}}]}"));
        assertEquals(2, deltas.size());
        assertEquals(new TextDelta("hi", 0), ((StreamDeltaEvent) deltas.get(0)).delta());
        assertEquals(new ToolCallDelta("{", 1, "c", "f"), ((StreamDeltaEvent) deltas.get(1)).delta());
        List<StreamEvent> finish = DIALECT.parseStreamEvent(req, cx, new SseEvent(null, "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}"));
        assertEquals(FinishReason.TOOL_CALL, ((StreamEndEvent) finish.get(0)).finishReason());
        List<StreamEvent> usage = DIALECT.parseStreamEvent(req, cx, new SseEvent(null, "{\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4}}"));
        StreamEndEvent end = (StreamEndEvent) usage.get(0);
        assertNull(end.finishReason());
        assertEquals(7, end.usage().totalTokens());
        assertNotNull(end.providerData());
        assertEquals(new StreamEndEvent(null, null, null), DIALECT.parseStreamEvent(req, cx, new SseEvent(null, "[DONE]")).get(0));
        assertTrue(DIALECT.parseStreamEvent(req, cx, new SseEvent(null, "")).isEmpty());
    }

    @Test
    void errorsRefineByCodeThenStatus() {
        BuildContext cx = cx("openai-chat", "openai", "m");
        LM15Error ctx = DIALECT.normalizeError(cx, 400, "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"long\"}}");
        assertEquals(ErrorCode.CONTEXT_LENGTH, ctx.code());
        assertEquals("context_length_exceeded", ctx.providerCode());
        assertInstanceOf(BillingError.class, DIALECT.normalizeError(cx, 429, "{\"error\":{\"code\":\"1113\",\"message\":\"x\"}}"));
        assertInstanceOf(AuthError.class, DIALECT.normalizeError(cx, 401, "{\"error\":{\"type\":\"authentication_error\",\"message\":\"x\"}}"));
        assertInstanceOf(InvalidRequestError.class, DIALECT.normalizeError(cx, 400, "not json"));
        LM15Error xai = new XaiDialect().normalizeError(cx("xai", "xai", "m"), 401, "{\"code\":\"unauthenticated:no-credentials\",\"error\":\"No credentials presented.\"}");
        assertInstanceOf(AuthError.class, xai);
        assertEquals("unauthenticated:no-credentials", xai.providerCode());
    }

    @Test
    void ingestRoundTripsTheBuilderAndRefusesTheUndecided() {
        BuildContext cx = cx("openai-chat", "openai", "gpt-5.6");
        Config config = Config.builder().maxTokens(5).temperature(0.5).logprobs(2).userId("u")
            .cache(new CacheConfig(CacheMode.AUTO, CacheRetention.LONG, "k1", 0, null, null))
            .toolChoice(new ToolChoice(ToolChoiceMode.REQUIRED, List.of("lookup"), false))
            .responseFormat(Json.obj("type", "json_schema", "schema", Json.obj("type", "object"), "strict", true)).build();
        Request original = new Request("gpt-5.6", List.of(Message.user("q"), Message.assistant(List.of(new ToolCallPart("c1", "lookup", Json.obj("q", "x")))),
            Message.tool(List.of(new ToolResultPart("c1", List.of(new TextPart("r"))))), Message.user("again")), SystemPrompt.of("sys"), List.of(LOOKUP), config);
        JsonObject body = DIALECT.build(original, false, cx).body.asObject();
        assertEquals(original, DIALECT.requestFromOpenAIChat(cx, body));

        assertThrows(UnsupportedFeatureError.class, () -> DIALECT.requestFromOpenAIChat(cx, body.with("n", Json.of(2))));
        assertThrows(UnsupportedFeatureError.class, () -> DIALECT.requestFromOpenAIChat(cx, body.with("thinking", Json.obj("type", "enabled"))));
        assertThrows(UnsupportedFeatureError.class, () -> DIALECT.requestFromOpenAIChat(cx, body.with("something_new", Json.of(1))));
        assertThrows(ValidationException.class, () -> DIALECT.requestFromOpenAIChat(cx, body.with("max_tokens", Json.of(9))));
        Request dropped = DIALECT.requestFromOpenAIChat(cx, body.with("stream", Json.of(true)).with("seed", Json.of(3)));
        assertEquals(Json.obj("seed", 3), dropped.config().extensions());
    }

    @Test
    void ingestReadsOnePresetsSpellings() {
        JsonObject groq = Json.parseObject("{\"model\":\"qwen/qwen3-32b\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}],\"reasoning_format\":\"parsed\",\"tools\":[{\"type\":\"browser_search\"}]}");
        Request r = DIALECT.requestFromOpenAIChat(cx("groq", "groq", "qwen/qwen3-32b"), groq);
        assertEquals(Json.obj("reasoning_format", "parsed"), r.config().extensions());
        assertEquals(new BuiltinTool("web_search"), r.tools().get(0));
        assertThrows(UnsupportedFeatureError.class, () -> DIALECT.requestFromOpenAIChat(cx("openai-chat", "openai", "m"), groq));
        JsonObject image = Json.parseObject("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://a.b/c/x.JPG?y=1\",\"detail\":\"high\"}}]}]}");
        ImagePart part = (ImagePart) DIALECT.requestFromOpenAIChat(cx("openai-chat", "openai", "m"), image).messages().get(0).parts().get(0);
        assertEquals("image/jpeg", part.mediaType());
        assertEquals(ImageDetail.HIGH, part.detail());
    }
}
