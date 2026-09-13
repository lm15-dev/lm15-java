package dev.lm15.dialects.openai;

import dev.lm15.dialects.Common;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonNull;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.live.LiveCodec;
import dev.lm15.types.*;
import dev.lm15.wire.BuildContext;
import dev.lm15.wire.Wire;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The OpenAI Realtime (GA) codec: the {@code session.update} setup frame,
 * client events → wire frames, server frames → canonical events. Pure; the
 * socket is the session's business.
 */
public final class OpenAILiveCodec implements LiveCodec {
    private final BuildContext cx;
    private final LiveConfig config;

    public OpenAILiveCodec(BuildContext cx, LiveConfig config) {
        this.cx = cx;
        this.config = config;
    }

    @Override public String url() {
        URI base = URI.create(cx.baseUrl());
        String scheme = "https".equals(base.getScheme()) ? "wss" : "ws";
        String basePath = base.getRawPath() == null ? "" : base.getRawPath();
        while (basePath.endsWith("/")) basePath = basePath.substring(0, basePath.length() - 1);
        String path = basePath.isEmpty() ? "/realtime" : basePath + "/realtime";
        return scheme + "://" + base.getRawAuthority() + path + "?model=" + Wire.formEncode(cx.model());
    }

    /** The policy's static headers; the credential header is added by the session at connect time (AUTH-2). */
    @Override public List<Map.Entry<String, String>> headers() {
        return new ArrayList<>(cx.policy().headers());
    }

    // ─── setup ───

    static JsonObject audioFormat(AudioFormat fmt) {
        // GA wire format objects: {"type": "audio/pcm", "rate": N} (pcmu/pcma carry no rate).
        if (fmt.encoding() == AudioEncoding.PCM16) return Json.obj("type", "audio/pcm", "rate", fmt.sampleRate());
        return Json.obj("type", "audio/" + fmt.encoding().wire());
    }

    static JsonObject sessionUpdate(LiveConfig config) {
        JsonBuilder session = new JsonBuilder().put("type", "realtime");
        if (config.system() != null) {
            session.put("instructions", config.system().isText() ? config.system().text() : Common.partsToText(config.system().parts()));
        }
        JsonBuilder audio = new JsonBuilder();
        if (config.outputFormat() != null || config.voice() != null) {
            session.put("output_modalities", Json.arr("audio"));
            JsonBuilder output = new JsonBuilder();
            if (config.outputFormat() != null) output.put("format", audioFormat(config.outputFormat()));
            if (config.voice() != null) output.put("voice", config.voice());
            audio.put("output", output.build());
        } else {
            session.put("output_modalities", Json.arr("text"));
        }
        if (config.inputFormat() != null) {
            // turn_detection null = server VAD OFF: a turn happens exactly when the caller sends end_audio().
            audio.put("input", new JsonBuilder().put("format", audioFormat(config.inputFormat())).put("turn_detection", JsonNull.INSTANCE).build());
        }
        if (!audio.isEmpty()) session.put("audio", audio.build());
        if (!config.tools().isEmpty()) {
            List<JsonValue> tools = new ArrayList<>();
            for (Tool t : config.tools()) {
                if (t instanceof FunctionTool f) {
                    tools.add(new JsonBuilder().put("type", "function").put("name", f.name()).put("description", f.description()).put("parameters", f.parameters()).build());
                }
            }
            session.put("tools", new JsonArray(tools));
        }
        if (config.extensions() != null) session.putAll(config.extensions());
        return Json.obj("type", "session.update", "session", session.build());
    }

    @Override public List<JsonValue> setupFrames() {
        return List.of(sessionUpdate(config));
    }

    // ─── client events → frames ───

    private static JsonObject userMessage(List<JsonValue> content) {
        return Json.obj("type", "conversation.item.create", "item", Json.obj("type", "message", "role", "user", "content", new JsonArray(content)));
    }

    private static final JsonObject RESPONSE_CREATE = Json.obj("type", "response.create");

