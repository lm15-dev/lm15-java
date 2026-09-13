package dev.lm15.dialects.gemini;

import dev.lm15.dialects.Common;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonFloat;
import dev.lm15.json.JsonInt;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.types.*;
import dev.lm15.wire.Wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The request side of the Gemini wire (the reference's {@code _part},
 * {@code _message}, {@code _function_response}, {@code _payload}): canonical
 * parts → {@code contents[]}, tools → {@code functionDeclarations}, the
 * MAP-6/7/8/10 refusals, and the generateContent payload.
 */
final class GeminiContents {
    private GeminiContents() {}

    /** Canonical builtin tool name → Gemini tool key. */
    static final Map<String, String> BUILTIN_MAP = Map.of(
        "web_search", "googleSearch",
        "code_execution", "codeExecution");

    /** Request extension keys the payload never passes through verbatim. */
    private static final Set<String> RESERVED_EXTENSIONS = Set.of("prompt_caching", "output");

    /**
     * True for the Gemini 3.x class: {@code thinkingLevel}, no full off (MAP-7
     * rule 10). Model-name table, receipted 2026-09-02; a table that rots.
     */
    static boolean levelClass(String model) {
        String lowered = model.toLowerCase();
        return lowered.startsWith("models/gemini-3") || lowered.startsWith("gemini-3");
    }

    /** {@code models/<model>}, the model percent-encoded with {@code /:@} kept. */
    static String modelPath(String model) {
        String m = Wire.percentEncode(model, "/:@");
        return m.startsWith("models/") ? m : "models/" + m;
    }

    static String cacheResource(String cacheId) {
        return cacheId.startsWith("cachedContents/") ? cacheId : "cachedContents/" + cacheId;
    }

    /** Gemini wire dialect: integral float knobs travel in integer form (proto3-JSON). */
    static JsonValue number(double value) {
        if (Double.isFinite(value) && value == Math.rint(value) && Math.abs(value) < 9.0e15) return JsonInt.of((long) value);
        return new JsonFloat(value);
    }

    private static UnsupportedFeatureError unsupported(String provider, String message) {
        return new UnsupportedFeatureError(message, ErrorMeta.of(provider));
    }

    // ─── parts ───

    /** The part's {@code gemini:thought_signature} value, or null when absent or falsy. */
    private static JsonValue thoughtSignature(Part part) {
        JsonObject data = ContinuationState.find(part.continuation(), "gemini", "thought_signature");
        if (data == null) return null;
        JsonValue value = data.get("value");
        return GeminiJson.truthy(value) ? value : null;
    }

    static JsonObject part(Part part, Map<String, String> names, String provider) {
        switch (part) {
            case TextPart t -> {
                JsonBuilder out = new JsonBuilder().put("text", t.text());
                JsonValue sig = thoughtSignature(t);
                if (sig != null) out.put("thoughtSignature", sig);
                return out.build();
            }
            case MediaPart m -> { return mediaPart(m); }
            case ToolCallPart tc -> {
                JsonBuilder fc = new JsonBuilder().put("name", tc.name()).put("args", tc.input());
                if (!tc.id().isEmpty()) fc.put("id", tc.id());
                JsonBuilder out = new JsonBuilder().put("functionCall", fc.build());
                JsonValue sig = thoughtSignature(tc);
                if (sig != null) out.put("thoughtSignature", sig);
                return out.build();
            }
            case ToolResultPart tr -> { return functionResponse(tr, names, provider); }
            case ThinkingPart th -> {
                JsonBuilder out = new JsonBuilder().put("text", th.text());
                JsonValue sig = thoughtSignature(th);
                if (sig != null) {
                    out.put("thought", true);
                    out.put("thoughtSignature", sig);
                }
                return out.build();
            }
            case RefusalPart r -> { return Json.obj("text", r.text() == null ? "" : r.text()); }
            case CitationPart c -> { return Json.obj("text", c.text() == null ? "" : c.text()); }
        }
    }

    static JsonObject part(Part part, String provider) { return part(part, null, provider); }

    private static JsonObject mediaPart(MediaPart part) {
        String mime = part.mediaType() == null || part.mediaType().isEmpty() ? "application/octet-stream" : part.mediaType();
        if (part.url() != null) return Json.obj("fileData", Json.obj("mimeType", mime, "fileUri", part.url()));
        if (part.fileId() != null) return Json.obj("fileData", Json.obj("mimeType", mime, "fileUri", part.fileId()));
        if (part.data() != null) return Json.obj("inlineData", Json.obj("mimeType", mime, "data", part.data()));
        return Json.obj("inlineData", Json.obj("mimeType", mime, "data", MediaPart.Media.encode(part.bytes())));
    }

