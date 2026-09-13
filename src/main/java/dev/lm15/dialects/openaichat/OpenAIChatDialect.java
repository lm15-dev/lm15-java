package dev.lm15.dialects.openaichat;

import dev.lm15.compat.Compat;
import dev.lm15.compat.OpenAIChatCompat;
import dev.lm15.dialects.Common;
import dev.lm15.dialects.Dialect;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.LM15Error;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonInt;
import dev.lm15.json.JsonNull;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.registry.DialectId;
import dev.lm15.registry.Registry;
import dev.lm15.serde.Canonical;
import dev.lm15.sse.SseEvent;
import dev.lm15.transport.HttpResponse;
import dev.lm15.types.*;
import dev.lm15.wire.BuildContext;
import dev.lm15.wire.WireRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The OpenAI Chat Completions dialect (the reference's
 * {@code lm15/providers/openai_chat.py}): the wire format spoken by OpenAI's
 * legacy endpoint and by most OpenAI-compatible servers — ollama, Groq,
 * OpenRouter, vLLM, SGLang, DeepSeek, Z.AI, Moonshot, Meta, the Azure and
 * Bedrock chat doors. Server quirks are the {@link OpenAIChatCompat} presets.
 */
public class OpenAIChatDialect implements Dialect {

    /** Canonical builtin tool name → Groq server-executed tool type (compat builtin_tools="groq"; both verified live 2026-09-01). */
    static final Map<String, String> GROQ_BUILTIN_MAP = Map.of("web_search", "browser_search", "code_execution", "code_interpreter");
    static final Map<String, String> GROQ_BUILTIN_INVERSE = Map.of("browser_search", "web_search", "code_interpreter", "code_execution");

    static final Map<String, FinishReason> FINISH_REASON_MAP = Map.of(
        "stop", FinishReason.STOP,
        "length", FinishReason.LENGTH,
        "tool_calls", FinishReason.TOOL_CALL,
        "function_call", FinishReason.TOOL_CALL,
        "content_filter", FinishReason.CONTENT_FILTER);

    private static final Pattern GPT_VERSION = Pattern.compile("^gpt-(\\d+)\\.(\\d+)");

    /** Extensions keys the builder never forwards (compat/config selectors, not wire fields). */
    private static final Set<String> RESERVED_EXTENSIONS = Set.of("prompt_caching", "cache", "compat", "openai_compat", "openai_chat_compat");

    public OpenAIChatDialect() {}

    @Override public DialectId id() { return DialectId.OPENAI_CHAT; }

    @Override public Compat compat(String preset) { return OpenAIChatCompat.preset(preset); }

    @Override public Compat defaultCompat(String provider) {
        return Registry.canonicalProvider(provider).equals("xai") ? OpenAIChatCompat.preset("xai") : OpenAIChatCompat.preset("openai");
    }

    // ─── shared bits ───

    static OpenAIChatCompat compatOf(BuildContext cx) {
        return cx.compat() == null ? OpenAIChatCompat.DEFAULT : cx.compat(OpenAIChatCompat.class);
    }

    /** The resolved compat for this model: the preset's per-family overrides applied. */
    static OpenAIChatCompat compatFor(BuildContext cx, String model) {
        return compatOf(cx).forModel(model);
    }

    /** Content-type plus the policy's static headers; auth is applied once, in Wire.emit (AUTH-2). */
    static void headers(WireRequest w, BuildContext cx) {
        w.header("Content-Type", "application/json");
        if (cx.policy() != null) for (Map.Entry<String, String> h : cx.policy().headers()) w.header(h.getKey(), h.getValue());
    }

    static UnsupportedFeatureError unsupported(BuildContext cx, String message) {
        return new UnsupportedFeatureError(cx.provider() + ": " + message, ErrorMeta.of(cx.provider()));
    }

    /** The model string the wire carries: the bound wire model, else the request's. */
    static String wireModel(Request request, BuildContext cx) {
        return cx.model() == null || cx.model().isEmpty() ? request.model() : cx.model();
    }

    /** True for the GPT-5.6-and-later model class (MAP-6, mode="off"): the only class that accepts prompt_cache_options. */
    static boolean modelHasCacheOptions(String model) {
        Matcher m = GPT_VERSION.matcher(model.toLowerCase());
        if (!m.find()) return false;
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        return major > 5 || (major == 5 && minor >= 6);
    }

    // ─── request serialization ───

    /** {@code {"type": "image_url", "image_url": {url, detail?}}}: a URL verbatim, inline data or a path as a data URI; file_id has no slot. */
    static JsonObject imageBlock(ImagePart part, BuildContext cx) {
        if (part.fileId() != null) {
            throw unsupported(cx, "an image addressed by file_id cannot be sent on the Chat Completions wire (no file reference form); pass a URL or inline data");
        }
        JsonBuilder payload = new JsonBuilder().put("url", part.url() != null ? part.url() : Common.mediaDataUri(part));
        if (part.detail() != null) payload.put("detail", part.detail().wire());
        return Json.obj("type", "image_url", "image_url", payload.build());
    }

