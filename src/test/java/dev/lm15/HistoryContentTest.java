package dev.lm15;

import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.json.*;
import dev.lm15.serde.Canonical;
import dev.lm15.types.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HistoryContentTest {
    static Request history(Part... parts) {
        return Request.builder("test-model").message(new Message(Role.ASSISTANT, List.of(parts), List.of()))
            .user("Describe the earlier output.").build();
    }
    static Part media(String kind) {
        String mime = switch (kind) {
            case "image" -> "image/png";
            case "audio" -> "audio/wav";
            case "video" -> "video/mp4";
            case "document" -> "application/pdf";
            default -> "application/octet-stream";
        };
        return Canonical.partFromJson(Json.obj("type", kind, "media_type", mime, "url", "https://example.invalid/original"));
    }

    @Test void chatRefusesAssistantMediaBeforeCredentialCallbacks() {
        AtomicInteger calls = new AtomicInteger();
        try (ProviderLM lm = OpenAIChatLM.builder().credentials(() -> {
            calls.incrementAndGet(); return new dev.lm15.auth.Credential.ApiKey("test");
        }).build()) {
            for (String kind : List.of("image", "document", "binary", "audio", "video")) {
                for (boolean stream : List.of(false, true)) {
                    var error = assertThrows(UnsupportedFeatureError.class,
                        () -> lm.buildRequest(history(new TextPart("before"), media(kind)), stream));
                    assertTrue(error.message().contains(kind));
                }
            }
            assertEquals(0, calls.get());
        }
    }

    @Test void responsesKeepsNativeMediaAndTextTogetherInOrder() {
        try (ProviderLM lm = OpenAILM.create("test")) {
            Request request = history(new TextPart("before"), media("image"), media("document"), new TextPart("after"));
            JsonObject original = Canonical.toJson(request);
            for (boolean stream : List.of(false, true)) {
                JsonObject message = lm.buildRequest(request, stream).body().asObject().get("input").asArray().get(0).asObject();
                assertEquals("assistant", message.get("role").asString());
                assertEquals(Json.arr(
                    Json.obj("type", "input_text", "text", "before"),
                    Json.obj("type", "input_image", "image_url", "https://example.invalid/original"),
                    Json.obj("type", "input_file", "file_url", "https://example.invalid/original"),
                    Json.obj("type", "input_text", "text", "after")), message.get("content"));
            }
            assertEquals(original, Canonical.toJson(request));
        }
    }

    @Test void uploadedImageDetailSurvivesHistoryReplay() {
        Part image = Canonical.partFromJson(Json.obj("type", "image", "media_type", "image/png", "file_id", "file-test", "detail", "high"));
        try (ProviderLM lm = OpenAILM.create("test")) {
            JsonObject first = lm.buildRequest(history(image), false).body().asObject().get("input").asArray().get(0).asObject();
            assertEquals(Json.arr(Json.obj("type", "input_image", "file_id", "file-test", "detail", "high")), first.get("content"));
        }
    }

    @Test void responsesRefusesAssistantAudioVideoAndMixedRefusal() {
        try (ProviderLM lm = OpenAILM.create("test")) {
            for (String kind : List.of("audio", "video")) {
                assertThrows(UnsupportedFeatureError.class, () -> lm.buildRequest(history(media(kind)), false));
            }
            assertThrows(UnsupportedFeatureError.class,
                () -> lm.buildRequest(history(new RefusalPart("no"), media("image")), false));
        }
    }

    @Test void anthropicRefusesUnsupportedMediaInsteadOfSendingEmptyText() {
        try (ProviderLM lm = AnthropicLM.create("test")) {
            for (String kind : List.of("audio", "video", "binary")) {
                assertThrows(UnsupportedFeatureError.class, () -> lm.buildRequest(history(media(kind)), false));
            }
        }
    }

    @Test void citationDetailsSurviveEveryDialect() {
        Part citation = new CitationPart("https://example.invalid/source", "Source title", "Quoted evidence");
        for (String provider : List.of("openai", "openai-chat", "anthropic", "gemini")) {
            try (ProviderLM lm = ProviderLM.builder(provider).apiKey("test").build()) {
                String body = Json.write(lm.buildRequest(history(citation), false).body());
                for (String text : List.of("https://example.invalid/source", "Source title", "Quoted evidence")) {
                    assertTrue(body.contains(text), provider + ": lost " + text);
                }
            }
        }
    }
}