    /**
     * {@code functionResponse} (MAP-10 on this wire): text in
     * {@code response.result} ({@code response.error} when is_error), media
     * nested under {@code parts[]} in the caller's order; the name resolved
     * from the transcript's matching functionCall when the caller gave none
     * (rule 6). Every model takes the shape; Gemini 2.5 answers HTTP 400 itself.
     */
    private static JsonObject functionResponse(ToolResultPart part, Map<String, String> names, String provider) {
        List<Part> textParts = new ArrayList<>();
        List<Part> mediaParts = new ArrayList<>();
        for (Part p : part.content()) {
            if (Common.MEDIA_KINDS.contains(p.type())) mediaParts.add(p);
            else textParts.add(p);
        }
        for (Part p : mediaParts) {
            if (p.type() != PartType.IMAGE && p.type() != PartType.DOCUMENT) {
                throw unsupported(provider, provider + ": a " + p.type().wire() + " part in tool_result '" + part.id() + "' cannot reach a functionResponse — "
                    + "multimodal function responses take images (png/jpeg/webp) and documents (pdf, text/plain) only (MAP-10)");
            }
        }
        String name = part.name();
        if ((name == null || name.isEmpty()) && names != null) name = names.get(part.id());
        if (name == null || name.isEmpty()) {
            throw unsupported(provider, provider + ": tool_result '" + part.id() + "' needs a function name on the Gemini wire and no preceding "
                + "assistant tool_call with that id is in the transcript; set ToolResultPart.name (MAP-10 rule 6)");
        }
        String text = Common.partsToText(textParts, provider, "functionResponse.response");
        JsonObject response;
        if (part.isError()) response = Json.obj("error", text);
        else if (!mediaParts.isEmpty() && textParts.isEmpty()) response = JsonObject.EMPTY; // the media IS the result; no fabricated text
        else response = Json.obj("result", text);
        JsonBuilder fr = new JsonBuilder().put("name", name).put("response", response);
        if (!part.id().isEmpty()) fr.put("id", part.id());
        if (!mediaParts.isEmpty()) {
            List<JsonValue> media = new ArrayList<>();
            for (Part p : mediaParts) media.add(part(p, provider));
            fr.put("parts", new JsonArray(media));
        }
        return Json.obj("functionResponse", fr.build());
    }

    static JsonObject message(Message msg, Map<String, String> names, String provider) {
        if (msg.role() == Role.DEVELOPER) {
            String text = "[developer]\n" + Common.partsToText(msg.parts(), provider, "a developer turn");
            return Json.obj("role", "user", "parts", Json.arr(Json.obj("text", text)));
        }
        String role = msg.role() == Role.ASSISTANT ? "model" : "user";
        List<JsonValue> parts = new ArrayList<>();
        for (Part p : msg.parts()) parts.add(part(p, names, provider));
        return Json.obj("role", role, "parts", new JsonArray(parts));
    }