    /** Non-assistant message parts → content: a lone text part is a string (unless {@code forceArray}); anything multimodal is an array; a part with no slot RAISES (MAP-10). */
    static JsonValue contentParts(Message msg, boolean forceArray, BuildContext cx) {
        List<Part> parts = new ArrayList<>();
        for (Part p : msg.parts()) if (!(p instanceof ToolCallPart) && !(p instanceof ToolResultPart)) parts.add(p);
        if (parts.size() == 1 && parts.get(0) instanceof TextPart t && !forceArray) return new JsonString(t.text());
        List<JsonValue> out = new ArrayList<>();
        for (Part part : parts) {
            if (part instanceof TextPart t) out.add(Json.obj("type", "text", "text", t.text()));
            else if (part instanceof ImagePart img) out.add(imageBlock(img, cx));
            else if (part instanceof ThinkingPart) continue; // thinking is never replayed as user content
            else if (Common.MEDIA_KINDS.contains(part.type())) {
                throw unsupported(cx, "a " + part.type().wire() + " part in a " + msg.role().wire() + " message has no slot on the Chat Completions wire "
                    + "(text and image_url only); the OpenAI Responses, Anthropic and Gemini dialects carry it (MAP-10)");
            } else out.add(Json.obj("type", "text", "text", Common.partsToText(List.of(part), cx.provider(), "a text-only wire field")));
        }
        return new JsonArray(out);
    }

    /** A {@code role: tool} row's content (MAP-10): a string when text-only; text and image_url blocks where the preset proved the array form; is_error rides as an {@code [error] } prefix. */
    static JsonValue toolRowContent(BuildContext cx, ToolResultPart part, String policy) {
        Common.checkToolResultMedia(cx.provider(), part, policy, "a Chat Completions tool row");
        if (Common.textOnly(part.content())) {
            return new JsonString(Common.toolResultErrorText(part, Common.partsToText(part.content(), cx.provider(), "a Chat Completions tool row")));
        }
        List<JsonObject> blocks = new ArrayList<>();
        for (Part p : part.content()) {
            if (p instanceof ImagePart img) blocks.add(imageBlock(img, cx));
            else blocks.add(Json.obj("type", "text", "text", Common.partsToText(List.of(p), cx.provider(), "a text-only wire field")));
        }
        if (part.isError()) {
            int first = -1;
            for (int i = 0; i < blocks.size(); i++) if ("text".equals(blocks.get(i).optString("type"))) { first = i; break; }
            if (first < 0) blocks.add(0, Json.obj("type", "text", "text", "[error]"));
            else blocks.set(first, blocks.get(first).with("text", new JsonString("[error] " + blocks.get(first).optString("text"))));
        }
        return new JsonArray(new ArrayList<>(blocks));
    }

    /** Canonical response_format (INV-050) → chat-completions response_format. */
    static JsonObject responseFormatToChat(JsonObject format) {
        if ("json_object".equals(format.optString("type"))) return Json.obj("type", "json_object");
        JsonBuilder inner = new JsonBuilder()
            .put("name", format.get("name") instanceof JsonString n && !n.value().isEmpty() ? n.value() : "response")
            .put("schema", format.get("schema"));
        if (format.has("strict")) inner.put("strict", format.get("strict"));
        return Json.obj("type", "json_schema", "json_schema", inner.build());
    }

    // MAP-6 helpers (the reference's openai.py cache functions, shared by both OpenAI dialects)

    /** Message index that carries the explicit prompt-cache breakpoint, clamped to the last message; only under cache_control="openai". */
    static Integer cacheBreakpointIndex(Request request, String cacheControl) {
        CacheConfig c = request.config().cache();
        if (c == null || c.mode() == CacheMode.OFF || c.prefixUntilIndex() == null) return null;
        if (!cacheControl.equals("openai")) return null;
        return Math.min(c.prefixUntilIndex(), request.messages().size() - 1);
    }

    /** prefix="stable": mark the end of system + tools (MAP-6 A2). */
    static boolean cacheStablePrefix(Request request, String cacheControl) {
        CacheConfig c = request.config().cache();
        return c != null && c.mode() != CacheMode.OFF && c.prefix() == CachePrefix.STABLE && cacheControl.equals("openai");
    }

    static boolean hasExplicitBreakpoint(Request request, String cacheControl) {
        return cacheBreakpointIndex(request, cacheControl) != null || (cacheStablePrefix(request, cacheControl) && request.system() != null);
    }