    @Override public List<JsonValue> encode(LiveClientEvent event) {
        switch (event) {
            case LiveClientEvent.Audio a -> { return List.of(Json.obj("type", "input_audio_buffer.append", "audio", a.data())); }
            case LiveClientEvent.EndAudio e -> { return List.of(Json.obj("type", "input_audio_buffer.commit"), RESPONSE_CREATE); }
            case LiveClientEvent.Interrupt i -> { return List.of(Json.obj("type", "response.cancel")); }
            case LiveClientEvent.Text t -> {
                return List.of(userMessage(List.of(Json.obj("type", "input_text", "text", t.text()))), RESPONSE_CREATE);
            }
            case LiveClientEvent.Turn turn -> {
                List<JsonValue> content = new ArrayList<>();
                for (Part p : turn.parts()) content.add(Common.partToOpenAIInput(p, null));
                return turn.turnComplete() ? List.of(userMessage(content), RESPONSE_CREATE) : List.of(userMessage(content));
            }
            case LiveClientEvent.Image img -> {
                return List.of(userMessage(List.of(Json.obj("type", "input_image", "image_url", "data:" + img.mediaType() + ";base64," + img.data()))), RESPONSE_CREATE);
            }
            case LiveClientEvent.ToolResult r -> {
                String output = Common.partsToText(r.content(), cx.provider(), "a Realtime function_call_output");
                return List.of(Json.obj("type", "conversation.item.create", "item", Json.obj("type", "function_call_output", "call_id", r.id(), "output", output)), RESPONSE_CREATE);
            }
        }
    }

    // ─── server frames → events ───

    @Override public List<LiveServerEvent> decode(byte[] frame) {
        List<LiveServerEvent> events = new ArrayList<>();
        JsonValue parsed;
        try {
            parsed = Json.parse(new String(frame, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return events;
        }
        if (!(parsed instanceof JsonObject payload)) return events;
        String et = Js.str(payload, "type");
        switch (et) {
            case "response.output_text.delta", "response.text.delta", "response.output_audio_transcript.delta", "response.audio_transcript.delta" -> {
                String delta = Js.strOrEmpty(payload.get("delta"), payload.get("text"));
                if (!delta.isEmpty()) events.add(new LiveServerEvent.Text(delta));
            }
            case "response.output_audio.delta" -> {
                String delta = Js.str(payload, "delta");
                if (!delta.isEmpty()) events.add(new LiveServerEvent.Audio(delta, null));
            }
            case "response.function_call_arguments.delta" -> {
                String delta = Js.str(payload, "delta");
                if (!delta.isEmpty()) {
                    events.add(new LiveServerEvent.ToolCallDelta(delta, Js.strOrNull(payload.get("call_id"), payload.get("id")), Js.strOrNull(payload.get("name"))));
                }
            }
            case "response.output_item.done" -> {
                // The ONLY tool-call emission point (function_call_arguments.done would double-fire).
                JsonObject item = Js.obj(payload, "item");
                if ("function_call".equals(item.optString("type"))) {
                    String callId = Js.strOrEmpty(item.get("call_id"), item.get("id"));
                    if (!callId.isEmpty()) {
                        String name = Js.truthy(item.get("name")) ? Js.pyStr(item.get("name")) : "tool";
                        events.add(new LiveServerEvent.ToolCall(callId, name, Common.parseJsonObject(item.get("arguments"))));
                    }
                }
            }
            case "response.done", "response.completed" -> {
                JsonObject response = Js.obj(payload, "response");
                JsonArray output = Js.arr(response, "output");
                Usage usage = Js.liveUsageFrom(response);
                boolean hasCall = false;
                for (JsonValue i : output) if (i instanceof JsonObject o && "function_call".equals(o.optString("type"))) { hasCall = true; break; }
                if ("cancelled".equals(Js.str(response, "status"))) {
                    // GA signals barge-in via response.done status=cancelled; its tokens ride a usage event first.
                    if (usage != null) events.add(new LiveServerEvent.UsageEvent(usage));
                    events.add(new LiveServerEvent.Interrupted());
                } else if (hasCall) {
                    // A response that requests tool calls does NOT end the turn; its tokens ride a usage event.
                    if (usage != null) events.add(new LiveServerEvent.UsageEvent(usage));
                } else {
                    events.add(new LiveServerEvent.TurnEnd(usage == null ? Usage.EMPTY : usage));
                }
            }
            case "response.cancelled", "response.canceled" -> events.add(new LiveServerEvent.Interrupted());
            case "error", "response.error" -> {
                ErrorDetail detail = OpenAIErrors.streamErrorDetail(payload);
                // Benign barge-in race: the response finished before the cancel arrived.
                if ("response_cancel_not_active".equals(detail.providerCode())) return events;
                events.add(new LiveServerEvent.Error(detail));
            }
            default -> { }
        }
        return events;
    }

    @Override public boolean isSetupComplete(byte[] frame) {
        try {
            JsonValue parsed = Json.parse(new String(frame, StandardCharsets.UTF_8));
            return parsed instanceof JsonObject o && ("session.updated".equals(o.optString("type")) || "session.created".equals(o.optString("type")));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
