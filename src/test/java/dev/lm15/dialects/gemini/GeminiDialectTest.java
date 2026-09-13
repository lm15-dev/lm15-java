package dev.lm15.dialects.gemini;

import dev.lm15.ProviderLM;
import dev.lm15.errors.ContextLengthError;
import dev.lm15.errors.LM15Error;
import dev.lm15.errors.ProviderError;
import dev.lm15.errors.RateLimitError;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.live.LiveCodec;
import dev.lm15.transport.HttpResponse;
import dev.lm15.types.*;
import dev.lm15.wire.TransportRequest;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The Gemini dialect through the same public adapter users call (no network: pure hooks only). */
class GeminiDialectTest {
    private static ProviderLM lm() {
        return ProviderLM.builder("gemini").apiKey("test-key").build();
    }

    private static Request user(String model, String text, Config config) {
        return new Request(model, List.of(Message.user(text)), null, List.of(), config);
    }

    private static JsonObject body(TransportRequest req) { return req.body().asObject(); }

    private static String header(TransportRequest req, String name) {
        for (Map.Entry<String, String> h : req.headers()) if (h.getKey().equalsIgnoreCase(name)) return h.getValue();
        return null;
    }

    @Test
    void buildsGenerateContentWithKeyHeaderAndSsePath() {
        TransportRequest req = lm().buildRequest(user("gemini-2.5-flash", "Hello.", Config.builder().temperature(1.0).maxTokens(16).build()), true);
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent", req.url());
        assertEquals(List.of(Map.entry("alt", "sse")), req.params());
        assertEquals("test-key", header(req, "x-goog-api-key"));
        JsonObject generation = body(req).get("generationConfig").asObject();
        // Integral float knobs travel in proto3-JSON integer form.
        assertEquals("1", generation.get("temperature").toJson());
        assertEquals("16", generation.get("maxOutputTokens").toJson());
        assertEquals(Json.parse("[{\"role\":\"user\",\"parts\":[{\"text\":\"Hello.\"}]}]"), body(req).get("contents"));
    }

    @Test
    void reasoningClassesAndRefusals() {
        Config budget = Config.builder().reasoning(new Reasoning(ReasoningEffort.MEDIUM)).build();
        JsonObject thinking25 = body(lm().buildRequest(user("gemini-2.5-flash", "q", budget), false)).get("generationConfig").asObject().get("thinkingConfig").asObject();
        assertEquals(Json.obj("thinkingBudget", 8192), thinking25);
        JsonObject thinking3 = body(lm().buildRequest(user("gemini-3-flash", "q", budget), false)).get("generationConfig").asObject().get("thinkingConfig").asObject();
        assertEquals(Json.obj("thinkingLevel", "medium"), thinking3);
        JsonObject off = body(lm().buildRequest(user("gemini-2.5-flash", "q", Config.builder().reasoning(Reasoning.OFF).build()), false)).get("generationConfig").asObject();
        assertEquals(Json.obj("thinkingBudget", 0), off.get("thinkingConfig"));
        assertThrows(UnsupportedFeatureError.class, () -> lm().buildRequest(user("gemini-3-flash", "q", Config.builder().reasoning(Reasoning.OFF).build()), false));
        assertThrows(UnsupportedFeatureError.class, () -> lm().buildRequest(user("gemini-3-flash", "q", Config.builder().reasoning(new Reasoning(ReasoningEffort.XHIGH)).build()), false));
        assertThrows(UnsupportedFeatureError.class, () -> lm().buildRequest(user("gemini-2.5-flash", "q", Config.builder().userId("u").build()), false));
    }

    @Test
    void toolChoiceParallelFalseRaisesAndAllowedAutoIsValidated() {
        FunctionTool tool = new FunctionTool("get_weather", "w");
        Request parallel = new Request("gemini-2.5-flash", List.of(Message.user("q")), null, List.of(tool),
            Config.builder().toolChoice(new ToolChoice(ToolChoiceMode.AUTO, List.of(), false)).build());
        assertThrows(UnsupportedFeatureError.class, () -> lm().buildRequest(parallel, false));
        Request allowed = new Request("gemini-2.5-flash", List.of(Message.user("q")), null, List.of(tool),
            Config.builder().toolChoice(new ToolChoice(ToolChoiceMode.AUTO, List.of("get_weather"), null)).build());
        JsonObject cfg = body(lm().buildRequest(allowed, false)).get("toolConfig").asObject().get("functionCallingConfig").asObject();
        assertEquals("VALIDATED", cfg.get("mode").asString());
        assertEquals(Json.arr("get_weather"), cfg.get("allowedFunctionNames"));
    }

