package dev.lm15.dialects.anthropic;

import dev.lm15.ProviderLM;
import dev.lm15.compat.AnthropicCompat;
import dev.lm15.errors.ContextLengthError;
import dev.lm15.errors.LM15Error;
import dev.lm15.errors.ProviderError;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.errors.UnsupportedModelError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonObject;
import dev.lm15.stream.Streams;
import dev.lm15.transport.HttpResponse;
import dev.lm15.types.*;
import dev.lm15.wire.TransportRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicDialectTest {
    private static ProviderLM lm(String provider) {
        return ProviderLM.builder(provider).apiKey("test-key").build();
    }

    private static Request request(Config config) {
        return Request.builder("claude-sonnet-4-5").user("Say hello.").config(config).build();
    }

    @Test
    void buildAndParseRoundTrip() {
        ProviderLM lm = lm("anthropic");
        Request request = request(Config.builder().maxTokens(256).temperature(0.5).build());
        TransportRequest wire = lm.buildRequest(request, false);
        assertEquals("POST", wire.method());
        assertEquals("https://api.anthropic.com/v1/messages", wire.url());
        assertEquals("test-key", wire.header("x-api-key"));
        assertEquals("2023-06-01", wire.header("anthropic-version"));
        JsonObject body = wire.body().asObject();
        assertEquals(List.of("model", "messages", "stream", "max_tokens", "temperature"), List.copyOf(body.keys()));
        assertEquals("claude-sonnet-4-5", body.get("model").asString());
        assertEquals(256, body.get("max_tokens").asInt());
        assertTrue(body.get("temperature").isFloat());

        String text = "{\"id\":\"msg_1\",\"model\":\"claude-sonnet-4-5\",\"stop_reason\":\"end_turn\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"Hello!\"}],"
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":3,\"cache_read_input_tokens\":4}}";
        Response response = lm.parseResponse(request, HttpResponse.json(200, text.getBytes(StandardCharsets.UTF_8)));
        assertEquals("msg_1", response.id());
        assertEquals("Hello!", response.text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertEquals(10, response.usage().inputTokens());
        assertEquals(13, response.usage().totalTokens());
        assertEquals(4, response.usage().cacheReadTokens());
        assertNull(response.usage().reasoningTokens());
        assertNull(response.providerData().get("_lm15_unmapped"));
    }

    @Test
    void manualClassReasoningAddsBudgetToCeiling() {
        JsonObject body = lm("anthropic").buildRequest(request(Config.builder().maxTokens(1000)
            .reasoning(Reasoning.of(ReasoningEffort.LOW)).build()), false).body().asObject();
        assertEquals(Json.obj("type", "enabled", "budget_tokens", 2048), body.get("thinking"));
        assertEquals(3048, body.get("max_tokens").asInt());
    }

    @Test
    void jsonObjectResponseFormatIsRefusedBeforeAnyWire() {
        Request request = request(Config.builder().responseFormat(Json.obj("type", "json_object")).build());
        UnsupportedFeatureError err = assertThrows(UnsupportedFeatureError.class, () -> lm("anthropic").buildRequest(request, false));
        assertEquals(ErrorCode.UNSUPPORTED_FEATURE, err.code());
        assertEquals("anthropic", err.provider());
    }

    @Test
    void allowlistSubsetIsRefusedAndSingleForcedToolIsNamed() {
        List<Tool> tools = List.of(new FunctionTool("a", "A"), new FunctionTool("b", "B"));
        Request subset = Request.builder("claude-sonnet-4-5").user("hi").tools(tools)
            .config(Config.builder().toolChoice(new ToolChoice(ToolChoiceMode.AUTO, List.of("a"), null)).build()).build();
        assertThrows(UnsupportedFeatureError.class, () -> lm("anthropic").buildRequest(subset, false));

        Request forced = Request.builder("claude-sonnet-4-5").user("hi").tools(tools)
            .config(Config.builder().toolChoice(new ToolChoice(ToolChoiceMode.REQUIRED, List.of("a"), false)).build()).build();
        JsonObject body = lm("anthropic").buildRequest(forced, false).body().asObject();
        assertEquals(Json.obj("type", "tool", "name", "a", "disable_parallel_tool_use", true), body.get("tool_choice"));
    }

    @Test
    void presetsRefuseWhatTheirServersSilentlyIgnore() {
        assertEquals("deepseek", AnthropicCompat.preset("DeepSeek").thinkingFormat());
        assertEquals("unsigned", AnthropicCompat.preset("moonshotai").thinkingReplay());
        assertEquals("signed", AnthropicCompat.DEFAULT.thinkingReplay());
        assertThrows(ValidationException.class, () -> AnthropicCompat.preset("groq"));

        Request claude = Request.builder("claude-opus-4-1").user("hi").build();
        assertThrows(UnsupportedModelError.class, () -> lm("deepseek-anthropic").buildRequest(claude, false));

        Request off = Request.builder("deepseek-v4-pro").user("hi").config(Config.builder().reasoning(Reasoning.OFF).build()).build();
        JsonObject body = lm("deepseek-anthropic").buildRequest(off, false).body().asObject();
        assertEquals(Json.obj("type", "disabled"), body.get("thinking"));
    }

    @Test
    void claudeCodePolicyPrefixesTheSystemPromptAndJoinsBetas() {
        Request request = Request.builder("claude-sonnet-4-5").user("hi").system("be brief")
            .tool(new BuiltinTool("code_execution")).build();
        TransportRequest wire = lm("claude-code").buildRequest(request, false);
        assertEquals("claude-code-20250219,oauth-2025-04-20,code-execution-2025-05-22", wire.header("anthropic-beta"));
        JsonArray system = wire.body().asObject().get("system").asArray();
        assertEquals(2, system.size());
        assertEquals("be brief", system.get(1).asObject().get("text").asString());
    }

    @Test
    void streamReplayCoalescesToOneStartAndOneEnd() {
        String sse = frame("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_s\",\"model\":\"claude-sonnet-4-5\"}}")
            + frame("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\",\"input\":{}}}")
            + frame("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\"}}")
            + frame("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"Paris\\\"}\"}}")
            + frame("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
            + frame("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":12}}")
            + frame("message_stop", "{\"type\":\"message_stop\"}");
        Request request = request(Config.DEFAULT);
        List<StreamEvent> events = lm("anthropic").replayStream(request, sse.getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(StreamStartEvent.class, events.get(0));
        assertEquals(1, events.stream().filter(e -> e instanceof StreamEndEvent).count());
        StreamEndEvent end = (StreamEndEvent) events.get(events.size() - 1);
        assertEquals(FinishReason.TOOL_CALL, end.finishReason());
        assertEquals("message_delta", end.providerData().get("type").asString());
        Response response = Streams.materialize(events, request);
        assertEquals("msg_s", response.id());
        assertEquals(1, response.toolCalls().size());
        assertEquals(Json.obj("city", "Paris"), response.toolCalls().get(0).input());
    }

    private static String frame(String event, String data) { return "event: " + event + "\ndata: " + data + "\n\n"; }

    @Test
    void unnamedToolUseIsRefusedOnTheCompletePath() {
        String text = "{\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"input\":{}}],\"stop_reason\":\"tool_use\"}";
        ProviderError err = assertThrows(ProviderError.class,
            () -> lm("anthropic").parseResponse(request(Config.DEFAULT), HttpResponse.json(200, text.getBytes(StandardCharsets.UTF_8))));
        assertEquals(ErrorCode.PROVIDER, err.code());
    }

    @Test
    void errorsMapByTypeAndMessage() {
        LM15Error ctx = lm("anthropic").normalizeError(400, "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"prompt is too long\"},\"request_id\":\"req_1\"}");
        assertInstanceOf(ContextLengthError.class, ctx);
        assertEquals("invalid_request_error", ctx.providerCode());
        assertEquals("req_1", ctx.requestId());
        LM15Error model = lm("moonshotai-anthropic").normalizeError(404, "{\"error\":{\"type\":\"resource_not_found_error\",\"message\":\"Not found the model kimi-x\"}}");
        assertInstanceOf(UnsupportedModelError.class, model);
        LM15Error raw = lm("anthropic").normalizeError(503, "<html>down</html>");
        assertEquals(ErrorCode.SERVER, raw.code());
        assertNull(raw.providerCode());
    }

    @Test
    void endedBatchStatusSplitsOnRequestCounts() {
        assertEquals(BatchStatus.CANCELLED, AnthropicDialect.batchStatus(Json.obj("processing_status", "ended", "request_counts", Json.obj("canceled", 2))));
        assertEquals(BatchStatus.EXPIRED, AnthropicDialect.batchStatus(Json.obj("processing_status", "ended", "request_counts", Json.obj("expired", 1))));
        assertEquals(BatchStatus.COMPLETED, AnthropicDialect.batchStatus(Json.obj("processing_status", "ended", "request_counts", Json.obj("succeeded", 1, "canceled", 1))));
        assertEquals(BatchStatus.RUNNING, AnthropicDialect.batchStatus(Json.obj("processing_status", "in_progress")));
        assertEquals(BatchStatus.QUEUED, AnthropicDialect.batchStatus(JsonObject.EMPTY));
    }
}
