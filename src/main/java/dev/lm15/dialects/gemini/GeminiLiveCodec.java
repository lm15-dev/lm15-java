package dev.lm15.dialects.gemini;

import dev.lm15.dialects.Common;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.InvalidRequestError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.live.LiveCodec;
import dev.lm15.types.*;
import dev.lm15.wire.BuildContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Gemini Live (BidiGenerateContent) websocket codec: one {@code setup}
 * frame at connect, {@code clientContent} / {@code realtimeInput} /
 * {@code toolResponse} client frames, {@code serverContent} / {@code toolCall}
 * server frames. Audio-native models ({@code live-preview}, {@code native-audio})
 * answer AUDIO with a transcript and take text as {@code realtimeInput}.
 * The {@code setupComplete} acknowledgement and housekeeping frames decode
 * to nothing.
 */
public final class GeminiLiveCodec implements LiveCodec {
    private static final String WS_PATH = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

    private final BuildContext cx;
    private final LiveConfig config;
    private final boolean audioNative;

    public GeminiLiveCodec(BuildContext cx, LiveConfig config) {
        this.cx = cx;
        this.config = config;
        this.audioNative = isAudioNative(config.model());
    }

    public static boolean isAudioNative(String model) {
        String lowered = model.toLowerCase();
        return lowered.contains("live-preview") || lowered.contains("native-audio");
    }

    /** The socket URL without the key: the session appends {@code key=} from the credential (the door's query-key scheme). */
    @Override public String url() {
        String base = cx.baseUrl();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        int schemeEnd = base.indexOf("://");
        String scheme = schemeEnd < 0 ? "https" : base.substring(0, schemeEnd);
        String rest = schemeEnd < 0 ? base : base.substring(schemeEnd + 3);
        int slash = rest.indexOf('/');
        String netloc = slash < 0 ? rest : rest.substring(0, slash);
        return (scheme.equals("https") ? "wss" : "ws") + "://" + netloc + WS_PATH;
    }

    @Override public List<Map.Entry<String, String>> headers() { return List.of(); }

    // ─── setup ───

    @Override public List<JsonValue> setupFrames() { return List.of(setupFrame()); }

    JsonObject setupFrame() {
        JsonBuilder setup = new JsonBuilder().put("model", GeminiContents.modelPath(cx.model()));
        if (config.system() != null) setup.put("systemInstruction", GeminiContents.systemInstruction(config.system()));
        List<JsonValue> functions = GeminiContents.functionDeclarations(config.tools());
        if (!functions.isEmpty()) setup.put("tools", Json.arr(Json.obj("functionDeclarations", new JsonArray(functions))));
        JsonBuilder generation = new JsonBuilder();
        if (config.outputFormat() != null || audioNative) generation.put("responseModalities", List.of("AUDIO"));
        if (config.voice() != null && !generation.has("speechConfig")) {
            generation.put("speechConfig", Json.obj("voiceConfig", Json.obj("prebuiltVoiceConfig", Json.obj("voiceName", config.voice()))));
        }
        if (!generation.isEmpty()) setup.put("generationConfig", generation.build());
        if (config.extensions() != null) setup.putAll(config.extensions());
        if (audioNative) setup.put("outputAudioTranscription", JsonObject.EMPTY);
        return Json.obj("setup", setup.build());
    }

    // ─── client events → frames ───

    @Override public List<JsonValue> encode(LiveClientEvent event) {
        switch (event) {
            case LiveClientEvent.Text t -> {
                if (audioNative) return List.of(Json.obj("realtimeInput", Json.obj("text", t.text())));
                return List.of(Json.obj("clientContent", Json.obj(
                    "turns", Json.arr(Json.obj("role", "user", "parts", Json.arr(Json.obj("text", t.text())))), "turnComplete", true)));
            }
            case LiveClientEvent.Turn turn -> {
                List<JsonValue> parts = new ArrayList<>();
                for (Part p : turn.parts()) parts.add(GeminiContents.part(p, cx.provider()));
                return List.of(Json.obj("clientContent", Json.obj(
                    "turns", Json.arr(Json.obj("role", "user", "parts", new JsonArray(parts))), "turnComplete", turn.turnComplete())));
            }
            case LiveClientEvent.Audio a -> {
                return List.of(Json.obj("realtimeInput", Json.obj("audio", Json.obj("mimeType", a.mediaType(), "data", a.data()))));
            }
            case LiveClientEvent.Image i -> {
                return List.of(Json.obj("realtimeInput", Json.obj("video", Json.obj("mimeType", i.mediaType(), "data", i.data()))));
            }
            case LiveClientEvent.Interrupt x -> { return List.of(Json.obj("clientContent", Json.obj("turnComplete", true))); }
            case LiveClientEvent.EndAudio x -> { return List.of(Json.obj("realtimeInput", Json.obj("audioStreamEnd", true))); }
            case LiveClientEvent.ToolResult r -> {
                JsonArray output = Json.arr(Json.obj("text", Common.partsToText(r.content())));
                return List.of(Json.obj("toolResponse", Json.obj("functionResponses",
                    Json.arr(Json.obj("id", r.id(), "response", Json.obj("output", output))))));
            }
        }
    }

