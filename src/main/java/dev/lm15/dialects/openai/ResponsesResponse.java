package dev.lm15.dialects.openai;

import dev.lm15.dialects.Common;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.sse.SseEvent;
import dev.lm15.types.*;
import dev.lm15.wire.BuildContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The Responses body and SSE frames as canonical values (the reference's {@code parse_response} / {@code parse_stream_events}). */
final class ResponsesResponse {
    private ResponsesResponse() {}

    /** Provider-executed builtin tool items are not parts (MAP-1); they stay in provider_data. */
    static final Set<String> PROVIDER_EXECUTED_ITEMS = Set.of("web_search_call", "file_search_call", "code_interpreter_call", "computer_call", "computer_use_call");

    // ─── citations ───

    private static String annotationText(JsonObject annotation, String sourceText) {
        for (String key : new String[] {"text", "snippet", "cited_text", "quote"}) {
            String text = Js.strOrNull(annotation.get(key));
            if (text != null) return text;
        }
        Integer start = Js.intOrNull(annotation.get("start_index"));
        Integer end = Js.intOrNull(annotation.get("end_index"));
        if (sourceText != null && start != null && end != null && 0 <= start && start < end && end <= sourceText.length()) {
            return sourceText.substring(start, end);
        }
        return null;
    }

    static CitationPart citationFromAnnotation(JsonObject annotation, String sourceText) {
        String url = Js.strOrNull(annotation.get("url"), annotation.get("uri"));
        String title = Js.strOrNull(annotation.get("title"));
        if (title == null) title = Js.strOrNull(annotation.get("filename"));
        if (title == null) title = Js.strOrNull(annotation.get("file_id"));
        String text = annotationText(annotation, sourceText);
        if (url == null && title == null && text == null) return null;
        return new CitationPart(url, title, text);
    }

    static CitationDelta citationDeltaFromAnnotation(JsonObject annotation, int partIndex) {
        CitationPart c = citationFromAnnotation(annotation, null);
        if (c == null) return null;
        return new CitationDelta(c.text(), c.url(), c.title(), partIndex);
    }

    // ─── finish reason ───

    static FinishReason finishFromStatus(JsonObject data, boolean hasToolCall) {
        if (hasToolCall) return FinishReason.TOOL_CALL;
        String status = Js.str(data, "status").toLowerCase();
        JsonValue incomplete = data.get("incomplete_details");
        String reason = incomplete instanceof JsonObject inc ? Js.str(inc, "reason").toLowerCase() : "";
        if (status.equals("incomplete") && reason.contains("token")) return FinishReason.LENGTH;
        if (reason.contains("content_filter") || reason.contains("safety")) return FinishReason.CONTENT_FILTER;
        return FinishReason.STOP;
    }

    // ─── the complete path ───

    static Response parse(Request request, BuildContext cx, JsonObject data) {
        String provider = cx.provider();
        JsonValue respError = data.get("error");
        if (respError instanceof JsonObject err) {
            String message = Js.truthy(err.get("message")) ? Js.pyStr(err.get("message")) : err.toJson();
            throw OpenAIErrors.responseError(provider, Js.str(err, "code"), message);
        }
        List<Part> parts = new ArrayList<>();
        List<JsonValue> unmapped = new ArrayList<>();
        List<TokenLogprob> logprobs = new ArrayList<>();
        JsonArray output = Js.arr(data, "output");
        for (int itemIndex = 0; itemIndex < output.size(); itemIndex++) {
            JsonValue itemValue = output.get(itemIndex);
            if (!(itemValue instanceof JsonObject item)) {
                Common.recordUnmapped(unmapped, "output[" + itemIndex + "]", Json.of(Js.typeName(itemValue)));
                continue;
            }
            String itemType = item.optString("type");
            if ("message".equals(itemType)) {
                JsonArray content = Js.arr(item, "content");
                for (int ci = 0; ci < content.size(); ci++) {
                    JsonValue cv = content.get(ci);
                    String path = "output[" + itemIndex + "].content[" + ci + "]";
                    if (!(cv instanceof JsonObject c)) {
                        Common.recordUnmapped(unmapped, path, Json.of(Js.typeName(cv)));
                        continue;
                    }
                    String ctype = c.optString("type");
                    if ("output_text".equals(ctype) || "text".equals(ctype)) {
                        String text = Js.str(c, "text");
                        parts.add(new TextPart(text));
                        logprobs.addAll(Common.openaiTokenLogprobs(c.get("logprobs")));
                        for (JsonValue av : Js.arr(c, "annotations")) {
                            if (!(av instanceof JsonObject annotation)) continue;
                            CitationPart citation = citationFromAnnotation(annotation, text);
                            if (citation != null) parts.add(citation);
                        }
                    } else if ("refusal".equals(ctype)) {
                        String text = Js.strOrEmpty(c.get("refusal"), c.get("text"));
                        parts.add(text.isEmpty() ? new TextPart("") : new RefusalPart(text));
                    } else if ("output_image".equals(ctype)) {
                        String b64 = Js.strOrEmpty(c.get("b64_json"), c.get("image_base64"));
                        if (!b64.isEmpty()) parts.add(new ImagePart("image/png", b64, null, null, null, null, List.of()));
                    } else if ("output_audio".equals(ctype)) {
                        JsonObject audio = Js.obj(c, "audio");
                        String b64 = Js.strOrEmpty(audio.get("data"), c.get("b64_json"));
                        if (!b64.isEmpty()) parts.add(new AudioPart("audio/wav", b64, null, null, null, List.of()));
                    } else {
                        Common.recordUnmapped(unmapped, path, c.get("type"));
                    }
                }
            } else if ("function_call".equals(itemType)) {
                if (!Js.truthy(item.get("name"))) throw Common.unnamedToolCallError(provider, "output[" + itemIndex + "]");
                String id = Js.strOrEmpty(item.get("call_id"), item.get("id"));
                if (id.isEmpty()) id = "call_" + parts.size();
                parts.add(new ToolCallPart(id, Js.pyStr(item.get("name")), Common.parseJsonObject(item.get("arguments"))));
            } else if ("reasoning".equals(itemType)) {
                // MAP-7 rule 8/9: the reasoning item is replay state; its summary is the visible text.
                JsonValue summary = item.get("summary");
                String text;
                if (summary instanceof JsonArray arr) {
                    List<String> bits = new ArrayList<>();
                    for (JsonValue x : arr) bits.add(x instanceof JsonObject xo ? Js.pyStr(xo.get("text")) : Js.pyStr(x));
                    text = String.join("\n", bits);
                } else {
                    text = Js.strOrEmpty(summary, item.get("text"));
                }
                List<ContinuationState> continuation = reasoningState(item);
                if (!text.isEmpty() || !continuation.isEmpty()) parts.add(new ThinkingPart(text, continuation));
            } else if (itemType != null && PROVIDER_EXECUTED_ITEMS.contains(itemType)) {
                continue;
            } else {
                Common.recordUnmapped(unmapped, "output[" + itemIndex + "]", item.get("type"));
            }
        }
        if (parts.isEmpty()) parts.add(new TextPart(Js.str(data, "output_text")));

        Usage usage = Js.usageFrom(Js.obj(data, "usage"));
        boolean hasTool = parts.stream().anyMatch(p -> p instanceof ToolCallPart);
        JsonObject providerData = unmapped.isEmpty() ? data : data.with("_lm15_unmapped", new JsonArray(unmapped));
        String id = Js.truthy(data.get("id")) ? Js.pyStr(data.get("id")) : null;
        String model = Js.truthy(data.get("model")) ? Js.pyStr(data.get("model")) : request.model();
        return new Response(id, model, new Message(Role.ASSISTANT, parts), finishFromStatus(data, hasTool), usage,
            logprobs.isEmpty() ? null : logprobs, providerData);
    }

    /** The {@code openai:reasoning_item} state of a reasoning item (id + encrypted_content), empty when the item carries neither. */
    static List<ContinuationState> reasoningState(JsonObject item) {
        JsonObject state = reasoningStateData(item);
        return state.isEmpty() ? List.of() : List.of(new ContinuationState("openai", "reasoning_item", state));
    }

    static JsonObject reasoningStateData(JsonObject item) {
        dev.lm15.json.JsonBuilder state = new dev.lm15.json.JsonBuilder();
        if (Js.truthy(item.get("id"))) state.put("id", Js.pyStr(item.get("id")));
        if (Js.truthy(item.get("encrypted_content"))) state.put("encrypted_content", Js.pyStr(item.get("encrypted_content")));
        return state.build();
    }

    // ─── the stream path (PRE-coalesce events per frame) ───

    static List<StreamEvent> streamEvents(Request request, BuildContext cx, SseEvent raw) {
        List<StreamEvent> out = new ArrayList<>(1);
        String data = raw.data();
        if (data == null || data.isEmpty()) return out;
        if (data.equals("[DONE]")) {
            // A bare terminator: no finish reason, no usage (MAP-3).
            out.add(new StreamEndEvent(null, null, null));
            return out;
        }
        JsonValue parsed = Json.parse(data);
        if (!(parsed instanceof JsonObject payload)) return out;
        String et = Js.str(payload, "type");

        if (et.equals("response.output_item.added") || et.equals("response.output_item.done")) {
            JsonValue iv = payload.get("item");
            if (iv instanceof JsonObject item && "reasoning".equals(item.optString("type"))) {
                int index = Js.outputIndex(payload);
                if (et.equals("response.output_item.added")) {
                    // MAP-7.9: even an item with no visible summary is thinking.
                    out.add(new StreamDeltaEvent(new ThinkingDelta("", index)));
                } else {
                    JsonObject state = reasoningStateData(item);
                    if (!state.isEmpty()) out.add(new StreamDeltaEvent(new ContinuationDelta("openai", "reasoning_item", state, index)));
                }
                return out;
            }
        }
        StreamEvent event = singleStreamEvent(request, cx, payload, et);
        if (event != null) out.add(event);
        return out;
    }

    private static StreamEvent singleStreamEvent(Request request, BuildContext cx, JsonObject payload, String et) {
        switch (et) {
            case "response.created" -> {
                JsonObject response = Js.obj(payload, "response");
                String id = Js.truthy(response.get("id")) ? Js.pyStr(response.get("id")) : null;
                String model = Js.truthy(response.get("model")) ? Js.pyStr(response.get("model")) : request.model();
                return new StreamStartEvent(id, model);
            }
            case "response.output_text.delta", "response.refusal.delta" -> {
                return new StreamDeltaEvent(new TextDelta(Js.str(payload, "delta"), Js.outputIndex(payload), Common.openaiTokenLogprobs(payload.get("logprobs"))));
            }
            case "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                return new StreamDeltaEvent(new ThinkingDelta(Js.str(payload, "delta"), Js.outputIndex(payload)));
            }
            case "response.output_text.annotation.added" -> {
                JsonValue av = payload.get("annotation");
                if (av instanceof JsonObject annotation) {
                    CitationDelta delta = citationDeltaFromAnnotation(annotation, Js.outputIndex(payload));
                    if (delta != null) return new StreamDeltaEvent(delta);
                }
                return null;
            }
            case "response.output_audio.delta" -> {
                return new StreamDeltaEvent(AudioDelta.ofData(Js.str(payload, "delta"), Js.outputIndex(payload), "audio/wav"));
            }
            case "response.output_image.delta", "response.image.delta" -> {
                return new StreamDeltaEvent(ImageDelta.ofData(Js.str(payload, "delta"), Js.outputIndex(payload), "image/png"));
            }
            case "response.output_item.added" -> {
                JsonObject item = Js.obj(payload, "item");
                if ("function_call".equals(item.optString("type"))) {
                    return new StreamDeltaEvent(new ToolCallDelta(Js.str(item, "arguments"), Js.outputIndex(payload),
                        Js.strOrNull(item.get("call_id"), item.get("id")), Js.strOrNull(item.get("name"))));
                }
                return null;
            }
            case "response.function_call_arguments.delta" -> {
                return new StreamDeltaEvent(new ToolCallDelta(Js.str(payload, "delta"), Js.outputIndex(payload),
                    Js.strOrNull(payload.get("call_id"), payload.get("id")), Js.strOrNull(payload.get("name"))));
            }
            case "response.completed" -> {
                JsonValue rv = payload.get("response");
                JsonObject response = rv instanceof JsonObject r ? r : JsonObject.EMPTY;
                Usage usage = Js.usageFrom(Js.obj(response, "usage"));
                boolean hasTool = false;
                for (JsonValue item : Js.arr(response, "output")) {
                    if (item instanceof JsonObject o && "function_call".equals(o.optString("type"))) { hasTool = true; break; }
                }
                return new StreamEndEvent(hasTool ? FinishReason.TOOL_CALL : FinishReason.STOP, usage, rv instanceof JsonObject r ? r : null);
            }
            case "response.error", "error" -> {
                return new StreamErrorEvent(OpenAIErrors.streamErrorDetail(payload));
            }
            default -> { return null; }
        }
    }
}
