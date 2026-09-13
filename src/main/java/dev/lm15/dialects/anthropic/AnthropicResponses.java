package dev.lm15.dialects.anthropic;

import dev.lm15.dialects.Common;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBool;
import dev.lm15.json.JsonFloat;
import dev.lm15.json.JsonInt;
import dev.lm15.json.JsonNull;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.serde.Canonical;
import dev.lm15.sse.SseEvent;
import dev.lm15.transport.HttpResponse;
import dev.lm15.types.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The response side of the Messages wire ({@code parse_response},
 * {@code parse_stream_events} of the reference): content blocks to parts
 * (MAP-1, MAP-2, MAP-7 rule 11), the finish-reason table, usage under
 * INV-029, and the PRE-coalesce per-frame stream events (MAP-3/4).
 */
final class AnthropicResponses {
    private AnthropicResponses() {}

    static final String CONTINUATION_PROVIDER = "anthropic";
    static final String KIND_THINKING_SIGNATURE = "thinking_signature";
    static final String KIND_REDACTED_THINKING = "redacted_thinking";

    /** Provider-executed builtin tool activity is not a part (MAP-1); the mechanics stay in provider_data. */
    static final Set<String> PROVIDER_EXECUTED_BLOCKS = Set.of("server_tool_use", "web_search_tool_result", "code_execution_tool_result");

    // ─── tables ───

    static FinishReason finishReason(String stopReason, boolean hasToolCall) {
        if (hasToolCall) return FinishReason.TOOL_CALL;
        String reason = stopReason == null ? "" : stopReason.toLowerCase();
        return switch (reason) {
            case "max_tokens", "model_context_window_exceeded" -> FinishReason.LENGTH;
            case "tool_use", "pause_turn" -> FinishReason.TOOL_CALL;
            case "refusal", "safety", "content_filter" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.STOP;
        };
    }

    // ─── small readers (the reference's `str(x) if x else None` idiom) ───

    /** A truthy wire value as text: a non-empty string verbatim, a number by its lexeme, else null. */
    static String truthyString(JsonValue v) {
        if (v == null || v instanceof JsonNull) return null;
        if (v instanceof JsonString s) return s.value().isEmpty() ? null : s.value();
        if (v instanceof JsonBool b) return b.value() ? "True" : null;
        if (v instanceof JsonInt i) return i.value().signum() == 0 ? null : i.value().toString();
        if (v instanceof JsonFloat f) return f.value() == 0.0 ? null : f.toString();
        if (v instanceof JsonArray a) return a.isEmpty() ? null : a.toJson();
        if (v instanceof JsonObject o) return o.isEmpty() ? null : o.toJson();
        return v.toJson();
    }

    static String truthyString(JsonObject o, String key) { return truthyString(o.get(key)); }

    /** {@code str(x or "")}: the text of a string, an empty string for absent/null/falsy. */
    static String textOr(JsonValue v) {
        String s = truthyString(v);
        return s == null ? "" : s;
    }

    static JsonObject objectOr(JsonValue v) { return v instanceof JsonObject o ? o : JsonObject.EMPTY; }