    /** Shared MAP-6 fields: off switch, key, retention; resource RAISES (no stored-cache tier). */
    static void cacheCommonPayload(Request request, JsonBuilder payload, String cacheControl, BuildContext cx) {
        CacheConfig c = request.config().cache();
        if (c == null || !(cacheControl.equals("openai") || cacheControl.equals("openai_implicit"))) return;
        if (cacheControl.equals("openai_implicit")) {
            if (c.mode() != CacheMode.OFF) {
                if (c.key() != null) payload.put("prompt_cache_key", c.key());
                if (c.retention() == CacheRetention.LONG) payload.put("prompt_cache_retention", "24h");
            }
            if (c.resource() != null) {
                throw unsupported(cx, "cache.resource is not supported — this provider has no stored-cache tier; it caches every prompt prefix automatically");
            }
            return;
        }
        if (c.mode() == CacheMode.OFF) {
            // Option 2 (ratified 2026-09-01): the real off switch where the model class has one; nothing where writes are free anyway.
            if (modelHasCacheOptions(request.model())) payload.put("prompt_cache_options", Json.obj("mode", "explicit"));
            return;
        }
        if (c.key() != null) payload.put("prompt_cache_key", c.key());
        if (c.retention() == CacheRetention.LONG) payload.put("prompt_cache_retention", "24h");
        if (modelHasCacheOptions(request.model()) && hasExplicitBreakpoint(request, cacheControl)) {
            // A placed breakpoint means "cache up to here"; the mark and the mode go together (review probe 3, 2026-09-02).
            payload.put("prompt_cache_options", Json.obj("mode", "explicit"));
        }
        if (c.resource() != null) {
            throw unsupported(cx, "cache.resource is not supported — this provider has no stored-cache tier; it caches by marks on blocks (prefix / prefix_until_index) and automatically");
        }
    }

    static UnsupportedFeatureError breakpointUnsupported(BuildContext cx, int index, Role role) {
        return unsupported(cx, "cache.prefix_until_index=" + index + " points at a " + role.wire() + " message whose last block is not text — the wire carries "
            + "prompt_cache_breakpoint on text input blocks only. Point the prefix at a user/developer message that ends with text, or omit prefix_until_index (implicit caching still applies).");
    }

    JsonArray buildMessages(Request request, OpenAIChatCompat compat, BuildContext cx) {
        List<JsonValue> messages = new ArrayList<>();
        if (request.system() != null) {
            String systemText = request.system().isText() ? request.system().text() : Common.partsToText(request.system().parts());
            if (cacheStablePrefix(request, compat.cacheControl())) {
                // prefix="stable": the mark rides on the system message's text content part (array form; a bare string cannot carry it).
                messages.add(Json.obj("role", compat.instructionRole(), "content", Json.arr(
                    Json.obj("type", "text", "text", systemText, "prompt_cache_breakpoint", Json.obj("mode", "explicit")))));
            } else {
                messages.add(Json.obj("role", compat.instructionRole(), "content", systemText));
            }
        }

        Integer breakpointIndex = cacheBreakpointIndex(request, compat.cacheControl());
        for (int msgIndex = 0; msgIndex < request.messages().size(); msgIndex++) {
            Message msg = request.messages().get(msgIndex);
            if (breakpointIndex != null && msgIndex == breakpointIndex && (msg.role() == Role.ASSISTANT || msg.role() == Role.TOOL)) {
                throw breakpointUnsupported(cx, msgIndex, msg.role());
            }
            if (msg.role() == Role.TOOL) {
                for (Part part : msg.parts()) {
                    if (part instanceof ToolResultPart result) {
                        JsonBuilder item = new JsonBuilder().put("role", "tool").put("tool_call_id", result.id())
                            .put("content", toolRowContent(cx, result, compat.toolResultMedia()));
                        if (compat.toolResultName().equals("include") && result.name() != null && !result.name().isEmpty()) item.put("name", result.name());
                        messages.add(item.build());
                    }
                }
                continue;
            }

            if (msg.role() == Role.ASSISTANT) {
                List<String> textBits = new ArrayList<>();
                List<JsonValue> toolCalls = new ArrayList<>();
                List<String> thinkingBits = new ArrayList<>();
                for (Part part : msg.parts()) {
                    if (part instanceof MediaPart) {
                        throw new UnsupportedFeatureError(cx.provider() + ": " + part.type().wire()
                            + " has no native block in an assistant Chat Completions message", ErrorMeta.of(cx.provider()));
                    }
                    if (part instanceof TextPart t) textBits.add(t.text());
                    else if (part instanceof CitationPart c) textBits.add(Common.partsToText(List.of(c)));
                    else if (part instanceof RefusalPart r && !r.text().isEmpty()) textBits.add(r.text());
                    else if (part instanceof ThinkingPart th && compat.thinkingReplay().equals("as_text") && !th.text().isEmpty()) textBits.add(th.text());
                    if (part instanceof ThinkingPart th && !th.text().isEmpty()) thinkingBits.add(th.text());
                    if (part instanceof ToolCallPart call) {
                        toolCalls.add(Json.obj("id", call.id(), "type", "function",
                            "function", Json.obj("name", call.name(), "arguments", Json.write(call.input()))));
                    }
                }
                JsonBuilder item = new JsonBuilder().put("role", "assistant")
                    .put("content", textBits.isEmpty() ? JsonNull.INSTANCE : new JsonString(String.join("\n", textBits)));
                if (compat.thinkingReplay().equals("native")) {
                    String thinking = String.join("\n", thinkingBits);
                    if (!thinking.isEmpty() || compat.assistantReasoningContent().equals("include_empty")) item.put("reasoning_content", thinking);
                }
                if (!toolCalls.isEmpty()) item.put("tool_calls", new JsonArray(toolCalls));
                messages.add(item.build());
                continue;
            }

            String role = msg.role() == Role.DEVELOPER ? compat.instructionRole() : msg.role().wire();
            boolean atBreakpoint = breakpointIndex != null && msgIndex == breakpointIndex;
            JsonValue content = contentParts(msg, atBreakpoint, cx);
            if (atBreakpoint) {
                // The breakpoint rides on the last text content block of the prefix message (ChatCompletionContentPartText carries prompt_cache_breakpoint).
                if (!(content instanceof JsonArray arr) || arr.isEmpty() || !(arr.get(arr.size() - 1) instanceof JsonObject last) || !"text".equals(last.optString("type"))) {
                    throw breakpointUnsupported(cx, msgIndex, msg.role());
                }
                List<JsonValue> blocks = new ArrayList<>(arr.values());
                blocks.set(blocks.size() - 1, last.with("prompt_cache_breakpoint", Json.obj("mode", "explicit")));
                content = new JsonArray(blocks);
            }
            boolean keep = content instanceof JsonString || (content instanceof JsonArray a && !a.isEmpty());
            if (keep) messages.add(Json.obj("role", role, "content", content));
        }
        return new JsonArray(messages);
    }

