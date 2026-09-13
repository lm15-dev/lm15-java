package dev.lm15;

import dev.lm15.auth.*;
import dev.lm15.errors.NotConfiguredError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonObject;
import dev.lm15.router.LMRouter;
import dev.lm15.router.RouterConfig;
import dev.lm15.transport.HttpResponse;
import dev.lm15.transport.Transport;
import dev.lm15.types.Request;
import dev.lm15.wire.Clock;
import dev.lm15.wire.TransportRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ClientBindingTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"));
    static final Request REQUEST = Request.builder("gpt-4.1").user("hello").build();
    static final String AWS_SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";

    static final class Scripted implements Transport {
        final List<TransportRequest> requests = new ArrayList<>();
        final Deque<HttpResponse> replies = new ArrayDeque<>();
        Scripted(JsonObject... responses) {
            for (JsonObject response : responses) replies.add(HttpResponse.json(200, Json.write(response).getBytes(StandardCharsets.UTF_8)));
        }
        public HttpResponse send(TransportRequest request) {
            requests.add(request);
            if (replies.isEmpty()) throw new AssertionError("unexpected HTTP request: " + request.method());
            return replies.removeFirst();
        }
        public Streaming stream(TransportRequest request) { throw new AssertionError("unexpected stream"); }
    }

    static Map<String, String> env(Path home, String... pairs) {
        Map<String, String> env = new HashMap<>();
        env.put("HOME", home.toString());
        env.put("AWS_EC2_METADATA_DISABLED", "true");
        env.put("NO_GCE_CHECK", "true");
        for (int i = 0; i < pairs.length; i += 2) env.put(pairs[i], pairs[i + 1]);
        return env;
    }

    static JsonObject answer() {
        return Json.obj("id", "r", "model", "gpt-4.1", "status", "completed", "output",
            Json.arr(Json.obj("type", "message", "content", Json.arr(Json.obj("type", "output_text", "text", "hello")))));
    }

    @Test void dynamicCredentialsRunOnlyOncePerRequestNotAtConstruction() {
        AtomicInteger calls = new AtomicInteger();
        try (ProviderLM lm = OpenAILM.builder().credentials(() -> {
            calls.incrementAndGet(); return new Credential.ApiKey("test");
        }).transport(new Scripted()).build()) {
            assertEquals(0, calls.get());
            lm.buildRequest(REQUEST, false);
            assertEquals(1, calls.get());
            lm.buildRequest(REQUEST, true);
            assertEquals(2, calls.get());
        }
    }

    @Test void nullCallbackDoesNotSendOrFallBackToAmbientCredentials() {
        Scripted transport = new Scripted();
        try (ProviderLM lm = OpenAILM.builder().env(Map.of("OPENAI_API_KEY", "other-account"))
                .credentials(() -> null).transport(transport).build()) {
            assertThrows(NotConfiguredError.class, () -> lm.complete(REQUEST));
            assertTrue(transport.requests.isEmpty());
        }
    }

    @Test void directAdapterResolvesEnvironmentAndBuilderCanBeReused(@TempDir Path home) {
        var builder = OpenAILM.builder().transport(new Scripted()).env(env(home, "OPENAI_API_KEY", "first"));
        try (ProviderLM first = builder.build(); ProviderLM second = builder.env(env(home, "OPENAI_API_KEY", "second")).build()) {
            assertEquals("Bearer first", first.buildRequest(REQUEST, false).header("authorization"));
            assertEquals("Bearer second", second.buildRequest(REQUEST, false).header("authorization"));
        }
    }

    @Test void awsProfileSettingsAndCredentialsReachPublicRouter(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve(".aws"));
        Files.writeString(home.resolve(".aws/config"), "[profile work]\nregion=us-east-2\n");
        Files.writeString(home.resolve(".aws/credentials"), "[work]\naws_access_key_id=AKIDEXAMPLE\naws_secret_access_key=" + AWS_SECRET + "\n");
        var environment = env(home, "AWS_PROFILE", "work");
        assertTrue(Doctor.explainAuth("bedrock-chat", Map.of(), environment, null, null, home.toString(), Map.of()).configured());
        LMRouter router = new LMRouter(RouterConfig.builder().env(environment).clock(CLOCK).transport(new Scripted()).build());
        try (ProviderLM lm = router.lm("bedrock-chat:test-model")) {
            TransportRequest wire = lm.buildRequest(REQUEST.withModel("test-model"), false);
            assertEquals("us-east-2", lm.settings().get("region"));
            assertTrue(wire.url().contains("bedrock-runtime.us-east-2.amazonaws.com"));
            assertTrue(wire.header("authorization").contains("Credential=AKIDEXAMPLE/20260101/us-east-2/bedrock/aws4_request"));
            assertEquals("20260101T000000Z", wire.header("x-amz-date"));
        }
    }

    @Test void azureUsesInjectedTransportAndCachesTokenWithInjectedClock(@TempDir Path home) {
        Scripted transport = new Scripted(Json.obj("access_token", "azure-test", "expires_in", 3600), answer(), answer());
        var environment = env(home, "AZURE_TENANT_ID", "tenant", "AZURE_CLIENT_ID", "client", "AZURE_CLIENT_SECRET", "secret",
            "AZURE_TOKEN_CREDENTIALS", "EnvironmentCredential");
        LMRouter router = new LMRouter(RouterConfig.builder().env(environment).clock(CLOCK).transport(transport)
            .setting("azure", "resource", "resource").build());
        ProviderLM lm = router.lm("azure:gpt-4.1");
        assertTrue(transport.requests.isEmpty(), "construction must not exchange tokens");
        assertEquals("hello", router.complete(REQUEST.withModel("azure:gpt-4.1")).text());
        assertEquals("hello", router.complete(REQUEST.withModel("azure:gpt-4.1")).text());
        assertEquals(3, transport.requests.size());
        assertTrue(transport.requests.get(0).url().contains("login.microsoftonline.com/tenant/oauth2/v2.0/token"));
        assertEquals("Bearer azure-test", transport.requests.get(1).header("authorization"));
        lm.close();
    }

    @Test void vertexFindsAdcProjectAndUsesInjectedTransport(@TempDir Path home) throws Exception {
        Path adc = home.resolve("adc.json");
        Files.writeString(adc, Json.write(Json.obj("type", "authorized_user", "client_id", "client", "client_secret", "secret",
            "refresh_token", "refresh", "quota_project_id", "test-project")));
        Scripted transport = new Scripted(Json.obj("access_token", "vertex-test", "expires_in", 3600));
        try (ProviderLM lm = ProviderLM.builder("vertex").env(env(home, "GOOGLE_APPLICATION_CREDENTIALS", adc.toString()))
                .setting("location", "us-central1").clock(CLOCK).transport(transport).build()) {
            assertEquals("test-project", lm.settings().get("project"));
            assertTrue(transport.requests.isEmpty());
            TransportRequest wire = lm.buildRequest(REQUEST.withModel("gemini-2.5-flash"), false);
            assertEquals("Bearer vertex-test", wire.header("authorization"));
            assertTrue(wire.url().contains("projects/test-project/locations/us-central1"));
            assertEquals(1, transport.requests.size());
        }
    }

    @Test void storedCodexConstructionDoesNotRefreshAndUsesExplicitHome(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve(".codex"));
        Files.writeString(home.resolve(".codex/auth.json"), Json.write(Json.obj("tokens", Json.obj("access_token", "fake-token", "account_id", "account-1"))));
        try (ProviderLM lm = OpenAICodexLM.builder().env(env(home)).transport(new Scripted()).build()) {
            TransportRequest wire = lm.buildRequest(REQUEST, true);
            assertEquals("Bearer fake-token", wire.header("authorization"));
            assertEquals("account-1", wire.header("chatgpt-account-id"));
        }
    }

    @Test void codexCompleteAssemblesStreamInsteadOfParsingSseAsJson() {
        AtomicInteger closes = new AtomicInteger();
        String frames = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n"
            + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n";
        Transport transport = new Transport() {
            public HttpResponse send(TransportRequest r) { throw new AssertionError("Codex must use the streaming transport"); }
            public Streaming stream(TransportRequest r) {
                assertTrue(r.body().asObject().get("stream").asBool());
                return new Streaming(200, List.of(), new ByteArrayInputStream(frames.getBytes(StandardCharsets.UTF_8)) {
                    public void close() { closes.incrementAndGet(); }
                });
            }
        };
        try (ProviderLM lm = OpenAICodexLM.builder().apiKey("test").transport(transport).build()) {
            assertEquals("hello", lm.complete(REQUEST).text());
            assertEquals(1, closes.get());
        }
    }
}