    /** Python {@code json.dumps(x, separators=(",", ":"))}: compact, non-ASCII as {@code \\uXXXX} (structural JSON is ASCII, so a post-pass suffices). */
    static String compactAscii(JsonValue v) {
        String compact = Json.write(v);
        StringBuilder sb = new StringBuilder(compact.length());
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (c >= 0x80) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    static int index(JsonObject payload) {
        JsonValue v = payload.opt("index");
        if (v == null) return 0;
        try {
            return v.asInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static Integer counter(JsonObject usage, String key) {
        JsonValue v = usage.opt(key);
        return v == null ? null : Canonical.toInt(v, key);
    }

    private static Integer reasoningTokens(JsonObject usage) {
        // The wire nests thinking spend under output_tokens_details; absent
        // when thinking never ran, so null stays the honest "not reported".
        JsonValue details = usage.opt("output_tokens_details");
        if (details instanceof JsonObject d && d.opt("thinking_tokens") != null) return Canonical.toInt(d.opt("thinking_tokens"), "thinking_tokens");
        return null;
    }

    static Usage usage(JsonObject usage) {
        return new Usage(counter(usage, "input_tokens"), counter(usage, "output_tokens"), null,
            counter(usage, "cache_read_input_tokens"), counter(usage, "cache_creation_input_tokens"), reasoningTokens(usage), null, null);
    }

    static CitationPart citation(JsonObject citation) {
        String url = truthyString(citation, "url");
        if (url == null) url = truthyString(citation, "uri");
        String title = truthyString(citation, "title");
        if (title == null) title = truthyString(citation, "document_title");
        if (title == null) title = truthyString(citation, "source_title");
        String text = truthyString(citation, "cited_text");
        if (text == null) text = truthyString(citation, "text");
        if (text == null) text = truthyString(citation, "quote");
        if (url == null && title == null && text == null) return null;
        return new CitationPart(url, title, text);
    }

    /** The Python type name of a non-object block, for the unmapped recorder. */
    private static String pythonTypeName(JsonValue v) {
        if (v instanceof JsonString) return "str";
        if (v instanceof JsonInt) return "int";
        if (v instanceof JsonFloat) return "float";
        if (v instanceof JsonBool) return "bool";
        if (v instanceof JsonArray) return "list";
        if (v instanceof JsonNull) return "NoneType";
        return v.typeName();
    }

    // ─── complete bodies ───

    static Response parseResponse(String provider, Request request, HttpResponse response) {
        JsonObject data = response.json().asObject();
        List<Part> parts = new ArrayList<>();
        List<JsonValue> unmapped = new ArrayList<>();
        JsonValue content = data.opt("content");
        if (content instanceof JsonArray blocks) {
            int blockIndex = -1;
            for (JsonValue raw : blocks) {
                blockIndex++;
                String path = "content[" + blockIndex + "]";
                if (!(raw instanceof JsonObject block)) {
                    unmapped.add(Json.obj("path", path, "type", pythonTypeName(raw)));
                    continue;
                }
                String type = block.opt("type") instanceof JsonString s ? s.value() : null;
                if ("text".equals(type)) {
                    parts.add(new TextPart(textOr(block.opt("text"))));
                    if (block.opt("citations") instanceof JsonArray citations) {
                        for (JsonValue c : citations) {
                            if (!(c instanceof JsonObject co)) continue;
                            CitationPart cite = citation(co);
                            if (cite != null) parts.add(cite);
                        }
                    }
                } else if ("tool_use".equals(type)) {
                    String name = truthyString(block, "name");
                    if (name == null) throw Common.unnamedToolCallError(provider, path);
                    String id = truthyString(block, "id");
                    JsonValue input = block.opt("input");
                    parts.add(new ToolCallPart(id != null ? id : "tool_" + parts.size(), name, input instanceof JsonObject o ? o : JsonObject.EMPTY));
                } else if ("thinking".equals(type)) {
                    List<ContinuationState> continuation = List.of();
                    String signature = truthyString(block, "signature");
                    if (signature != null) {
                        continuation = List.of(new ContinuationState(CONTINUATION_PROVIDER, KIND_THINKING_SIGNATURE, Json.obj("signature", signature)));
                    }
                    String text = truthyString(block, "thinking");
                    if (text == null) text = truthyString(block, "text");
                    parts.add(new ThinkingPart(text == null ? "" : text, continuation));
                } else if ("redacted_thinking".equals(type)) {
                    // MAP-7 rule 11: hidden thinking is empty text plus replay state;
                    // the blob goes back verbatim as redacted_thinking.
                    List<ContinuationState> continuation = List.of();
                    JsonValue blob = block.opt("data");
                    if (blob != null) {
                        continuation = List.of(new ContinuationState(CONTINUATION_PROVIDER, KIND_REDACTED_THINKING, Json.obj("data", blob)));
                    }
                    parts.add(new ThinkingPart("", continuation));
                } else if (type != null && PROVIDER_EXECUTED_BLOCKS.contains(type)) {
                    continue;
                } else {
                    Common.recordUnmapped(unmapped, path, block.get("type"));
                }
            }
        }
        if (parts.isEmpty()) parts.add(new TextPart(""));

        // INV-029: absent counters stay null; Usage sums the total itself only
        // when both primaries are present.
        Usage usage = usage(objectOr(data.opt("usage")));
        boolean hasTool = false;
        for (Part p : parts) if (p instanceof ToolCallPart) hasTool = true;
        String model = truthyString(data, "model");
        JsonObject providerData = unmapped.isEmpty() ? data : data.with("_lm15_unmapped", new JsonArray(unmapped));
        // D8 (2026-09-06): Response.id carries the message id; no message-level
        // continuation state is minted for it.
        return new Response(truthyString(data, "id"), model != null ? model : request.model(),
            new Message(Role.ASSISTANT, parts), finishReason(truthyString(data, "stop_reason"), hasTool), usage, null, providerData);
    }

    // ─── SSE frames (PRE-coalesce) ───

    static List<StreamEvent> parseStreamEvent(String provider, Request request, SseEvent raw) {
        List<StreamEvent> out = new ArrayList<>();
        if (raw.data() == null || raw.data().isEmpty()) return out;
        JsonValue parsed = Json.parse(raw.data());
        if (!(parsed instanceof JsonObject payload)) return out;
        String et = payload.optString("type");
        if (et == null) return out;
        switch (et) {
            case "message_start" -> {
                JsonObject msg = objectOr(payload.opt("message"));
                String model = truthyString(msg, "model");
                out.add(new StreamStartEvent(truthyString(msg, "id"), model != null ? model : request.model()));
            }
            case "content_block_start" -> {
                JsonObject block = objectOr(payload.opt("content_block"));
                String type = block.optString("type");
                if ("tool_use".equals(type)) {
                    // A streamed tool_use block opens with input: {} and the
                    // arguments arrive as input_json_delta fragments; the empty
                    // object is a placeholder, not a fragment. A non-empty input on
                    // the start frame is kept verbatim.
                    JsonValue startInput = block.opt("input");
                    String input;
                    if (startInput instanceof JsonObject o) input = o.isEmpty() ? "" : compactAscii(o);
                    else input = textOr(startInput);
                    out.add(new StreamDeltaEvent(new ToolCallDelta(input, index(payload), truthyString(block, "id"), truthyString(block, "name"))));
                } else if ("redacted_thinking".equals(type) && block.opt("data") != null) {
                    // MAP-7 rule 11: an empty thinking delta opens the hidden block;
                    // its replay state is the block's only content, emitted here
                    // (the adapter is stateless per frame).
                    int idx = index(payload);
                    out.add(new StreamDeltaEvent(new ThinkingDelta("", idx)));
                    out.add(new StreamDeltaEvent(new ContinuationDelta(CONTINUATION_PROVIDER, KIND_REDACTED_THINKING, Json.obj("data", block.opt("data")), idx)));
                }
            }
            case "content_block_delta" -> {
                JsonObject delta = objectOr(payload.opt("delta"));
                int idx = index(payload);
                String dtype = delta.optString("type");
                if (dtype == null) return out;
                switch (dtype) {
                    case "text_delta" -> out.add(new StreamDeltaEvent(new TextDelta(textOr(delta.opt("text")), idx)));
                    case "input_json_delta" -> out.add(new StreamDeltaEvent(new ToolCallDelta(textOr(delta.opt("partial_json")), idx)));
                    case "thinking_delta" -> out.add(new StreamDeltaEvent(new ThinkingDelta(textOr(delta.opt("thinking")), idx)));
                    case "signature_delta" -> {
                        String signature = truthyString(delta, "signature");
                        if (signature != null) {
                            out.add(new StreamDeltaEvent(new ContinuationDelta(CONTINUATION_PROVIDER, KIND_THINKING_SIGNATURE, Json.obj("signature", signature), idx)));
                        }
                    }
                    case "citation_delta", "citations_delta" -> {
                        JsonObject citation = delta.opt("citation") instanceof JsonObject c ? c : delta;
                        String text = truthyString(citation, "cited_text");
                        if (text == null) text = truthyString(citation, "text");
                        out.add(new StreamDeltaEvent(new CitationDelta(text, truthyString(citation, "url"), truthyString(citation, "title"), idx)));
                    }
                    default -> { }
                }
            }
            case "message_delta" -> {
                // Anthropic sends the authoritative stop_reason and final usage here;
                // message_stop is just the terminator and carries neither.
                JsonObject delta = objectOr(payload.opt("delta"));
                JsonObject usagePayload = objectOr(payload.opt("usage"));
                Usage usage = usagePayload.isEmpty() ? null : usage(usagePayload);
                JsonValue stopReason = delta.opt("stop_reason");
                if (stopReason != null || usage != null) {
                    // MAP-3 (D9): this frame supplied usage (and the stop reason), so
                    // it is the end event's provider_data, verbatim.
                    out.add(new StreamEndEvent(stopReason != null ? finishReason(textOr(stopReason), false) : null, usage, payload));
                }
            }
            case "message_stop" -> out.add(new StreamEndEvent(null, null, null));
            case "error" -> {
                JsonValue err = payload.opt("error");
                String providerCode;
                String message;
                if (err instanceof JsonObject e) {
                    providerCode = truthyString(e, "type");
                    if (providerCode == null) providerCode = truthyString(e, "code");
                    if (providerCode == null) providerCode = truthyString(payload, "code");
                    message = truthyString(e, "message");
                    if (message == null) message = truthyString(payload, "message");
                } else {
                    providerCode = truthyString(payload, "code");
                    if (providerCode == null) providerCode = truthyString(payload, "error_type");
                    message = truthyString(payload, "message");
                }
                out.add(new StreamErrorEvent(AnthropicErrors.errorDetail(providerCode == null ? "provider" : providerCode, message == null ? "" : message)));
            }
            default -> { }
        }
        return out;
    }
}