    /** Map a BuiltinTool for the chat dialect, or raise: the base wire carries function/custom tools only, and unproven servers ignore unknown types silently. */
    JsonObject builtinToolPayload(BuiltinTool tool, OpenAIChatCompat compat, BuildContext cx) {
        if (compat.builtinTools().equals("groq")) {
            String wireType = GROQ_BUILTIN_MAP.get(tool.name());
            if (wireType == null) {
                throw unsupported(cx, "builtin tool '" + tool.name() + "' has no Groq wire mapping — supported: " + new java.util.TreeSet<>(GROQ_BUILTIN_MAP.keySet()));
            }
            JsonBuilder entry = new JsonBuilder().put("type", wireType);
            if (tool.config() != null && !tool.config().isEmpty()) entry.putAll(tool.config());
            return entry.build();
        }
        throw unsupported(cx, "builtin tool '" + tool.name() + "' is not supported on this server — the Chat Completions wire carries function tools only, and "
            + "unproven servers may silently ignore unknown tool types. Use compat='groq' for Groq's server-executed tools, or the OpenAI Responses / Anthropic / Gemini providers");
    }

    JsonValue toolChoicePayload(Request request, BuildContext cx) {
        ToolChoice tc = request.config().toolChoice();
        if (tc == null) return null;
        if (tc.mode() == ToolChoiceMode.NONE) return new JsonString("none");
        if (!tc.allowed().isEmpty()) {
            Map<String, Tool> byName = new LinkedHashMap<>();
            for (Tool t : request.tools()) byName.put(t.name(), t);
            List<Tool> entries = new ArrayList<>();
            List<String> builtins = new ArrayList<>();
            for (String name : tc.allowed()) {
                Tool t = byName.get(name);
                entries.add(t);
                if (t instanceof BuiltinTool) builtins.add(t.name());
            }
            if (!builtins.isEmpty()) {
                throw unsupported(cx, "cannot force builtin tools " + builtins + " — the Chat Completions wire has no hosted-tool tool_choice form (OpenAI Responses and Anthropic carry it)");
            }
            if (entries.size() == 1 && tc.mode() == ToolChoiceMode.REQUIRED) {
                return Json.obj("type", "function", "function", Json.obj("name", entries.get(0).name()));
            }
            // mode="auto" restriction or multi-tool subset: the dialect's allowed_tools form (nested spelling, function tools only).
            List<JsonValue> tools = new ArrayList<>();
            for (Tool t : entries) tools.add(Json.obj("type", "function", "function", Json.obj("name", t.name())));
            return Json.obj("type", "allowed_tools", "allowed_tools", Json.obj("mode", tc.mode().wire(), "tools", new JsonArray(tools)));
        }
        if (tc.mode() == ToolChoiceMode.REQUIRED) return new JsonString("required");
        return new JsonString("auto");
    }