    // ─── server frames → events ───

    private static JsonObject parseFrame(byte[] frame) {
        try {
            JsonValue payload = Json.parse(new String(frame, StandardCharsets.UTF_8));
            return payload instanceof JsonObject o ? o : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True on {@code setupComplete}; an error frame is the typed error; anything else keeps waiting. */
    @Override public boolean isSetupComplete(byte[] frame) {
        JsonObject payload = parseFrame(frame);
        if (payload == null) return false;
        if (payload.has("setupComplete")) return true;
        if (payload.has("error")) {
            JsonValue err = payload.get("error");
            String message = err instanceof JsonObject e ? GeminiJson.strOrEmpty(e.get("message")) : GeminiJson.str(err);
            String code = err instanceof JsonObject e && GeminiJson.truthy(e.get("status")) ? GeminiJson.str(e.get("status")) : "live_setup";
            throw new InvalidRequestError("Live setup failed: " + message, ErrorMeta.of(cx.provider()).withProviderCode(code));
        }
        return false;
    }

    private static LiveServerEvent.ToolCall toolCall(JsonObject fc) {
        String id = GeminiJson.strOrEmpty(fc.get("id"));
        String name = GeminiJson.strOrEmpty(fc.get("name"));
        JsonObject input = fc.get("args") instanceof JsonObject args ? args : JsonObject.EMPTY;
        return new LiveServerEvent.ToolCall(id.isEmpty() ? "fc_0" : id, name.isEmpty() ? "tool" : name, input);
    }

    private Usage liveUsage(JsonObject payload, JsonObject server) {
        JsonValue metadata = payload.get("usageMetadata");
        if (!(metadata instanceof JsonObject) && server != null) metadata = server.get("usageMetadata");
        return GeminiResponses.usage(metadata, GeminiResponses.LIVE_OUTPUT_KEYS);
    }

    @Override public List<LiveServerEvent> decode(byte[] frame) {
        List<LiveServerEvent> events = new ArrayList<>();
        JsonObject payload = parseFrame(frame);
        if (payload == null) return events;
        if (payload.has("error")) {
            JsonValue err = payload.get("error");
            events.add(new LiveServerEvent.Error(GeminiErrors.errorDetail(cx, GeminiErrors.envelopeCode(err), GeminiErrors.envelopeMessage(err))));
            return events;
        }
        JsonObject toolCall = GeminiJson.objectAt(payload, "toolCall");
        if (toolCall != null) {
            JsonArray calls = GeminiJson.truthy(toolCall.get("functionCalls")) ? GeminiJson.arr(toolCall.get("functionCalls")) : null;
            if (calls != null) for (JsonValue fc : calls) if (fc instanceof JsonObject o) events.add(toolCall(o));
        }
        JsonObject server = GeminiJson.objectAt(payload, "serverContent");
        if (server == null) return events;
        JsonObject modelTurn = GeminiJson.objectAt(server, "modelTurn");
        if (modelTurn != null) {
            JsonArray parts = GeminiJson.truthy(modelTurn.get("parts")) ? GeminiJson.arr(modelTurn.get("parts")) : null;
            if (parts != null) {
                for (JsonValue pv : parts) {
                    if (!(pv instanceof JsonObject part)) continue;
                    if (part.has("text")) {
                        events.add(new LiveServerEvent.Text(GeminiJson.strOrEmpty(part.get("text"))));
                    } else if (part.get("inlineData") instanceof JsonObject inline) {
                        String mime = GeminiJson.strOrEmpty(inline.get("mimeType"));
                        if (mime.startsWith("audio/")) {
                            events.add(new LiveServerEvent.Audio(GeminiJson.strOrEmpty(inline.get("data")), mime.isEmpty() ? null : mime));
                        }
                    } else if (part.get("functionCall") instanceof JsonObject fc) {
                        events.add(toolCall(fc));
                    }
                }
            }
        }
        JsonObject transcription = GeminiJson.objectAt(server, "outputTranscription");
        if (transcription != null && GeminiJson.truthy(transcription.get("text"))) {
            events.add(new LiveServerEvent.Text(GeminiJson.str(transcription.get("text"))));
        }
        boolean turnComplete = GeminiJson.truthy(server.get("turnComplete"));
        boolean hasUsage = payload.get("usageMetadata") instanceof JsonObject || server.get("usageMetadata") instanceof JsonObject;
        if (hasUsage && !turnComplete) {
            // Usage on a frame that does not end the turn (interrupted or tool-call) rides a usage event; the
            // pinned transcripts report usage only on turnComplete, so this is the rule, not a receipt.
            events.add(new LiveServerEvent.UsageEvent(liveUsage(payload, server)));
        }
        if (GeminiJson.truthy(server.get("interrupted"))) events.add(new LiveServerEvent.Interrupted());
        if (turnComplete) events.add(new LiveServerEvent.TurnEnd(liveUsage(payload, server)));
        return events;
    }
}
