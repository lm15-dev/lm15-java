package dev.lm15;

import dev.lm15.errors.RateLimitError;
import dev.lm15.json.Json;
import dev.lm15.router.LMRouter;
import dev.lm15.router.RouterConfig;
import dev.lm15.stream.ResponseStream;
import dev.lm15.transport.HttpResponse;
import dev.lm15.transport.Transport;
import dev.lm15.types.*;
import dev.lm15.wire.TransportRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The README's shape end to end over a scripted transport: router, direct adapter, stream, tool round trip, error metadata. */
class EndToEndTest {
    /** A transport that answers with scripted replies and records what was sent. */
    static final class Scripted implements Transport {
        final List<TransportRequest> sent = new ArrayList<>();
        final List<HttpResponse> replies = new ArrayList<>();

        Scripted reply(int status, String body, String... headers) {
            List<Map.Entry<String, String>> h = new ArrayList<>();
            h.add(Map.entry("content-type", body.startsWith("data:") ? "text/event-stream" : "application/json"));
            for (int i = 0; i + 1 < headers.length; i += 2) h.add(Map.entry(headers[i], headers[i + 1]));
            replies.add(new HttpResponse(status, h, body.getBytes(StandardCharsets.UTF_8)));
            return this;
        }

        @Override public HttpResponse send(TransportRequest request) {
            sent.add(request);
            return replies.remove(0);
        }

        @Override public Streaming stream(TransportRequest request) {
            sent.add(request);
            HttpResponse r = replies.remove(0);
            return new Streaming(r.status(), r.headers(), new ByteArrayInputStream(r.body()));
        }
    }

    private static final String CHAT_BODY = """
        {"id":"chatcmpl-1","object":"chat.completion","model":"gpt-4.1-mini","choices":[{"index":0,"message":{"role":"assistant","content":"Hello there, friend."},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""";

    private static final String TOOL_BODY = """
        {"id":"chatcmpl-2","object":"chat.completion","model":"gpt-4.1-mini","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\\"city\\":\\"Montreal\\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":20,"completion_tokens":8,"total_tokens":28}}""";

    private static final String STREAM_BODY = """
        data: {"id":"chatcmpl-3","object":"chat.completion.chunk","model":"gpt-4.1-mini","choices":[{"index":0,"delta":{"role":"assistant","content":"Mont"},"finish_reason":null}]}

        data: {"id":"chatcmpl-3","object":"chat.completion.chunk","model":"gpt-4.1-mini","choices":[{"index":0,"delta":{"content":"real"},"finish_reason":null}]}

        data: {"id":"chatcmpl-3","object":"chat.completion.chunk","model":"gpt-4.1-mini","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

        data: {"id":"chatcmpl-3","object":"chat.completion.chunk","model":"gpt-4.1-mini","choices":[],"usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}

        data: [DONE]

        """;