    /** The request body (the reference's {@code _payload}). */
    protected JsonObject payload(Request request, boolean stream, BuildContext cx) {
        OpenAIChatCompat compat = compatFor(cx, request.model());
        Config config = request.config();
        JsonBuilder payload = new JsonBuilder().put("model", wireModel(request, cx)).put("messages", buildMessages(request, compat, cx));
        if (stream) {
            payload.put("stream", true);
            if (compat.streamUsage().equals("include")) payload.put("stream_options", Json.obj("include_usage", true));
        }
        if (config.maxTokens() != null) payload.put(compat.maxTokensField(), config.maxTokens());
        if (config.temperature() != null) payload.put("temperature", config.temperature());
        if (config.topP() != null) payload.put("top_p", config.topP());
        if (config.topK() != null) {
            // No wire slot on Chat Completions (port.md rule 4: a raise or an extensions door, never omission).
            throw unsupported(cx, "config.top_k has no field on the Chat Completions wire; servers that accept top_k take it through extensions");
        }
        if (!config.stop().isEmpty()) payload.put("stop", config.stop());
        if (config.logprobs() != null) {
            // Verified live 2026-09-01: logprobs=true alone returns chosen tokens only; top_logprobs adds the alternatives.
            payload.put("logprobs", true);
            if (config.logprobs() > 0) payload.put("top_logprobs", config.logprobs());
        }
        if (!request.tools().isEmpty()) {
            List<JsonValue> toolsWire = new ArrayList<>();
            for (Tool tool : request.tools()) {
                if (tool instanceof FunctionTool f) {
                    JsonBuilder function = new JsonBuilder().put("name", f.name()).put("description", f.description() == null ? JsonNull.INSTANCE : new JsonString(f.description()))
                        .put("parameters", f.parameters());
                    if (compat.strictTools().equals("include")) function.put("strict", false);
                    toolsWire.add(Json.obj("type", "function", "function", function.build()));
                } else if (tool instanceof BuiltinTool b) {
                    toolsWire.add(builtinToolPayload(b, compat, cx));
                }
            }
            if (!toolsWire.isEmpty()) payload.put("tools", new JsonArray(toolsWire));
        }
        JsonValue toolChoice = toolChoicePayload(request, cx);
        if (toolChoice != null) {
            ToolChoice tc = config.toolChoice();
            if (compat.forcedToolChoice().equals("reject") && (tc.mode() != ToolChoiceMode.AUTO || !tc.allowed().isEmpty())) {
                // MAP-8: the server documents tool_choice=auto only and ignores every other form without an error (Z.AI, live 2026-09-03).
                throw unsupported(cx, "tool_choice mode='" + tc.mode().wire() + "'" + (tc.allowed().isEmpty() ? "" : " allowed=" + tc.allowed())
                    + " is silently ignored by this server (only 'auto' is honoured); omit tool_choice, or send only the tools you want callable");
            }
            payload.put("tool_choice", toolChoice);
        }
        if (config.toolChoice() != null && config.toolChoice().parallel() != null) payload.put("parallel_tool_calls", config.toolChoice().parallel());
        if (config.responseFormat() != null) {
            String type = config.responseFormat().optString("type");
            if (compat.jsonSchema().equals("reject") && !"json_object".equals(type)) {
                // The server accepts response_format.type=json_schema and ignores it (Z.AI, live 2026-09-03). json_object is honoured.
                throw unsupported(cx, "response_format type '" + type + "' is silently ignored by this server; use {'type': 'json_object'} and describe the shape in the prompt");
            }
            payload.put("response_format", responseFormatToChat(config.responseFormat()));
        }
        if (config.reasoning() != null) {
            Reasoning reasoning = config.reasoning();
            String fmt = compat.thinkingFormat();
            if (fmt.equals("none")) {
                // No reasoning dial on this server (ollama / LM Studio). MAP-5 and MAP-7 rule 2: a raise, never an omission.
                throw unsupported(cx, "reasoning.effort='" + reasoning.effort().wire() + "' has no field on this server (compat thinking_format='none'); "
                    + "omit config.reasoning, or pass the server's own knob through extensions");
            }
            if (!reasoning.isOff()) {
                if (reasoning.thinkingBudget() != null) {
                    throw unsupported(cx, "reasoning.thinking_budget is not supported — the Chat Completions wire has no thinking token budget; use effort");
                }
                if (reasoning.summary() == ReasoningSummary.CONCISE || reasoning.summary() == ReasoningSummary.DETAILED) {
                    throw unsupported(cx, "reasoning.summary='" + reasoning.summary().wire() + "' is an OpenAI Responses detail level; the Chat Completions wire has none (use 'auto')");
                }
                String effort = reasoning.effort().wire();
                if (compat.reasoningEfforts() != null && !compat.reasoningEfforts().contains(effort)) {
                    // MAP-7 rule 2: a word with no native level raises here when the server would not refuse it (Moonshot kimi-k3, live 2026-09-03).
                    throw unsupported(cx, "reasoning.effort='" + effort + "' has no level on this server (it accepts " + String.join(", ", compat.reasoningEfforts()) + ") and would be accepted silently");
                }
                if (compat.builtinTools().equals("groq") && reasoning.summary() == ReasoningSummary.AUTO) {
                    // Groq's visibility knob (MAP-7 rule 7): "parsed" returns the trace as message.reasoning.
                    payload.put("reasoning_format", "parsed");
                }
                switch (fmt) {
                    case "reasoning_effort" -> payload.put("reasoning_effort", effort);
                    case "openrouter" -> payload.put("reasoning", Json.obj("effort", effort));
                    case "deepseek" -> {
                        payload.put("thinking", Json.obj("type", "enabled"));
                        payload.put("reasoning_effort", effort);
                    }
                    case "kimi" -> payload.put("reasoning_effort", effort);
                    case "qwen" -> payload.put("enable_thinking", true);
                    case "qwen_chat_template" -> payload.put("chat_template_kwargs", Json.obj("enable_thinking", true, "preserve_thinking", true));
                    default -> { }
                }
            } else {
                // Explicit off must reach the wire (MAP-5); servers whose models cannot disable reasoning reject "none" with a clear 400.
                switch (fmt) {
                    case "reasoning_effort" -> payload.put("reasoning_effort", "none");
                    case "openrouter" -> payload.put("reasoning", Json.obj("enabled", false));
                    case "deepseek", "kimi" -> payload.put("thinking", Json.obj("type", "disabled"));
                    case "qwen" -> payload.put("enable_thinking", false);
                    case "qwen_chat_template" -> payload.put("chat_template_kwargs", Json.obj("enable_thinking", false));
                    default -> { }
                }
            }
        }

        // Prompt caching (MAP-6): off switch, key, retention, resource.
        cacheCommonPayload(request, payload, compat.cacheControl(), cx);

        if (compat.routing() != null) payload.put("provider", compat.routing());

        // Promoted cross-provider knobs: user_id rides the dialect's `user` field, or the server's own name when the compat says so.
        if (config.serviceTier() != null) payload.put("service_tier", config.serviceTier());
        if (config.userId() != null) payload.put(compat.userField(), config.userId());
        if (config.store() != null) payload.put("store", config.store());

        if (config.extensions() != null) {
            for (Map.Entry<String, JsonValue> e : config.extensions().members().entrySet()) {
                if (!RESERVED_EXTENSIONS.contains(e.getKey())) payload.put(e.getKey(), e.getValue());
            }
        }
        return payload.build();
    }

