package dev.lm15.dialects.openai;

import dev.lm15.auth.Access;
import dev.lm15.compat.OpenAIResponsesCompat;
import dev.lm15.errors.BillingError;
import dev.lm15.errors.LM15Error;
import dev.lm15.errors.ProviderError;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.errors.UnsupportedModelError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.sse.SseEvent;
import dev.lm15.transport.HttpResponse;
import dev.lm15.types.*;
import dev.lm15.wire.BuildContext;
import dev.lm15.wire.WireRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenAIResponsesDialectTest {
    private final OpenAIResponsesDialect dialect = new OpenAIResponsesDialect();

    private static BuildContext openai(String model) {
        return new BuildContext("openai", Access.OPENAI_API, Map.of(), OpenAIResponsesCompat.preset("openai"), "https://api.openai.com/v1", model, null);
    }

    private static BuildContext codex(String model) {
        return new BuildContext("openai-codex", Access.OPENAI_CODEX, Map.of(), OpenAIResponsesCompat.preset("openai"), Access.DEFAULT_CODEX_BASE_URL, model, "acct-1");
    }

    private static Request request(Config config) {
        return new Request("gpt-5-mini", List.of(Message.user("hi")), null, List.of(), config);
    }

    // ─── compat ───

    @Test void presetsResolveAliasesAndKnobs() {
        assertSame(OpenAIResponsesCompat.preset("openai"), OpenAIResponsesCompat.preset("responses"));
        assertEquals("ollama", OpenAIResponsesCompat.preset("lm-studio").resolve().developerRole().equals("system") ? "ollama" : "?");
        OpenAIResponsesCompat.Resolved meta = OpenAIResponsesCompat.preset("meta").resolve();
        assertEquals("tag", meta.commentaryPhase());
        assertEquals("openai_implicit", meta.cacheControl());
        assertEquals("verbatim", meta.builtinTools());
        assertEquals("images", OpenAIResponsesCompat.preset("moonshotai").resolve().toolResultMedia());
        assertEquals(OpenAIResponsesCompat.Resolved.DEFAULT, OpenAIResponsesCompat.preset("openai").resolve());
        assertThrows(ValidationException.class, () -> OpenAIResponsesCompat.preset("nope"));
    }

    @Test void modelOverridesAndExtensionHatchLayer() {
        OpenAIResponsesCompat base = OpenAIResponsesCompat.preset("openai").toBuilder().modelOverride("o1", Map.of("max_output_tokens_field", "max_tokens")).build();
        assertEquals("max_tokens", base.forModel("o1-mini").resolve().maxOutputTokensField());
        assertEquals("max_output_tokens", base.forModel("gpt-5").resolve().maxOutputTokensField());
        OpenAIResponsesCompat over = OpenAIResponsesCompat.fromExtensions(Json.obj("openai_responses_compat", Json.obj("developer_role", "system")));
        assertEquals("system", base.merge(over).resolve().developerRole());
        assertThrows(ValidationException.class, () -> base.toBuilder().modelOverride("x", Map.of("routing", "y")).build());
    }

    // ─── build ───

    @Test void buildsTheResponsesPayload() {
        Request r = new Request("gpt-5-mini", List.of(Message.user("hi")), SystemPrompt.of("be brief"), List.of(new FunctionTool("f", "d")),
            Config.builder().maxTokens(10).reasoning(Reasoning.OFF).toolChoice(ToolChoice.REQUIRED).build());
        WireRequest w = dialect.build(r, false, openai("gpt-5-mini"));
        JsonObject body = w.body.asObject();
        assertEquals("/responses", w.path);
        assertEquals("responses", w.endpoint);
        assertEquals("be brief", body.optString("instructions"));
        assertEquals(10, body.get("max_output_tokens").asInt());
        assertEquals(Json.obj("effort", "none"), body.get("reasoning"));
        assertEquals("required", body.optString("tool_choice"));
        assertEquals("function", body.get("tools").asArray().get(0).asObject().optString("type"));
        assertFalse(body.get("stream").asBool());
    }

    @Test void codexBackendDefaults() {
        WireRequest w = dialect.build(request(Config.builder().maxTokens(50).build()), false, codex("gpt-5-codex"));
        JsonObject body = w.body.asObject();
        assertTrue(body.get("stream").asBool());
        assertFalse(body.get("store").asBool());
        assertEquals(Access.DEFAULT_CODEX_INSTRUCTIONS, body.optString("instructions"));
        assertFalse(body.has("max_output_tokens"));
        assertTrue(w.headers.stream().anyMatch(h -> h.getKey().equals("chatgpt-account-id") && h.getValue().equals("acct-1")));
    }

    @Test void refusesKnobsWithNoWireSlot() {
        assertThrows(UnsupportedFeatureError.class, () -> dialect.build(request(Config.builder().stop("x").build()), false, openai("gpt-5-mini")));
        assertThrows(UnsupportedFeatureError.class, () -> dialect.build(request(Config.builder().topK(3).build()), false, openai("gpt-5-mini")));
        assertThrows(UnsupportedFeatureError.class, () -> dialect.build(
            request(Config.builder().reasoning(new Reasoning(ReasoningEffort.HIGH, 1024, null)).build()), false, openai("gpt-5-mini")));
    }

    @Test void replaysReasoningItemsNatively() {
        ThinkingPart thinking = new ThinkingPart("", List.of(new ContinuationState("openai", "reasoning_item", Json.obj("id", "rs_1", "encrypted_content", "abc"))));
        Request r = new Request("gpt-5-mini", List.of(Message.user("q"), Message.assistant(List.of(thinking, new TextPart("a"))), Message.user("more")));
        JsonObject body = dialect.build(r, false, openai("gpt-5-mini")).body.asObject();
        JsonValue item = body.get("input").asArray().get(1);
        assertEquals(Json.obj("type", "reasoning", "id", "rs_1", "encrypted_content", "abc", "summary", Json.arr()), item);
    }

    // ─── parse ───

    @Test void parsesAReasoningItemWithoutSummaryAsEmptyThinking() {
        String body = "{\"id\":\"resp_1\",\"model\":\"gpt-5-mini\",\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],\"encrypted_content\":\"zzz\"},"
            + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"42\"}]}],\"usage\":{\"input_tokens\":3,\"output_tokens\":4,\"output_tokens_details\":{\"reasoning_tokens\":2}}}";
        Response resp = dialect.parseResponse(request(Config.DEFAULT), openai("gpt-5-mini"), HttpResponse.json(200, body.getBytes(StandardCharsets.UTF_8)));
        assertEquals("resp_1", resp.id());
        ThinkingPart th = (ThinkingPart) resp.message().parts().get(0);
        assertEquals("", th.text());
        assertEquals("zzz", th.continuation().get(0).data().optString("encrypted_content"));
        assertEquals("42", ((TextPart) resp.message().parts().get(1)).text());
        assertEquals(FinishReason.STOP, resp.finishReason());
        assertEquals(2, resp.usage().reasoningTokens());
        assertEquals(7, resp.usage().totalTokens());
        assertNull(resp.providerData().get("_lm15_unmapped"));
    }

    @Test void refusesAnUnnamedFunctionCall() {
        String body = "{\"output\":[{\"type\":\"function_call\",\"call_id\":\"c1\",\"arguments\":\"{}\"}]}";
        assertThrows(ProviderError.class, () -> dialect.parseResponse(request(Config.DEFAULT), openai("gpt-5-mini"), HttpResponse.json(200, body.getBytes(StandardCharsets.UTF_8))));
    }

    @Test void recordsUnmappedContent() {
        String body = "{\"output\":[{\"type\":\"mystery\"}],\"output_text\":\"\"}";
        Response resp = dialect.parseResponse(request(Config.DEFAULT), openai("gpt-5-mini"), HttpResponse.json(200, body.getBytes(StandardCharsets.UTF_8)));
        assertEquals(Json.arr(Json.obj("path", "output[0]", "type", "mystery")), resp.providerData().get("_lm15_unmapped"));
        assertEquals("", ((TextPart) resp.message().parts().get(0)).text());
    }

    // ─── stream ───

    @Test void streamFramesMapToPreCoalesceEvents() {
        BuildContext cx = openai("gpt-5-mini");
        Request r = request(Config.DEFAULT);
        List<StreamEvent> start = dialect.parseStreamEvent(r, cx, new SseEvent(null, "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_9\",\"model\":\"gpt-5-mini-2025\"}}"));
        assertEquals(new StreamStartEvent("resp_9", "gpt-5-mini-2025"), start.get(0));
        List<StreamEvent> added = dialect.parseStreamEvent(r, cx, new SseEvent(null, "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs\"}}"));
        assertEquals(new StreamDeltaEvent(new ThinkingDelta("", 0)), added.get(0));
        List<StreamEvent> done = dialect.parseStreamEvent(r, cx, new SseEvent(null, "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs\",\"encrypted_content\":\"e\"}}"));
        assertEquals(new StreamDeltaEvent(new ContinuationDelta("openai", "reasoning_item", Json.obj("id", "rs", "encrypted_content", "e"), 0)), done.get(0));
        List<StreamEvent> end = dialect.parseStreamEvent(r, cx, new SseEvent(null, "[DONE]"));
        assertEquals(new StreamEndEvent(null, null, null), end.get(0));
        List<StreamEvent> completed = dialect.parseStreamEvent(r, cx, new SseEvent(null, "{\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"function_call\",\"name\":\"f\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}}"));
        StreamEndEvent e = (StreamEndEvent) completed.get(0);
        assertEquals(FinishReason.TOOL_CALL, e.finishReason());
        assertEquals(3, e.usage().totalTokens());
        assertNotNull(e.providerData());
    }

    // ─── errors ───

    @Test void classifiesProviderErrors() {
        LM15Error billing = dialect.normalizeError(openai("m"), 429, "{\"error\":{\"message\":\"quota\",\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}");
        assertInstanceOf(BillingError.class, billing);
        assertEquals("insufficient_quota", billing.providerCode());
        LM15Error model = dialect.normalizeError(openai("m"), 404, "{\"error\":{\"message\":\"The model `x` does not exist\",\"code\":\"model_not_found\"}}");
        assertInstanceOf(UnsupportedModelError.class, model);
        LM15Error detail = dialect.normalizeError(codex("m"), 400, "{\"detail\":\"Unsupported model\"}");
        assertInstanceOf(UnsupportedModelError.class, detail);
        assertTrue(detail.message().contains(OpenAIErrors.MODEL_LIST_HINT));
        LM15Error plain = dialect.normalizeError(openai("m"), 503, "<html>down</html>");
        assertEquals(ErrorCode.SERVER, plain.code());
        assertNull(plain.providerCode());
    }

    // ─── live ───

    @Test void liveCodecFramesAndEvents() {
        LiveConfig config = LiveConfig.builder("gpt-realtime-mini").system("terse").build();
        OpenAILiveCodec codec = (OpenAILiveCodec) dialect.liveCodec(openai("gpt-realtime-mini"), config);
        assertEquals("wss://api.openai.com/v1/realtime?model=gpt-realtime-mini", codec.url());
        JsonObject setup = codec.setupFrames().get(0).asObject();
        assertEquals("session.update", setup.optString("type"));
        assertEquals(Json.arr("text"), setup.get("session").asObject().get("output_modalities"));
        assertEquals(2, codec.encode(new LiveClientEvent.EndAudio()).size());
        byte[] toolTurn = "{\"type\":\"response.done\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\"}],\"usage\":{\"input_tokens\":5,\"output_tokens\":6}}}".getBytes(StandardCharsets.UTF_8);
        List<LiveServerEvent> events = codec.decode(toolTurn);
        assertEquals(1, events.size());
        assertInstanceOf(LiveServerEvent.UsageEvent.class, events.get(0));
        assertTrue(codec.decode("{\"type\":\"error\",\"error\":{\"code\":\"response_cancel_not_active\"}}".getBytes(StandardCharsets.UTF_8)).isEmpty());
        List<LiveServerEvent> end = codec.decode("{\"type\":\"response.done\",\"response\":{\"status\":\"completed\",\"output\":[]}}".getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(LiveServerEvent.TurnEnd.class, end.get(0));
    }
}
