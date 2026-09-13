package dev.lm15.dialects.openai;

import dev.lm15.compat.OpenAIResponsesCompat;
import dev.lm15.dialects.Common;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBool;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.types.*;
import dev.lm15.wire.BuildContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The Responses request body (the reference's {@code OpenAILM._payload} and its helpers). */
final class ResponsesRequest {
    private ResponsesRequest() {}

    static final String CODEX_BACKEND = "chatgpt-codex";

    /** Canonical builtin tool name → OpenAI Responses API tool type. */
    static final Map<String, String> OPENAI_BUILTIN_MAP = Map.of(
        "web_search", "web_search_preview",
        "code_execution", "code_interpreter",
        "file_search", "file_search",
        "computer_use", "computer_use_preview");

    /** Per compat {@code builtin_tools} value; "verbatim" is the empty table (the canonical name is the wire type). */
    static final Map<String, Map<String, String>> BUILTIN_MAPS = Map.of("openai", OPENAI_BUILTIN_MAP, "verbatim", Map.of());

    static final Set<String> RESERVED_EXTENSIONS = Set.of("prompt_caching", "cache", "compat", "openai_compat", "openai_responses_compat");

    private static final Pattern GPT_VERSION = Pattern.compile("^gpt-(\\d+)\\.(\\d+)");

    static boolean isCodex(BuildContext cx) {
        return CODEX_BACKEND.equals(cx.policy().backend());
    }

    /** The resolved compat: the bound value, the request-level extension hatch, per-model overrides. */
    static OpenAIResponsesCompat.Resolved compat(BuildContext cx, JsonObject requestExtensions) {
        OpenAIResponsesCompat base = cx.compat() instanceof OpenAIResponsesCompat c ? c : OpenAIResponsesCompat.preset("openai");
        base = base.forModel(cx.model());
        return base.merge(OpenAIResponsesCompat.fromExtensions(requestExtensions)).resolve();
    }

    static String builtinType(BuiltinTool tool, OpenAIResponsesCompat.Resolved compat) {
        return BUILTIN_MAPS.getOrDefault(compat.builtinTools(), Map.of()).getOrDefault(tool.name(), tool.name());
    }

    static JsonObject builtinToOpenAI(BuiltinTool tool, OpenAIResponsesCompat.Resolved compat) {
        JsonBuilder out = new JsonBuilder().put("type", builtinType(tool, compat));
        if (tool.config() != null && !tool.config().isEmpty()) out.putAll(tool.config());
        return out.build();
    }

    /** True for the GPT-5.6-and-later model class (MAP-6, mode="off"): the only class that accepts prompt_cache_options. */
    static boolean modelHasCacheOptions(String model) {
        Matcher m = GPT_VERSION.matcher(model.toLowerCase());
        if (!m.find()) return false;
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        return major > 5 || (major == 5 && minor >= 6);
    }

    /** Message index carrying the explicit prompt-cache breakpoint, or null. */
    static Integer cacheBreakpointIndex(Request request, String cacheControl) {
        CacheConfig cfg = request.config().cache();
        if (cfg == null || cfg.mode() == CacheMode.OFF || cfg.prefixUntilIndex() == null) return null;
        if (!cacheControl.equals("openai")) return null;
        return Math.min(cfg.prefixUntilIndex(), request.messages().size() - 1);
    }

    static boolean cacheStablePrefix(Request request, String cacheControl) {
        CacheConfig cfg = request.config().cache();
        return cfg != null && cfg.mode() != CacheMode.OFF && cfg.prefix() == CachePrefix.STABLE && cacheControl.equals("openai");
    }

    static boolean hasExplicitBreakpoint(Request request, String cacheControl) {
        return cacheBreakpointIndex(request, cacheControl) != null || (cacheStablePrefix(request, cacheControl) && request.system() != null);
    }

    /** Shared MAP-6 fields for both OpenAI dialects: off switch, key, retention, resource. */
    static void cacheCommonPayload(Request request, JsonBuilder payload, String cacheControl, String provider) {
        CacheConfig cfg = request.config().cache();
        if (cfg == null || !(cacheControl.equals("openai") || cacheControl.equals("openai_implicit"))) return;
        if (cacheControl.equals("openai_implicit")) {
            if (cfg.mode() != CacheMode.OFF) {
                if (cfg.key() != null) payload.put("prompt_cache_key", cfg.key());
                if (cfg.retention() == CacheRetention.LONG) payload.put("prompt_cache_retention", "24h");
            }
            if (cfg.resource() != null) {
                throw new UnsupportedFeatureError(provider + ": cache.resource is not supported — this provider has no stored-cache "
                    + "tier; it caches every prompt prefix automatically", ErrorMeta.of(provider));
            }
            return;
        }
        if (cfg.mode() == CacheMode.OFF) {
            if (modelHasCacheOptions(request.model())) payload.put("prompt_cache_options", Json.obj("mode", "explicit"));
            return;
        }
        if (cfg.key() != null) payload.put("prompt_cache_key", cfg.key());
        if (cfg.retention() == CacheRetention.LONG) payload.put("prompt_cache_retention", "24h");
        if (modelHasCacheOptions(request.model()) && hasExplicitBreakpoint(request, cacheControl)) {
            payload.put("prompt_cache_options", Json.obj("mode", "explicit"));
        }
        if (cfg.resource() != null) {
            throw new UnsupportedFeatureError(provider + ": cache.resource is not supported — this provider has no stored-cache "
                + "tier; it caches by marks on blocks (prefix / prefix_until_index) and automatically", ErrorMeta.of(provider));
        }
    }

    static UnsupportedFeatureError breakpointUnsupported(String provider, int index, Role role) {
        return new UnsupportedFeatureError(provider + ": cache.prefix_until_index=" + index + " points at a " + role.wire() + " message "
            + "whose last block is not text — the wire carries prompt_cache_breakpoint on text input blocks only. Point the prefix at a "
            + "user/developer message that ends with text, or omit prefix_until_index (implicit caching still applies).", ErrorMeta.of(provider));
    }

    static JsonObject responseFormatToText(JsonObject format) {
        if ("json_object".equals(format.optString("type"))) return Json.obj("format", Json.obj("type", "json_object"));
        JsonBuilder fmt = new JsonBuilder().put("type", "json_schema")
            .put("name", format.opt("name") != null ? format.optString("name") : "response")
            .put("schema", format.get("schema"));
        if (format.has("strict")) fmt.put("strict", format.get("strict"));
        return Json.obj("format", fmt.build());
    }

    // ─── input items ───

    static List<JsonValue> buildInput(List<Message> messages, OpenAIResponsesCompat.Resolved compat, Integer breakpointIndex, String provider) {
        List<JsonValue> items = new ArrayList<>();
        for (int msgIndex = 0; msgIndex < messages.size(); msgIndex++) {
            Message msg = messages.get(msgIndex);
            boolean atBreakpoint = breakpointIndex != null && breakpointIndex == msgIndex;
            if (atBreakpoint && (msg.role() == Role.ASSISTANT || msg.role() == Role.TOOL)) {
                throw breakpointUnsupported(provider, msgIndex, msg.role());
            }
            if (msg.role() == Role.TOOL) {
                for (Part part : msg.parts()) {
                    if (part instanceof ToolResultPart r) {
                        JsonBuilder item = new JsonBuilder().put("type", "function_call_output").put("call_id", r.id())
                            .put("output", Common.toolResultOutputOpenAI(provider, r, compat.toolResultMedia()));
                        if (compat.toolResultName().equals("include") && r.name() != null) item.put("name", r.name());
                        items.add(item.build());
                    }
                }
                continue;
            }

            List<JsonObject> content = new ArrayList<>();
            if (msg.role() == Role.ASSISTANT) {
                boolean nativeInput = msg.parts().stream().anyMatch(p -> p instanceof MediaPart);
                if (nativeInput) {
                    for (Part part : msg.parts()) {
                        if (part instanceof AudioPart || part instanceof VideoPart || part instanceof RefusalPart) {
                            throw new UnsupportedFeatureError(provider + ": " + part.type().wire()
                                + " cannot share an assistant Responses input message with media", ErrorMeta.of(provider));
                        }
                    }
                }
                String textType = nativeInput ? "input_text" : "output_text";
                for (Part part : msg.parts()) {
                    if (part instanceof TextPart t) {
                        content.add(Json.obj("type", textType, "text", t.text()));
                    } else if (part instanceof CitationPart c) {
                        content.add(Json.obj("type", textType, "text", Common.partsToText(List.of(c))));
                    } else if (part instanceof MediaPart) {
                        content.add(Common.partToOpenAIInput(part, provider));
                    } else if (part instanceof RefusalPart r) {
                        content.add(Json.obj("type", "refusal", "refusal", r.text()));
                    } else if (part instanceof ThinkingPart th) {
                        JsonObject state = ContinuationState.find(th.continuation(), "openai", "reasoning_item");
                        if (state != null && !state.isEmpty()) {
                            // Native replay (MAP-7 rule 8): the reasoning item goes back as its own input item.
                            JsonBuilder item = new JsonBuilder().put("type", "reasoning");
                            for (Map.Entry<String, JsonValue> e : state.members().entrySet()) {
                                if (e.getKey().equals("id") || e.getKey().equals("encrypted_content")) item.put(e.getKey(), e.getValue());
                            }
                            // `summary` is required on a replayed item, even empty (HTTP 400 without).
                            List<JsonValue> summary = new ArrayList<>();
                            if (!th.text().isEmpty()) summary.add(Json.obj("type", "summary_text", "text", th.text()));
                            item.put("summary", new JsonArray(summary));
                            items.add(item.build());
                        } else if (!th.text().isEmpty()) {
                            // No native state: replay as assistant text (decision G) rather than drop it.
                            content.add(Json.obj("type", textType, "text", th.text()));
                        }
                    }
                }
            } else {
                for (Part part : msg.parts()) {
                    if (part instanceof ToolCallPart || part instanceof ToolResultPart) continue;
                    content.add(Common.partToOpenAIInput(part, provider));
                }
            }
            if (atBreakpoint) {
                if (content.isEmpty() || !"input_text".equals(content.get(content.size() - 1).optString("type"))) {
                    throw breakpointUnsupported(provider, msgIndex, msg.role());
                }
                JsonObject last = content.get(content.size() - 1).with("prompt_cache_breakpoint", Json.obj("mode", "explicit"));
                content.set(content.size() - 1, last);
            }
            if (!content.isEmpty()) {
                String role = msg.role() == Role.DEVELOPER ? compat.developerRole() : msg.role().wire();
                JsonBuilder item = new JsonBuilder().put("role", role).put("content", new JsonArray(new ArrayList<JsonValue>(content)));
                if (compat.commentaryPhase().equals("tag") && msg.role() == Role.ASSISTANT && msg.parts().stream().anyMatch(p -> p instanceof ToolCallPart)) {
                    // Assistant text that precedes a function_call in the same turn is "commentary" on this server (Meta).
                    item.put("phase", "commentary");
                }
                items.add(item.build());
            }
            for (Part part : msg.parts()) {
                if (part instanceof ToolCallPart call) {
                    items.add(Json.obj("type", "function_call", "call_id", call.id(), "name", call.name(), "arguments", Js.dumpsCompact(call.input())));
                }
            }
        }
        return items;
    }

    // ─── tool choice ───

    static JsonValue toolChoicePayload(Request request, OpenAIResponsesCompat.Resolved compat) {
        ToolChoice tc = request.config().toolChoice();
        if (tc == null) return null;
        if (tc.mode() == ToolChoiceMode.NONE) return new JsonString("none");
        if (!tc.allowed().isEmpty()) {
            List<Tool> entries = new ArrayList<>();
            for (String name : tc.allowed()) {
                for (Tool t : request.tools()) if (t.name().equals(name)) { entries.add(t); break; }
            }
            if (entries.size() == 1 && tc.mode() == ToolChoiceMode.REQUIRED) {
                Tool tool = entries.get(0);
                if (tool instanceof BuiltinTool b) return Json.obj("type", builtinType(b, compat));
                return Json.obj("type", "function", "name", tool.name());
            }
            List<JsonValue> wireTools = new ArrayList<>();
            for (Tool tool : entries) {
                if (tool instanceof BuiltinTool b) wireTools.add(Json.obj("type", builtinType(b, compat)));
                else wireTools.add(Json.obj("type", "function", "name", tool.name()));
            }
            return Json.obj("type", "allowed_tools", "mode", tc.mode().wire(), "tools", new JsonArray(wireTools));
        }
        if (tc.mode() == ToolChoiceMode.REQUIRED) return new JsonString("required");
        return new JsonString("auto");
    }

    // ─── the payload ───

    static JsonObject payload(Request request, boolean stream, BuildContext cx) {
        String provider = cx.provider();
        Config config = request.config();
        OpenAIResponsesCompat.Resolved compat = compat(cx, config.extensions());
        List<JsonValue> input = buildInput(request.messages(), compat, cacheBreakpointIndex(request, compat.cacheControl()), provider);
        JsonBuilder payload = new JsonBuilder().put("model", cx.model());
        if (request.system() != null) {
            String systemText = request.system().isText() ? request.system().text() : Common.partsToText(request.system().parts());
            if (cacheStablePrefix(request, compat.cacheControl())) {
                // Top-level `instructions` cannot carry a breakpoint: render the system prompt as the first input item with the mark.
                input.add(0, Json.obj("role", compat.developerRole(), "content", Json.arr(
                    Json.obj("type", "input_text", "text", systemText, "prompt_cache_breakpoint", Json.obj("mode", "explicit")))));
                payload.put("input", new JsonArray(input)).put("stream", stream);
            } else {
                payload.put("input", new JsonArray(input)).put("stream", stream).put("instructions", systemText);
            }
        } else {
            payload.put("input", new JsonArray(input)).put("stream", stream);
        }
        if (config.maxTokens() != null) payload.put(compat.maxOutputTokensField(), config.maxTokens());
        if (config.temperature() != null) payload.put("temperature", config.temperature());
        if (config.topP() != null) payload.put("top_p", config.topP());
        if (!config.stop().isEmpty()) {
            throw new UnsupportedFeatureError(provider + ": config.stop has no field on the Responses wire (the Chat Completions "
                + "dialect carries `stop`); a silent omission would run the model past the sequence", ErrorMeta.of(provider));
        }
        if (config.topK() != null) {
            throw new UnsupportedFeatureError(provider + ": config.top_k has no field on the Responses wire (Anthropic and Gemini carry it)", ErrorMeta.of(provider));
        }
        if (config.logprobs() != null) {
            payload.put("top_logprobs", config.logprobs());
            payload.put("include", Json.arr("message.output_text.logprobs"));
        }
        if (!request.tools().isEmpty()) {
            List<JsonValue> tools = new ArrayList<>();
            for (Tool tool : request.tools()) {
                if (tool instanceof FunctionTool f) {
                    JsonBuilder t = new JsonBuilder().put("type", "function").put("name", f.name()).put("description", f.description()).put("parameters", f.parameters());
                    if (compat.strictTools().equals("include")) t.put("strict", false);
                    tools.add(t.build());
                } else if (tool instanceof BuiltinTool b) {
                    tools.add(builtinToOpenAI(b, compat));
                }
            }
            payload.put("tools", new JsonArray(tools));
        }
        JsonValue toolChoice = toolChoicePayload(request, compat);
        if (toolChoice != null) payload.put("tool_choice", toolChoice);
        if (config.toolChoice() != null && config.toolChoice().parallel() != null) payload.put("parallel_tool_calls", config.toolChoice().parallel());
        if (config.responseFormat() != null) payload.put("text", responseFormatToText(config.responseFormat()));
        if (config.reasoning() != null) {
            Reasoning reasoning = config.reasoning();
            String fmt = compat.reasoningFormat();
            if (!reasoning.isOff()) {
                if (reasoning.thinkingBudget() != null) {
                    throw new UnsupportedFeatureError(provider + ": reasoning.thinking_budget is not supported — this wire "
                        + "has no thinking token budget; use effort (Anthropic's manual class and Gemini take a budget)", ErrorMeta.of(provider));
                }
                String effort = reasoning.effort().wire();
                ReasoningSummary summary = reasoning.summary();
                if ((summary == ReasoningSummary.CONCISE || summary == ReasoningSummary.DETAILED) && !fmt.equals("responses_reasoning")) {
                    throw new UnsupportedFeatureError(provider + ": reasoning.summary='" + summary.wire() + "' is an OpenAI Responses "
                        + "detail level; this wire has no summary levels (use 'auto')", ErrorMeta.of(provider));
                }
                switch (fmt) {
                    case "responses_reasoning" -> {
                        JsonBuilder r = new JsonBuilder().put("effort", effort);
                        if (summary != null) r.put("summary", summary.wire());
                        payload.put("reasoning", r.build());
                    }
                    case "reasoning_effort" -> payload.put("reasoning_effort", effort);
                    case "openrouter" -> payload.put("reasoning", Json.obj("effort", effort));
                    case "deepseek" -> { payload.put("thinking", Json.obj("type", "enabled")); payload.put("reasoning_effort", effort); }
                    case "qwen", "zai" -> payload.put("enable_thinking", true);
                    case "qwen_chat_template" -> payload.put("chat_template_kwargs", Json.obj("enable_thinking", true, "preserve_thinking", true));
                    default -> { }
                }
            } else {
                // Explicit off must reach the wire (MAP-5).
                switch (fmt) {
                    case "responses_reasoning" -> payload.put("reasoning", Json.obj("effort", "none"));
                    case "reasoning_effort" -> payload.put("reasoning_effort", "none");
                    case "openrouter" -> payload.put("reasoning", Json.obj("enabled", false));
                    case "deepseek" -> payload.put("thinking", Json.obj("type", "disabled"));
                    case "qwen", "zai" -> payload.put("enable_thinking", false);
                    case "qwen_chat_template" -> payload.put("chat_template_kwargs", Json.obj("enable_thinking", false));
                    default -> { }
                }
            }
        }

        cacheCommonPayload(request, payload, compat.cacheControl(), provider);

        if (compat.routing() != null) payload.put("provider", compat.routing());

        if (config.serviceTier() != null) payload.put("service_tier", config.serviceTier());
        if (config.userId() != null) payload.put("safety_identifier", config.userId());
        if (config.store() != null) payload.put("store", config.store());

        if (config.extensions() != null) {
            for (Map.Entry<String, JsonValue> e : config.extensions().members().entrySet()) {
                if (!RESERVED_EXTENSIONS.contains(e.getKey())) payload.put(e.getKey(), e.getValue());
            }
        }
        if (isCodex(cx)) {
            // Backend facts: streaming-only, rejects store=true and every max-token knob, expects instructions.
            String prefix = cx.policy().systemPrefix();
            if (prefix != null && !prefix.isEmpty() && !payload.has("instructions")) payload.put("instructions", prefix);
            payload.put("store", JsonBool.FALSE);
            payload.put("stream", JsonBool.TRUE);
            payload.remove("max_output_tokens");
            payload.remove("max_completion_tokens");
            payload.remove("max_tokens");
        }
        return payload.build();
    }
}