    @Override public WireRequest build(Request request, boolean stream, BuildContext cx) {
        WireRequest w = WireRequest.post("/chat/completions", payload(request, stream, cx)).endpoint("chat/completions").model(wireModel(request, cx));
        headers(w, cx);
        w.readTimeout = Duration.ofSeconds(stream ? 120 : 60);
        return w;
    }

    // ─── model listing ───

    @Override public WireRequest modelsRequest(BuildContext cx) {
        WireRequest w = WireRequest.get("/models");
        headers(w, cx);
        w.readTimeout = Duration.ofSeconds(30);
        return w;
    }

    @Override public List<ModelInfo> parseModels(BuildContext cx, String body) {
        JsonValue data = Json.parse(body);
        JsonValue entries = data instanceof JsonObject o ? o.get("data") : null;
        return Common.modelInfosFromEntries(entries, cx.provider(), DialectId.OPENAI_CHAT.apiFamily(),
            entry -> entry.get("id") instanceof JsonString s ? s.value() : null);
    }

    // ─── ingest (MAP-12) ───

    @Override public Request requestFromOpenAIChat(BuildContext cx, JsonObject body) {
        String model = body.get("model") instanceof JsonString s ? s.value() : null;
        OpenAIChatCompat compat = model != null ? compatFor(cx, model) : compatOf(cx);
        return ChatIngest.ingest(cx.provider(), body, compat);
    }

    // ─── response parsing ───

    static FinishReason finishReason(JsonValue raw, boolean hasToolCall, List<JsonValue> unmapped, String path) {
        if (hasToolCall) return FinishReason.TOOL_CALL;
        if (raw == null || raw instanceof JsonNull || (raw instanceof JsonString s && s.value().isEmpty())) return FinishReason.STOP;
        FinishReason mapped = FINISH_REASON_MAP.get(ChatErrors.pyStr(raw));
        if (mapped == null) {
            Common.recordUnmapped(unmapped, path + ".finish_reason", raw);
            return FinishReason.STOP;
        }
        return mapped;
    }

    static Usage usageFromChat(JsonValue usageV) {
        JsonObject usage = usageV instanceof JsonObject o ? o : JsonObject.EMPTY;
        JsonObject prompt = usage.get("prompt_tokens_details") instanceof JsonObject p ? p : JsonObject.EMPTY;
        JsonObject completion = usage.get("completion_tokens_details") instanceof JsonObject c ? c : JsonObject.EMPTY;
        return new Usage(
            Canonical.readInt(usage, "prompt_tokens"),
            Canonical.readInt(usage, "completion_tokens"),
            Canonical.readInt(usage, "total_tokens"),
            Canonical.readInt(prompt, "cached_tokens"),
            Canonical.readInt(prompt, "cache_write_tokens"),
            Canonical.readInt(completion, "reasoning_tokens"),
            Canonical.readInt(prompt, "audio_tokens"),
            Canonical.readInt(completion, "audio_tokens"));
    }