    @Test
    void storedCacheSendsOnlyTheSuffix() {
        Request request = new Request("gemini-2.5-flash", List.of(Message.user("prefix"), Message.user("question")), SystemPrompt.of("sys"),
            List.of(new FunctionTool("t", "d")), Config.builder().cache(CacheConfig.builder().resource("cachedContents/abc").prefixUntilIndex(0).build()).build());
        JsonObject wire = body(lm().buildRequest(request, false));
        assertEquals("cachedContents/abc", wire.get("cachedContent").asString());
        assertEquals(1, wire.get("contents").asArray().size());
        assertFalse(wire.has("systemInstruction"));
        assertFalse(wire.has("tools"));
    }

    @Test
    void functionResponseCarriesMediaNativelyAndResolvesTheName() {
        FunctionTool tool = new FunctionTool("fetch_panel", "p");
        ImagePart image = Parts.image(Parts.Address.ofData("AAAA"), "image/png");
        Request request = new Request("gemini-3.7-flash", List.of(
            Message.user("q"),
            Message.assistant(new ToolCallPart("call_1", "fetch_panel", Json.obj("label", "A"))),
            Message.tool("call_1", List.of(new TextPart("Panel."), image))), null, List.of(tool), Config.DEFAULT);
        JsonArray contents = body(lm().buildRequest(request, false)).get("contents").asArray();
        JsonObject fr = contents.get(2).asObject().get("parts").asArray().get(0).asObject().get("functionResponse").asObject();
        assertEquals("fetch_panel", fr.get("name").asString());
        assertEquals(Json.obj("result", "Panel."), fr.get("response"));
        assertEquals(Json.arr(Json.obj("inlineData", Json.obj("mimeType", "image/png", "data", "AAAA"))), fr.get("parts"));
        // A video part has no functionResponse slot; a result with no resolvable name is refused (MAP-10 rule 6).
        Request video = new Request("gemini-2.5-flash", List.of(Message.user("q"),
            Message.tool("c1", List.of(Parts.video(Parts.Address.ofData("AAAA"), "video/mp4")))), null, List.of(tool), Config.DEFAULT);
        assertThrows(UnsupportedFeatureError.class, () -> lm().buildRequest(video, false));
    }

    @Test
    void parsesResponseWithZeroCountRuleSignaturesAndUnmapped() {
        String json = "{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"\",\"thoughtSignature\":\"sig\"},"
            + "{\"functionCall\":{\"name\":\"get_weather\",\"args\":{\"city\":\"Gatineau\"}}},{\"foo\":1,\"bar\":2},{\"executableCode\":{}}]},"
            + "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":5,\"thoughtsTokenCount\":3,\"totalTokenCount\":8},\"responseId\":\"r1\"}";
        Response r = lm().parseResponse(user("gemini-2.5-flash", "q", Config.DEFAULT), HttpResponse.json(200, json.getBytes(StandardCharsets.UTF_8)));
        assertEquals("r1", r.id());
        assertEquals(FinishReason.TOOL_CALL, r.finishReason());
        assertEquals(new Usage(5, 0, 8, null, null, 3, null, null), r.usage());
        ThinkingPart thinking = (ThinkingPart) r.message().parts().get(0);
        assertEquals("", thinking.text());
        assertEquals(Json.obj("value", "sig"), ContinuationState.find(thinking.continuation(), "gemini", "thought_signature"));
        ToolCallPart call = (ToolCallPart) r.message().parts().get(1);
        assertEquals("tool_call_1", call.id());
        JsonValue unmapped = r.providerData().get("_lm15_unmapped");
        assertEquals(Json.arr(Json.obj("path", "candidates[0].content.parts[2]", "type", "bar+foo")), unmapped);
    }