    @Test void routerCompleteWithExplicitKeyAndEnv() {
        Scripted t = new Scripted().reply(200, CHAT_BODY);
        LMRouter router = new LMRouter(RouterConfig.builder().apiKey("groq", "gsk-test").env(Map.of()).transport(t).build());
        Request request = Request.builder("groq:openai/gpt-oss-20b").system("You are terse.").user("Say hello in three words.")
            .config(Config.builder().maxTokens(50).temperature(0.2).build()).build();
        Response response = router.complete(request);
        assertEquals("Hello there, friend.", response.text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertEquals(15, response.usage().totalTokens());
        TransportRequest sent = t.sent.get(0);
        assertEquals("https://api.groq.com/openai/v1/chat/completions", sent.url());
        assertEquals("Bearer gsk-test", sent.header("authorization"));
        assertEquals("openai/gpt-oss-20b", sent.body().asObject().get("model").asString());
        assertEquals(1.0 * 0.2, sent.body().asObject().get("temperature").asDouble());
        assertEquals("groq", router.resolve("groq:x").provider());
    }

    @Test void streamAndResponseStream() {
        Scripted t = new Scripted().reply(200, STREAM_BODY).reply(200, STREAM_BODY);
        ProviderLM lm = OpenAIChatLM.builder().apiKey("sk-test").transport(t).build();
        Request request = Request.builder("gpt-4.1-mini").user("One word about Montreal.").build();
        List<StreamEvent> events = new ArrayList<>();
        try (ProviderLM.EventStream es = lm.stream(request)) {
            es.forEachRemaining(events::add);
        }
        assertInstanceOf(StreamStartEvent.class, events.get(0));                       // MAP-4 synthesized
        assertInstanceOf(StreamEndEvent.class, events.get(events.size() - 1));         // MAP-3 one merged end
        assertEquals(9, ((StreamEndEvent) events.get(events.size() - 1)).usage().totalTokens());
        assertTrue(t.sent.get(0).body().asObject().get("stream").asBool());
        StringBuilder sb = new StringBuilder();
        try (ResponseStream rs = lm.responseStream(request)) {
            for (String text : rs) sb.append(text);
            assertEquals("Montreal", sb.toString());
            assertEquals("Montreal", rs.response().text());
            assertNull(rs.response().id());                                              // MAP-9.6: the chunk id is not lifted
        }
    }

    @Test void toolRoundTrip() {
        Scripted t = new Scripted().reply(200, TOOL_BODY).reply(200, CHAT_BODY);
        ProviderLM lm = OpenAIChatLM.builder().apiKey("sk-test").transport(t).build();
        FunctionTool weather = new FunctionTool("get_weather", "Get the current weather for a city.",
            Json.obj("type", "object", "properties", Json.obj("city", Json.obj("type", "string")), "required", Json.arr("city")));
        Request first = Request.builder("gpt-4.1-mini").user("What is the weather in Montreal?").tool(weather)
            .config(Config.builder().toolChoice(ToolChoice.REQUIRED.withParallel(false)).build()).build();
        Response response = lm.complete(first);
        assertEquals(FinishReason.TOOL_CALL, response.finishReason());
        ToolCallPart call = response.toolCalls().get(0);
        assertEquals("get_weather", call.name());
        assertEquals("Montreal", call.input().get("city").asString());
        List<Message> messages = new ArrayList<>(first.messages());
        messages.add(response.message());
        messages.add(Message.tool(call.id(), "Sunny and 22°C in Montreal."));
        Response answer = lm.complete(first.withMessages(messages).withConfig(Config.builder().toolChoice(ToolChoice.NONE).build()));
        assertEquals("Hello there, friend.", answer.text());
        var rows = t.sent.get(1).body().asObject().get("messages").asArray();
        assertEquals("tool", rows.get(rows.size() - 1).asObject().get("role").asString());
        assertEquals("none", t.sent.get(1).body().asObject().get("tool_choice").asString());
    }

    @Test void providerErrorsCarryHeaderMetadata() {
        Scripted t = new Scripted().reply(429, "{\"error\":{\"message\":\"slow down\",\"type\":\"rate_limit_error\",\"code\":\"rate_limit_exceeded\"}}",
            "retry-after", "7", "x-request-id", "req_9");
        ProviderLM lm = OpenAILM.builder().apiKey("sk-test").transport(t).build();
        RateLimitError e = assertThrows(RateLimitError.class, () -> lm.complete(Request.builder("gpt-5.4").user("hi").build()));
        assertEquals(7.0, e.retryAfter());
        assertEquals("req_9", e.requestId());
        assertEquals("rate_limit_exceeded", e.providerCode());
        assertTrue(e.isRetryable());
    }

    @Test void directAdaptersAndPresets() {
        ProviderLM ollama = OpenAIChatLM.builder().apiKey("ollama").preset("ollama").transport(new Scripted()).build();
        assertEquals("http://localhost:11434/v1", ollama.baseUrl());
        ProviderLM anthropic = AnthropicLM.builder().apiKey("sk-ant").transport(new Scripted()).build();
        TransportRequest req = anthropic.buildRequest(Request.builder("claude-sonnet-4-5").user("hi").build(), false);
        assertEquals("https://api.anthropic.com/v1/messages", req.url());
        assertEquals("sk-ant", req.header("x-api-key"));
        assertThrows(dev.lm15.errors.NotConfiguredError.class, () -> OpenAIChatLM.builder().apiKey("k").preset("qwen").build()); // no address row
    }
}