    /**
     * The one Chat Completions response reader: {@code parseResponse} for provider traffic and the MAP-12 rule 9 door share it.
     * {@code choice} names the choice to read; null means "the only one", and a body with several choices is then refused.
     */
    Response responseFromChatBody(BuildContext cx, JsonValue dataV, String model, Integer choice) {
        if (!(dataV instanceof JsonObject data)) throw ValidationException.type("a Chat Completions response body is a JSON object, got " + ChatErrors.pyTypeName(dataV));
        if (data.get("error") instanceof JsonObject err) {
            String code = ChatErrors.truthy(err.get("code")) ? ChatErrors.pyStr(err.get("code")) : "";
            String message = ChatErrors.truthy(err.get("message")) ? ChatErrors.pyStr(err.get("message")) : err.toJson();
            throw ChatErrors.responseError(cx, code, message);
        }

        List<JsonValue> unmapped = new ArrayList<>();
        JsonValue choicesV = ChatErrors.truthy(data.get("choices")) ? data.get("choices") : JsonArray.EMPTY;
        if (!(choicesV instanceof JsonArray choices)) throw ValidationException.type("choices must be an array");
        int index;
        if (choice == null) {
            if (choices.size() > 1) {
                throw unsupported(cx, "the body carries " + choices.size() + " choices; a canonical Response is one message — name the choice to read (choice=i) and read each one, or send no n");
            }
            index = 0;
        } else {
            if (choice < 0 || choice >= choices.size()) throw ValidationException.value("choice=" + choice + " but the body carries " + choices.size() + " choice(s)");
            index = choice;
        }
        String path = "choices[" + index + "]";
        JsonObject chosen = !choices.isEmpty() && choices.get(index) instanceof JsonObject c ? c : JsonObject.EMPTY;
        if (!choices.isEmpty() && !(choices.get(index) instanceof JsonObject)) {
            Common.recordUnmapped(unmapped, path, new JsonString(ChatErrors.pyTypeName(choices.get(index))));
        }
        JsonObject message = chosen.get("message") instanceof JsonObject m ? m : JsonObject.EMPTY;

        List<Part> parts = new ArrayList<>();
        JsonValue reasoningText = ChatErrors.truthy(message.get("reasoning_content")) ? message.get("reasoning_content") : message.get("reasoning");
        if (ChatErrors.truthy(reasoningText)) parts.add(new ThinkingPart(ChatErrors.pyStr(reasoningText)));

        JsonValue content = message.get("content");
        if (content instanceof JsonString s) {
            if (!s.value().isEmpty()) parts.add(new TextPart(s.value()));
        } else if (content instanceof JsonArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                JsonValue item = arr.get(i);
                if (item instanceof JsonObject o && "text".equals(o.optString("type"))) {
                    parts.add(new TextPart(ChatErrors.truthy(o.get("text")) ? ChatErrors.pyStr(o.get("text")) : ""));
                } else {
                    Common.recordUnmapped(unmapped, path + ".message.content[" + i + "]",
                        item instanceof JsonObject o ? o.get("type") : new JsonString(ChatErrors.pyTypeName(item)));
                }
            }
        } else if (content != null && !(content instanceof JsonNull)) {
            Common.recordUnmapped(unmapped, path + ".message.content", new JsonString(ChatErrors.pyTypeName(content)));
        }

        if (ChatErrors.truthy(message.get("refusal"))) parts.add(new RefusalPart(ChatErrors.pyStr(message.get("refusal"))));

        if (message.get("tool_calls") instanceof JsonArray calls) {
            for (int i = 0; i < calls.size(); i++) {
                JsonValue callV = calls.get(i);
                if (!(callV instanceof JsonObject call)) {
                    Common.recordUnmapped(unmapped, path + ".message.tool_calls[" + i + "]", new JsonString(ChatErrors.pyTypeName(callV)));
                    continue;
                }
                String callType = ChatErrors.truthy(call.get("type")) ? ChatErrors.pyStr(call.get("type")) : "function";
                if (!callType.equals("function")) {
                    Common.recordUnmapped(unmapped, path + ".message.tool_calls[" + i + "]", new JsonString(callType));
                    continue;
                }
                JsonObject function = call.get("function") instanceof JsonObject f ? f : JsonObject.EMPTY;
                if (!ChatErrors.truthy(function.get("name"))) throw Common.unnamedToolCallError(cx.provider(), path + ".message.tool_calls[" + i + "]");
                String id = ChatErrors.truthy(call.get("id")) ? ChatErrors.pyStr(call.get("id")) : "call_" + parts.size();
                parts.add(new ToolCallPart(id, ChatErrors.pyStr(function.get("name")), Common.parseJsonObject(function.get("arguments"))));
            }
        }

        if (parts.isEmpty()) parts.add(new TextPart("")); // MAP-2: a response message is never empty.

