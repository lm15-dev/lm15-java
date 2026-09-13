package dev.lm15.types;

import dev.lm15.json.Json;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;

import java.util.List;

/**
 * The composed artifact returned by a foundation model (spec/types.md
 * § Response): an assistant message (never empty, MAP-2), a finish reason,
 * usage, optional logprobs and the verbatim provider payload.
 */
public record Response(String id, String model, Message message, FinishReason finishReason, Usage usage,
                       List<TokenLogprob> logprobs, JsonObject providerData) {
    public Response {
        Check.optNonEmpty(id, "Response.id");
        Check.nonEmpty(model, "Response.model");
        if (message == null) throw ValidationException.type("Response.message must be a Message");
        if (message.role() != Role.ASSISTANT) throw ValidationException.value("Response.message must have role 'assistant'");
        Check.required(finishReason, "finish reason");
        if (usage == null) usage = Usage.EMPTY;
        if (logprobs != null) logprobs = Check.list(logprobs, "Response.logprobs");
        Check.jsonObject(providerData, "provider_data", false);
    }

    /** Concatenated assistant text; citation and thinking parts are metadata around it. Null when a part is something else. */
    public String text() {
        String t = message.text();
        if (t != null) return t;
        boolean any = false;
        for (Part p : message.parts()) {
            if (!(p instanceof TextPart || p instanceof CitationPart || p instanceof ThinkingPart)) return null;
            if (p instanceof TextPart) any = true;
        }
        return any ? Parts.textOf(message.parts()) : null;
    }

    public List<ToolCallPart> toolCalls() { return message.partsOf(ToolCallPart.class); }

    public List<CitationPart> citations() { return message.partsOf(CitationPart.class); }

    /** The response text parsed as JSON; a {@link ValidationException} when it is not pure text or not JSON. */
    public JsonValue parseJson() {
        String t = text();
        if (t == null) throw ValidationException.value("Cannot parse response as JSON: response is not pure text");
        try {
            return Json.parse(t.strip());
        } catch (RuntimeException e) {
            throw ValidationException.value("Cannot parse response as JSON: " + e.getMessage());
        }
    }

    /** Parsed JSON text, or null when parsing fails. */
    public JsonValue json() {
        try {
            return parseJson();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Response withProviderData(JsonObject pd) { return new Response(id, model, message, finishReason, usage, logprobs, pd); }
    public Response withModel(String m) { return new Response(id, m, message, finishReason, usage, logprobs, providerData); }
}