    @Test
    void emptyCandidateIsOneEmptyTextPartAndUnnamedCallIsRefused() {
        String empty = "{\"candidates\":[{\"content\":{\"parts\":[]},\"finishReason\":\"MAX_TOKENS\"}]}";
        Response r = lm().parseResponse(user("gemini-2.5-flash", "q", Config.DEFAULT), HttpResponse.json(200, empty.getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of(new TextPart("")), r.message().parts());
        assertEquals(FinishReason.LENGTH, r.finishReason());
        assertEquals(Usage.EMPTY, r.usage());
        String unnamed = "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"args\":{}}}]}}]}";
        assertThrows(ProviderError.class, () -> lm().parseResponse(user("gemini-2.5-flash", "q", Config.DEFAULT), HttpResponse.json(200, unnamed.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void replaysStreamWithSynthesizedStartAndMintedCallId() {
        String sse = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hel\"}]}}]}\r\n\r\n"
            + "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"f\",\"args\":{\"q\":\"\u00e9\"}}}]},\"finishReason\":\"STOP\"}],"
            + "\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":2}}\r\n\r\n";
        Request request = user("gemini-2.5-flash", "q", Config.DEFAULT);
        List<StreamEvent> events = lm().replayStream(request, sse.getBytes(StandardCharsets.UTF_8));
        assertEquals(new StreamStartEvent(null, "gemini-2.5-flash"), events.get(0));
        ToolCallDelta delta = (ToolCallDelta) ((StreamDeltaEvent) events.get(2)).delta();
        assertEquals("{\"q\":\"\\u00e9\"}", delta.input()); // Python json.dumps: compact, ASCII-escaped
        StreamEndEvent end = (StreamEndEvent) events.get(events.size() - 1);
        assertEquals(FinishReason.TOOL_CALL, end.finishReason());
        assertNotNull(end.providerData());
        Response r = dev.lm15.stream.Streams.materialize(events, request);
        assertEquals("tool_call_0", r.toolCalls().get(0).id());
        assertEquals(new Usage(1, 2), r.usage());
    }

    @Test
    void normalizesErrorsByStatusTableAndMessage() {
        LM15Error ctx = lm().normalizeError(400, "{\"error\":{\"message\":\"input token count exceeds the limit\",\"status\":\"INVALID_ARGUMENT\"}}");
        assertInstanceOf(ContextLengthError.class, ctx);
        assertEquals("INVALID_ARGUMENT", ctx.providerCode());
        LM15Error rate = lm().normalizeError(429, "{\"error\":{\"message\":\"slow\",\"status\":\"RESOURCE_EXHAUSTED\"}}");
        assertInstanceOf(RateLimitError.class, rate);
        LM15Error text = lm().normalizeError(503, "<html>down</html>");
        assertEquals(ErrorCode.SERVER, text.code());
        assertNull(text.providerCode());
    }

    @Test
    void resourceIdsKeepSlashesAndEncodeTheRest() {
        assertEquals("https://generativelanguage.googleapis.com/v1beta/files/odd%20id%3Fx%3A1%23%2525:download",
            lm().fileDownloadRequest("files/odd id?x:1#%25").url());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/batches/odd%20id:cancel", lm().batchCancelRequest("batches/odd id").url());
        assertEquals("https://generativelanguage.googleapis.com/upload/v1beta/files",
            lm().fileUploadRequest(new FileUploadRequest("a.txt", "hi".getBytes(StandardCharsets.UTF_8), "text/plain")).url());
        assertEquals("https://example.test/upload/v1beta", GeminiDialect.uploadBaseUrl("https://example.test/v1beta/"));
    }

    @Test
    void liveCodecSetupEncodeDecode() {
        LiveCodec codec = lm().liveCodec(LiveConfig.builder("gemini-3.1-flash-live-preview").system("Be concise.").build());
        assertEquals(Json.parse("{\"setup\":{\"model\":\"models/gemini-3.1-flash-live-preview\",\"systemInstruction\":{\"parts\":[{\"text\":\"Be concise.\"}]},"
            + "\"generationConfig\":{\"responseModalities\":[\"AUDIO\"]},\"outputAudioTranscription\":{}}}"), codec.setupFrames().get(0));
        assertEquals(List.of(Json.obj("realtimeInput", Json.obj("text", "hi"))), codec.encode(new LiveClientEvent.Text("hi")));
        assertEquals(List.of(Json.obj("clientContent", Json.obj("turnComplete", true))), codec.encode(new LiveClientEvent.Interrupt()));
        assertTrue(codec.decode("{\"setupComplete\":{}}".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(codec.isSetupComplete("{\"setupComplete\":{}}".getBytes(StandardCharsets.UTF_8)));
        List<LiveServerEvent> events = codec.decode(("{\"serverContent\":{\"turnComplete\":true},\"usageMetadata\":{\"promptTokenCount\":3,\"responseTokenCount\":2,"
            + "\"totalTokenCount\":5}}").getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(new LiveServerEvent.TurnEnd(new Usage(3, 2, 5, null, null, null, null, null))), events);
        List<LiveServerEvent> call = codec.decode("{\"toolCall\":{\"functionCalls\":[{\"name\":\"get_weather\",\"args\":{\"city\":\"Montreal\"},\"id\":\"fc_1\"}]}}".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(new LiveServerEvent.ToolCall("fc_1", "get_weather", Json.obj("city", "Montreal"))), call);
    }
}