        boolean hasTool = false;
        for (Part p : parts) if (p instanceof ToolCallPart) hasTool = true;
        Usage usage = usageFromChat(data.get("usage"));
        // choices[i].logprobs.content is the message-level token sequence; refusal logprobs stay in provider_data.
        JsonObject logprobsPayload = chosen.get("logprobs") instanceof JsonObject lp ? lp : JsonObject.EMPTY;
        List<TokenLogprob> logprobs = Common.openaiTokenLogprobs(logprobsPayload.get("content"));
        String resolvedModel = ChatErrors.truthy(data.get("model")) ? ChatErrors.pyStr(data.get("model")) : model;
        if (resolvedModel == null || resolvedModel.isEmpty()) throw ValidationException.value("the body carries no model; pass model=");
        FinishReason finish = finishReason(chosen.get("finish_reason"), hasTool, unmapped, path);
        JsonObject providerData = unmapped.isEmpty() ? data : data.with("_lm15_unmapped", new JsonArray(unmapped));
        return new Response(
            ChatErrors.truthy(data.get("id")) ? ChatErrors.pyStr(data.get("id")) : null,
            resolvedModel,
            new Message(Role.ASSISTANT, parts),
            finish,
            usage,
            logprobs.isEmpty() ? null : logprobs,
            providerData);
    }

    @Override public Response parseResponse(Request request, BuildContext cx, HttpResponse response) {
        return responseFromChatBody(cx, response.json(), request.model(), null);
    }

    @Override public Response responseFromOpenAIChat(BuildContext cx, JsonObject body, String model, Integer choice) {
        return responseFromChatBody(cx, body, model, choice);
    }

    // ─── stream parsing ───

    @Override public List<StreamEvent> parseStreamEvent(Request request, BuildContext cx, SseEvent event) {
        List<StreamEvent> out = new ArrayList<>();
        String data = event.data();
        if (data == null || data.isEmpty()) return out;
        if (data.equals("[DONE]")) {
            out.add(new StreamEndEvent(null, null, null));
            return out;
        }
        JsonValue parsed = Json.parse(data);
        if (!(parsed instanceof JsonObject payload)) return out;

        if (payload.get("error") instanceof JsonObject err) {
            String providerCode = ChatErrors.truthy(err.get("code")) ? ChatErrors.pyStr(err.get("code"))
                : ChatErrors.truthy(err.get("type")) ? ChatErrors.pyStr(err.get("type")) : "provider";
            String message = ChatErrors.truthy(err.get("message")) ? ChatErrors.pyStr(err.get("message")) : "";
            out.add(new StreamErrorEvent(ChatErrors.errorDetail(providerCode, message)));
            return out;
        }

        JsonObject choice = payload.get("choices") instanceof JsonArray choices && !choices.isEmpty() && choices.get(0) instanceof JsonObject c ? c : JsonObject.EMPTY;
        JsonObject delta = choice.get("delta") instanceof JsonObject d ? d : JsonObject.EMPTY;

        JsonValue reasoningText = ChatErrors.truthy(delta.get("reasoning_content")) ? delta.get("reasoning_content") : delta.get("reasoning");
        if (ChatErrors.truthy(reasoningText)) out.add(new StreamDeltaEvent(new ThinkingDelta(ChatErrors.pyStr(reasoningText), 0)));

        if (delta.get("content") instanceof JsonString content && !content.value().isEmpty()) {
            JsonObject logprobsPayload = choice.get("logprobs") instanceof JsonObject lp ? lp : JsonObject.EMPTY;
            out.add(new StreamDeltaEvent(new TextDelta(content.value(), 0, Common.openaiTokenLogprobs(logprobsPayload.get("content")))));
        }

        if (delta.get("tool_calls") instanceof JsonArray calls) {
            for (JsonValue callV : calls) {
                if (!(callV instanceof JsonObject call)) continue;
                JsonObject function = call.get("function") instanceof JsonObject f ? f : JsonObject.EMPTY;
                String input = ChatErrors.truthy(function.get("arguments")) ? ChatErrors.pyStr(function.get("arguments")) : "";
                int partIndex = ChatErrors.truthy(call.get("index")) ? call.get("index").asInt() : 0;
                String id = ChatErrors.truthy(call.get("id")) ? ChatErrors.pyStr(call.get("id")) : null;
                String name = ChatErrors.truthy(function.get("name")) ? ChatErrors.pyStr(function.get("name")) : null;
                out.add(new StreamDeltaEvent(new ToolCallDelta(input, partIndex, id, name)));
            }
        }

        // MAP-3 (D9): the end event's provider_data is the frame that supplied usage, verbatim, else the frame that supplied finish_reason.
        JsonValue finishRaw = choice.get("finish_reason");
        JsonValue usageData = payload.get("usage");
        if (ChatErrors.truthy(finishRaw)) {
            out.add(new StreamEndEvent(FINISH_REASON_MAP.getOrDefault(ChatErrors.pyStr(finishRaw), FinishReason.STOP),
                usageData instanceof JsonObject ? usageFromChat(usageData) : null, payload));
        } else if (usageData instanceof JsonObject) {
            // Final usage-only chunk (stream_options.include_usage).
            out.add(new StreamEndEvent(null, usageFromChat(usageData), payload));
        }
        return out;
    }

    // ─── errors ───

    @Override public LM15Error normalizeError(BuildContext cx, int status, String body) {
        return ChatErrors.normalize(cx, status, body);
    }

    /** Kept for callers that build ints by hand. */
    static JsonInt jsonInt(int v) { return JsonInt.of(v); }
}