    /** tool_call id → function name, over the transcript (MAP-10 rule 6). */
    static Map<String, String> callNames(List<Message> messages) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Message m : messages) {
            for (Part p : m.parts()) {
                if (p instanceof ToolCallPart tc && !tc.id().isEmpty()) names.put(tc.id(), tc.name());
            }
        }
        return names;
    }

    static JsonArray contents(List<Message> messages, Map<String, String> names, String provider) {
        List<JsonValue> out = new ArrayList<>();
        for (Message m : messages) out.add(message(m, names, provider));
        return new JsonArray(out);
    }

    // ─── system, tools, tool config ───

    static JsonObject systemInstruction(SystemPrompt system) {
        String text = system.text() != null ? system.text() : Common.partsToText(system.parts());
        return Json.obj("parts", Json.arr(Json.obj("text", text)));
    }

    static List<JsonValue> functionDeclarations(List<Tool> tools) {
        List<JsonValue> out = new ArrayList<>();
        for (Tool t : tools) {
            if (t instanceof FunctionTool f) {
                out.add(new JsonBuilder().put("name", f.name()).put("description", f.description()).put("parameters", f.parameters()).build());
            }
        }
        return out;
    }

    static JsonObject builtinTool(BuiltinTool tool) {
        return Json.obj(BUILTIN_MAP.getOrDefault(tool.name(), tool.name()), tool.config() == null ? JsonObject.EMPTY : tool.config());
    }

    /** {@code tools[]}: one functionDeclarations entry, then one entry per builtin. */
    static JsonArray toolsWire(List<Tool> tools) {
        List<JsonValue> out = new ArrayList<>();
        List<JsonValue> declarations = functionDeclarations(tools);
        if (!declarations.isEmpty()) out.add(Json.obj("functionDeclarations", new JsonArray(declarations)));
        for (Tool t : tools) if (t instanceof BuiltinTool b) out.add(builtinTool(b));
        return new JsonArray(out);
    }

    static JsonObject toolConfig(Request request, String provider) {
        ToolChoice tc = request.config().toolChoice();
        if (tc == null) return null;
        if (Boolean.FALSE.equals(tc.parallel())) {
            // MAP-8 rule 2 (live 2026-09-02): no wire knob; two calls came back regardless.
            throw unsupported(provider, "gemini: tool_choice.parallel=False is not supported — GenerateContent has no "
                + "parallel-tool-calls knob and returns several calls regardless (OpenAI and Anthropic carry it)");
        }
        String mode = switch (tc.mode()) {
            case NONE -> "NONE";
            case REQUIRED -> "ANY";
            case AUTO -> "AUTO";
        };
        JsonBuilder cfg = new JsonBuilder().put("mode", mode);
        if (!tc.allowed().isEmpty()) {
            List<String> builtins = new ArrayList<>();
            for (String name : tc.allowed()) {
                for (Tool t : request.tools()) if (t.name().equals(name) && t instanceof BuiltinTool) builtins.add(name);
            }
            if (!builtins.isEmpty()) {
                throw unsupported(provider, "gemini: cannot force builtin tools " + builtins + " — functionCallingConfig addresses function "
                    + "declarations only; googleSearch/codeExecution have no tool_choice form (OpenAI Responses and Anthropic carry builtin forcing)");
            }
            cfg.put("allowedFunctionNames", tc.allowed());
            // allowedFunctionNames is only legal with ANY or VALIDATED; VALIDATED = mode=auto + allowed.
            if (tc.mode() == ToolChoiceMode.AUTO) cfg.put("mode", "VALIDATED");
        }
        return Json.obj("functionCallingConfig", cfg.build());
    }

    // ─── response format (MAP-8) ───

    private static boolean containsKey(JsonValue value, String key) {
        if (value instanceof JsonObject o) {
            if (o.has(key)) return true;
            for (JsonValue v : o.members().values()) if (containsKey(v, key)) return true;
            return false;
        }
        if (value instanceof JsonArray a) {
            for (JsonValue v : a) if (containsKey(v, key)) return true;
        }
        return false;
    }

    /** {@code responseSchema} is the OpenAPI subset; {@code responseJsonSchema} takes JSON Schema keywords. */
    static JsonObject responseFormatConfig(JsonObject format) {
        if ("json_object".equals(format.optString("type"))) return Json.obj("responseMimeType", "application/json");
        JsonObject schema = format.get("schema").asObject();
        String field = containsKey(schema, "additionalProperties") ? "responseJsonSchema" : "responseSchema";
        return Json.obj("responseMimeType", "application/json", field, schema);
    }

    // ─── the generateContent payload ───

    /** The wire body for a Request; {@code model} is the wire model (MAP-7 class detection reads it). */
    static JsonObject payload(Request request, String model, String provider) {
        Config config = request.config();
        JsonObject extensions = config.extensions();
        CacheConfig cache = config.cache();
        // MAP-6 on Gemini: automatic tier sends nothing; the resource tier
        // names a stored object that already holds system, tools and the
        // prefix messages, so the wire carries only the suffix.
        String resource = null;
        int suffixFrom = 0;
        if (cache != null && cache.mode() != CacheMode.OFF) {
            if (cache.key() != null) {
                throw unsupported(provider, "gemini: cache.key is not supported — GenerateContent has no cache "
                    + "affinity key; use cache.resource with a stored cache (lm.cache(prefix))");
            }
            if (cache.retention() != null && cache.retention() != CacheRetention.SHORT) {
                throw unsupported(provider, "gemini: cache.retention is not supported in-request — lifetime belongs to "
                    + "the stored cache (cache_create(..., ttl_seconds=...) / cache_update)");
            }
            if (cache.resource() != null) {
                resource = cache.resource();
                if (cache.prefixUntilIndex() != null) {
                    suffixFrom = Math.min(cache.prefixUntilIndex(), request.messages().size() - 1) + 1;
                }
            }
        }
        List<Message> wireMessages = request.messages().subList(suffixFrom, request.messages().size());
        if (resource != null && wireMessages.isEmpty()) {
            throw ValidationException.value("gemini: a request against a stored cache needs at least one message after the prefix");
        }

        Map<String, String> names = callNames(request.messages());
        JsonBuilder payload = new JsonBuilder().put("contents", contents(wireMessages, names, provider));
        if (resource != null) payload.put("cachedContent", cacheResource(resource));
        if (request.system() != null && resource == null) payload.put("systemInstruction", systemInstruction(request.system()));

        JsonBuilder generation = new JsonBuilder();
        if (config.temperature() != null) generation.put("temperature", number(config.temperature()));
        if (config.maxTokens() != null) generation.put("maxOutputTokens", config.maxTokens());
        if (config.topP() != null) generation.put("topP", number(config.topP()));
        if (config.topK() != null) generation.put("topK", config.topK());
        if (!config.stop().isEmpty()) generation.put("stopSequences", config.stop());
        if (config.logprobs() != null) {
            // Doc-based (every served model rejects responseLogprobs live, 2026-09-01); the 400 surfaces typed.
            generation.put("responseLogprobs", true);
            if (config.logprobs() > 0) generation.put("logprobs", config.logprobs());
        }
        if (config.responseFormat() != null) generation.putAll(responseFormatConfig(config.responseFormat()));
        Reasoning reasoning = config.reasoning();
        if (reasoning != null) {
            boolean level = levelClass(model);
            if (reasoning.isOff()) {
                if (level) {
                    // MAP-7 rule 4 / MAP-5: 3.7 Flash takes thinkingBudget 0 and still spends tokens.
                    throw unsupported(provider, "gemini: reasoning cannot be disabled on " + model + " — the Gemini 3 "
                        + "class has no full off switch (thinkingBudget 0 is accepted but not honoured); use effort='low' or a 2.5 model");
                }
                generation.put("thinkingConfig", Json.obj("thinkingBudget", 0));
            } else {
                if (reasoning.summary() == ReasoningSummary.CONCISE || reasoning.summary() == ReasoningSummary.DETAILED) {
                    throw unsupported(provider, "gemini: reasoning.summary='" + reasoning.summary().wire() + "' is an OpenAI detail level; "
                        + "GenerateContent has includeThoughts only (use 'auto')");
                }
                JsonBuilder thinking = new JsonBuilder();
                if (reasoning.summary() != null) thinking.put("includeThoughts", true); // MAP-7 rule 7: only when asked
                if (reasoning.thinkingBudget() != null) {
                    thinking.put("thinkingBudget", reasoning.thinkingBudget()); // 3.x: accepted, docs warn
                } else if (level) {
                    if (reasoning.effort() == ReasoningEffort.XHIGH || reasoning.effort() == ReasoningEffort.MAX) {
                        throw unsupported(provider, "gemini: reasoning.effort='" + reasoning.effort().wire() + "' has no thinkingLevel on the "
                            + "Gemini 3 class (minimal|low|medium|high); 'high' is the ceiling");
                    }
                    thinking.put("thinkingLevel", reasoning.effort().wire());
                } else {
                    thinking.put("thinkingBudget", Common.EFFORT_THINKING_BUDGETS.get(reasoning.effort()));
                }
                generation.put("thinkingConfig", thinking.build());
            }
        }
        if (!generation.isEmpty()) payload.put("generationConfig", generation.build());

        if (!request.tools().isEmpty() && resource == null) payload.put("tools", toolsWire(request.tools()));
        JsonObject toolConfig = resource == null ? toolConfig(request, provider) : null;
        if (toolConfig != null) payload.put("toolConfig", toolConfig);

        String output = extensions == null ? null : extensions.optString("output");
        if ("image".equals(output)) setResponseModalities(payload, "IMAGE");
        else if ("audio".equals(output)) setResponseModalities(payload, "AUDIO");

        // Promoted cross-provider knobs: store and serviceTier map verbatim; user_id has no slot.
        if (config.store() != null) payload.put("store", config.store());
        if (config.serviceTier() != null) payload.put("serviceTier", config.serviceTier());
        if (config.userId() != null) {
            throw unsupported(provider, "gemini: config.user_id is not supported — GenerateContent has no "
                + "end-user attribution field (OpenAI and Anthropic carry it)");
        }

        if (extensions != null) {
            for (Map.Entry<String, JsonValue> e : extensions.members().entrySet()) {
                if (!RESERVED_EXTENSIONS.contains(e.getKey())) payload.put(e.getKey(), e.getValue());
            }
        }
        return payload.build();
    }

    private static void setResponseModalities(JsonBuilder payload, String modality) {
        JsonValue existing = payload.get("generationConfig");
        JsonBuilder generation = existing instanceof JsonObject o ? o.toBuilder() : new JsonBuilder();
        generation.put("responseModalities", List.of(modality));
        payload.put("generationConfig", generation.build());
    }
}
