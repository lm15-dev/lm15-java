package dev.lm15.dialects.anthropic;

import dev.lm15.compat.AnthropicCompat;
import dev.lm15.dialects.Common;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.errors.UnsupportedModelError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.types.*;
import dev.lm15.wire.BuildContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Messages request body in the reference's key order
 * ({@code lm15/providers/anthropic.py} {@code _payload}, {@code _part},
 * {@code _message}, {@code _tool_choice_payload}, {@code _headers}). Every
 * refusal (MAP-5..8, MAP-10) raises here, before any wire, with the
 * binding's provider string.
 */
final class AnthropicBody {
    private AnthropicBody() {}

    static final String API_VERSION = "2023-06-01";
    static final String BETA_HEADER = "anthropic-beta";
    static final String CODE_EXECUTION_BETA = "code-execution-2025-05-22";

    /** Canonical builtin tool name → Anthropic tool type. */
    static final Map<String, String> BUILTIN_TOOL_TYPES = Map.of(
        "web_search", "web_search_20250305",
        "code_execution", "code_execution_20250522");

    static final int DEFAULT_VISIBLE_TOKENS = 1024;

    /** MAP-7 rule 10: the models that take {@code thinking: {type: adaptive}} + {@code output_config.effort}. */
    static final List<String> ADAPTIVE_CLASS_MARKERS = List.of(
        "sonnet-5", "opus-5", "sonnet-4-6", "opus-4-6", "opus-4-7", "opus-4-8", "fable", "mythos", "haiku-5");

    static boolean adaptiveClass(String model) {
        String lowered = model.toLowerCase();
        for (String marker : ADAPTIVE_CLASS_MARKERS) if (lowered.contains(marker)) return true;
        return false;
    }

    static UnsupportedFeatureError unsupported(String provider, String message) {
        return new UnsupportedFeatureError(message, ErrorMeta.of(provider));
    }

    // ─── headers ───

    /**
     * {@code anthropic-version}, {@code content-type}, the policy's static
     * headers (its {@code anthropic-beta} values joined with the dialect's
     * own betas — {@code code-execution-2025-05-22} when a {@code code_execution}
     * builtin is offered). Never the credential: {@code Wire.emit} adds it.
     */
    static List<Map.Entry<String, String>> headers(Request request, BuildContext cx, boolean contentType) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        headers.add(Map.entry("anthropic-version", API_VERSION));
        if (contentType) headers.add(Map.entry("content-type", "application/json"));
        List<String> betas = new ArrayList<>();
        for (Map.Entry<String, String> h : cx.policy().headers()) {
            if (h.getKey().equalsIgnoreCase(BETA_HEADER)) {
                for (String b : h.getValue().split(",")) if (!b.isEmpty()) betas.add(b);
            } else {
                headers.add(Map.entry(h.getKey(), h.getValue()));
            }
        }
        if (request != null) {
            for (Tool tool : request.tools()) {
                if (tool instanceof BuiltinTool b && b.name().equals("code_execution")) {
                    betas.add(CODE_EXECUTION_BETA);
                    break;
                }
            }
        }
        if (!betas.isEmpty()) headers.add(Map.entry(BETA_HEADER, String.join(",", betas)));
        return headers;
    }

    // ─── parts ───

    static JsonObject part(Part part, BuildContext cx, AnthropicCompat compat) {
        String provider = cx.provider();
        switch (part) {
            case TextPart t -> { return Json.obj("type", "text", "text", t.text()); }
            case ImagePart img -> { return Json.obj("type", "image", "source", Common.anthropicSource(img)); }
            case DocumentPart doc -> { return Json.obj("type", "document", "source", Common.anthropicSource(doc)); }
            case ToolCallPart call -> { return Json.obj("type", "tool_use", "id", call.id(), "name", call.name(), "input", call.input()); }
            case ToolResultPart result -> {
                Common.checkToolResultMedia(provider, result, compat.toolResultMedia(), "a tool_result block");
                List<JsonObject> blocks = new ArrayList<>();
                for (Part p : result.content()) blocks.add(toolResultContent(p, provider));
                JsonBuilder out = new JsonBuilder().put("type", "tool_result").put("tool_use_id", result.id());
                if (!blocks.isEmpty()) {
                    // Anthropic accepts either a string or content blocks. Blocks
                    // preserve image/document tool outputs when present.
                    if (blocks.size() == 1 && "text".equals(blocks.get(0).optString("type"))) {
                        out.put("content", blocks.get(0).get("text"));
                    } else {
                        out.put("content", new JsonArray(new ArrayList<>(blocks)));
                    }
                }
                if (result.isError()) out.put("is_error", true);
                return out.build();
            }
            case ThinkingPart th -> {
                JsonObject redacted = ContinuationState.find(th.continuation(), "anthropic", "redacted_thinking");
                if (redacted != null) return new JsonBuilder().put("type", "redacted_thinking").putAll(redacted).build();
                JsonObject signature = ContinuationState.find(th.continuation(), "anthropic", "thinking_signature");
                if (signature != null && signature.opt("signature") != null && !Json.isEmpty(signature.opt("signature"))) {
                    return Json.obj("type", "thinking", "thinking", th.text(), "signature", signature.get("signature"));
                }
                if (compat.thinkingReplay().equals("unsigned") && !th.text().isEmpty()) {
                    // The server signs nothing (`signature: ""`) and takes the block
                    // back unsigned (Moonshot, live 2026-09-03); a text replay would
                    // turn the reasoning into a spoken turn.
                    return Json.obj("type", "thinking", "thinking", th.text());
                }
                return Json.obj("type", "text", "text", th.text());
            }
            case RefusalPart r -> { return Json.obj("type", "text", "text", r.text()); }
            case CitationPart c -> { return Json.obj("type", "text", "text", Common.partsToText(List.of(c))); }
            default -> throw unsupported(provider, provider + ": " + part.type().wire() + " has no native Anthropic message block (MAP-10)");
        }
    }

    private static JsonObject toolResultContent(Part part, String provider) {
        switch (part) {
            case TextPart t -> { return Json.obj("type", "text", "text", t.text()); }
            case ImagePart img -> { return Json.obj("type", "image", "source", Common.anthropicSource(img)); }
            case DocumentPart doc -> { return Json.obj("type", "document", "source", Common.anthropicSource(doc)); }
            default -> {
                if (Common.MEDIA_KINDS.contains(part.type())) {
                    // ToolResultBlockParam takes text, image and document blocks only.
                    throw unsupported(provider, provider + ": a " + part.type().wire()
                        + " part cannot reach a tool_result block (text, image and document only; MAP-10)");
                }
                return Json.obj("type", "text", "text", Common.partsToText(List.of(part), provider, "a text-only wire field"));
            }
        }
    }

    private static List<JsonObject> messageBlocks(Message msg, BuildContext cx, AnthropicCompat compat) {
        List<JsonObject> blocks = new ArrayList<>();
        for (Part p : msg.parts()) blocks.add(part(p, cx, compat));
        if (msg.role() == Role.DEVELOPER) {
            String text = Common.partsToText(msg.parts());
            return List.of(Json.obj("type", "text", "text", "[developer]\n" + text));
        }
        return blocks;
    }

    private static JsonObject message(Role role, List<JsonObject> blocks) {
        return Json.obj("role", role == Role.ASSISTANT ? "assistant" : "user", "content", new JsonArray(new ArrayList<>(blocks)));
    }

    // ─── tool choice ───

    static JsonObject toolChoice(Request request, String provider) {
        ToolChoice tc = request.config().toolChoice();
        if (tc == null) return null;
        JsonBuilder payload = new JsonBuilder();
        if (tc.mode() == ToolChoiceMode.NONE) {
            payload.put("type", "none");
        } else if (!tc.allowed().isEmpty()) {
            // {"type": "tool", "name": ...} forces client tools AND server tools
            // (verified live 2026-09-01 with web_search). Builtin names ride the
            // same form because the canonical name is what goes on the wire.
            Set<String> declared = new HashSet<>(request.toolNames());
            if (tc.allowed().size() == 1 && tc.mode() == ToolChoiceMode.REQUIRED) {
                payload.put("type", "tool").put("name", tc.allowed().get(0));
            } else if (new HashSet<>(tc.allowed()).equals(declared)) {
                // Allowing every declared tool is no restriction at all.
                payload.put("type", tc.mode() == ToolChoiceMode.REQUIRED ? "any" : "auto");
            } else {
                // A proper-subset allowlist has no Anthropic wire form. Degrading
                // to any/auto would let the model call excluded tools — raise,
                // never silently widen the caller's policy.
                throw unsupported(provider, "anthropic: tool_choice.allowed subsets are not supported — "
                    + "the Messages API can force one named tool or allow all declared tools, but cannot restrict to a subset. "
                    + "Send only the allowed tools in Request.tools instead");
            }
        } else if (tc.mode() == ToolChoiceMode.REQUIRED) {
            payload.put("type", "any");
        } else {
            payload.put("type", "auto");
        }
        if (Boolean.FALSE.equals(tc.parallel()) && !"none".equals(payload.get("type").asString())) {
            payload.put("disable_parallel_tool_use", true);
        }
        return payload.build();
    }

    // ─── reasoning ───

    /** Manual class only: the budget on the wire (MAP-7 rules 3 and 5). */
    private static Integer thinkingBudget(Request request) {
        Reasoning reasoning = request.config().reasoning();
        if (reasoning == null || reasoning.isOff()) return null;
        if (reasoning.thinkingBudget() != null) return reasoning.thinkingBudget();
        return Common.EFFORT_THINKING_BUDGETS.get(reasoning.effort());
    }

    /**
     * Manual class: max_tokens includes thinking, so the wire ceiling is the
     * budget plus the visible cap. Adaptive class (budget null): Config.max_tokens
     * is the total ceiling — provider semantics.
     */
    private static int maxTokens(Request request, Integer thinkingBudget) {
        int visible = request.config().maxTokens() != null ? request.config().maxTokens() : DEFAULT_VISIBLE_TOKENS;
        return thinkingBudget == null ? visible : thinkingBudget + visible;
    }

    private static JsonObject outputConfig(JsonObject formatConfig, String provider) {
        // The Messages API has no any-JSON mode (HTTP 400 without an object
        // schema, live 2026-09-02), so `json_object` RAISES. `strict` is
        // satisfied (always constrained); `name` is a label with no slot.
        if ("json_object".equals(formatConfig.optString("type"))) {
            throw unsupported(provider, "anthropic: response_format json_object is not supported — the Messages API has no "
                + "any-JSON mode; give a json_schema (objects need additionalProperties: false)");
        }
        return Json.obj("format", Json.obj("type", "json_schema", "schema", formatConfig.get("schema")));
    }

    // ─── the body ───

    static JsonObject payload(Request request, boolean stream, BuildContext cx, AnthropicCompat compat) {
        String provider = cx.provider();
        String model = cx.model();
        if (compat.modelPrefixes() != null) {
            boolean ok = false;
            for (String prefix : compat.modelPrefixes()) if (model.startsWith(prefix)) ok = true;
            if (!ok) {
                // The server maps foreign model names onto its own models without
                // saying so (DeepSeek: claude-opus* → deepseek-v4-pro, live 2026-09-03).
                throw new UnsupportedModelError(provider + ": model '" + model + "' is not one this endpoint serves as typed "
                    + "(expected a prefix in " + compat.modelPrefixes() + "); it would be silently substituted by another model. "
                    + "Name the model you actually want.", ErrorMeta.of(provider));
            }
        }
        Config config = request.config();
        CacheConfig cacheCfg = config.cache();
        // cache_control="none": the server ignores marks and caches implicitly;
        // nothing is placed and an explicit CacheConfig is not an error.
        boolean marks = compat.cacheControl().equals("anthropic");
        boolean useCache = cacheCfg != null && cacheCfg.mode() != CacheMode.OFF && marks;
        boolean longCache = cacheCfg != null && cacheCfg.retention() == CacheRetention.LONG && marks;

        List<Role> roles = new ArrayList<>();
        List<List<JsonObject>> contents = new ArrayList<>();
        for (Message m : request.messages()) {
            roles.add(m.role());
            contents.add(new ArrayList<>(messageBlocks(m, cx, compat)));
        }

        // MAP-6 marks. prefix_until_index=N and prefix="history" (N = last
        // message) put cache_control on the last block of message N; the
        // explicit block form rather than the top-level automatic marker.
        // prefix="stable" (and plain auto) mark the system block below.
        if (useCache) {
            if (cacheCfg.key() != null) {
                throw unsupported(provider, "anthropic: cache.key is not supported — the Messages API has no "
                    + "cache affinity key (OpenAI's prompt_cache_key); marks on blocks are the mechanism (prefix / prefix_until_index)");
            }
            if (cacheCfg.resource() != null) {
                throw unsupported(provider, "anthropic: cache.resource is not supported — the Messages API has no "
                    + "stored-cache tier; it caches by marks on blocks");
            }
            Integer idx = null;
            if (cacheCfg.prefixUntilIndex() != null) idx = Math.min(cacheCfg.prefixUntilIndex(), contents.size() - 1);
            else if (cacheCfg.prefix() == CachePrefix.HISTORY) idx = contents.size() - 1;
            if (idx != null && idx >= 0 && !contents.get(idx).isEmpty()) {
                List<JsonObject> blocks = contents.get(idx);
                JsonObject last = blocks.get(blocks.size() - 1);
                if (!last.has("cache_control")) blocks.set(blocks.size() - 1, last.with("cache_control", cacheMarker(longCache)));
            }
        }
        List<JsonValue> messages = new ArrayList<>();
        for (int i = 0; i < roles.size(); i++) messages.add(message(roles.get(i), contents.get(i)));

        Reasoning reasoning = config.reasoning();
        boolean on = reasoning != null && !reasoning.isOff();
        boolean deepseekThinking = compat.thinkingFormat().equals("deepseek");
        // "adaptive": every model on this server is the adaptive class (Meta).
        boolean alwaysAdaptive = compat.thinkingFormat().equals("adaptive");
        // "effort": no `thinking` object exists on this server (Moonshot).
        boolean effortOnly = compat.thinkingFormat().equals("effort");
        boolean adaptive = on && (deepseekThinking || alwaysAdaptive || effortOnly || adaptiveClass(model));
        if (on) {
            if (compat.reasoningEfforts() != null && !compat.reasoningEfforts().contains(reasoning.effort().wire())) {
                // MAP-7 rule 2: a word with no native level raises here when the
                // server would not refuse it (Moonshot answered 200 to `bogus`).
                throw unsupported(provider, provider + ": reasoning.effort='" + reasoning.effort().wire() + "' has no level on this server "
                    + "(it accepts " + String.join(", ", compat.reasoningEfforts()) + ") and would be accepted silently");
            }
            if (reasoning.summary() == ReasoningSummary.CONCISE || reasoning.summary() == ReasoningSummary.DETAILED) {
                throw unsupported(provider, "anthropic: reasoning.summary='" + reasoning.summary().wire() + "' is an OpenAI detail level; "
                    + "the Messages API returns thinking blocks whenever thinking runs (use 'auto' or None)");
            }
            if (adaptive) {
                if (reasoning.thinkingBudget() != null) {
                    String why = deepseekThinking ? "this server ignores budget_tokens (a silent no-op); effort is the dial"
                        : alwaysAdaptive ? "this server accepts budget_tokens without translating it (a silent no-op); effort is the dial (protocols--messages.md)"
                        : "this model class takes thinking.type 'adaptive' with output_config.effort; budget_tokens is rejected by the API (live 2026-09-02)";
                    throw unsupported(provider, provider + ": reasoning.thinking_budget is not supported on " + model + " — " + why);
                }
                // MAP-7: the word goes verbatim on the always-adaptive server; it
                // answers an unsupported level with a 400 of its own.
                if (reasoning.effort() == ReasoningEffort.MINIMAL && !(deepseekThinking || alwaysAdaptive || effortOnly)) {
                    throw unsupported(provider, "anthropic: reasoning.effort='minimal' has no level on this model class "
                        + "(output_config.effort is low|medium|high|xhigh|max); 'low' is the floor");
                }
            }
        }
        Integer thinkingBudget = adaptive ? null : thinkingBudget(request);

        JsonBuilder payload = new JsonBuilder()
            .put("model", model)
            .put("messages", new JsonArray(messages))
            .put("stream", stream)
            .put("max_tokens", maxTokens(request, thinkingBudget));

        if (request.system() != null) {
            String systemText = request.system().isText() ? request.system().text() : Common.partsToText(request.system().parts());
            if (useCache) {
                payload.put("system", Json.arr(Json.obj("type", "text", "text", systemText, "cache_control", cacheMarker(longCache))));
            } else {
                payload.put("system", systemText);
            }
        }
        if (compat.samplingParams().equals("reject")) {
            String[] names = {"temperature", "top_p", "top_k"};
            Object[] values = {config.temperature(), config.topP(), config.topK()};
            for (int i = 0; i < names.length; i++) {
                if (values[i] != null) {
                    // The server documents none of these and swallows them silently
                    // (Moonshot, live 2026-09-03).
                    throw unsupported(provider, provider + ": config." + names[i] + " is silently ignored by this server "
                        + "(the model's sampling is fixed); omit it");
                }
            }
        }
        payload.putIfPresent("temperature", config.temperature());
        payload.putIfPresent("top_p", config.topP());
        payload.putIfPresent("top_k", config.topK());
        if (!config.stop().isEmpty()) payload.put("stop_sequences", config.stop());
        if (!request.tools().isEmpty()) {
            List<JsonValue> tools = new ArrayList<>();
            for (Tool tool : request.tools()) {
                if (tool instanceof FunctionTool f) {
                    tools.add(new JsonBuilder().put("name", f.name()).put("description", (Object) f.description()).put("input_schema", f.parameters()).build());
                } else if (tool instanceof BuiltinTool b) {
                    JsonBuilder out = new JsonBuilder().put("type", BUILTIN_TOOL_TYPES.getOrDefault(b.name(), b.name())).put("name", b.name());
                    if (b.config() != null) out.putAll(b.config());
                    tools.add(out.build());
                }
            }
            payload.put("tools", new JsonArray(tools));
        }
        JsonObject toolChoice = toolChoice(request, provider);
        if (toolChoice != null) {
            ToolChoice tc = config.toolChoice();
            if (compat.parallelToolCalls().equals("reject") && tc != null && tc.parallel() != null) {
                // disable_parallel_tool_use is documented as ignored; a silent no-op is refused (MAP-8 §2).
                throw unsupported(provider, provider + ": tool_choice.parallel is silently ignored by this server "
                    + "(disable_parallel_tool_use is not applied); omit it");
            }
            payload.put("tool_choice", toolChoice);
        }
        if (deepseekThinking) {
            // DeepSeek over the Anthropic wire: thinking is ON by default, so
            // absence is not off — an explicit off must reach the wire.
            if (reasoning != null && reasoning.isOff()) {
                payload.put("thinking", Json.obj("type", "disabled"));
            } else if (reasoning != null) {
                payload.put("thinking", Json.obj("type", "enabled"));
                payload.put("output_config", Json.obj("effort", reasoning.effort().wire()));
            }
        } else if (effortOnly) {
            // Moonshot over the Anthropic wire: no thinking object is documented;
            // output_config.effort alone. Off goes out as thinking.type=disabled.
            if (reasoning != null && reasoning.isOff()) {
                payload.put("thinking", Json.obj("type", "disabled"));
            } else if (reasoning != null) {
                payload.put("output_config", Json.obj("effort", reasoning.effort().wire()));
            }
        } else if (alwaysAdaptive && reasoning != null && reasoning.isOff()) {
            // The server reasons by default and cannot stop (Meta): explicit off
            // must reach the wire so the server refuses it loudly (HTTP 400).
            payload.put("thinking", Json.obj("type", "disabled"));
        } else if (adaptive) {
            // MAP-7 rule 2 on the adaptive class: the model decides when to think; effort steers depth.
            payload.put("thinking", Json.obj("type", "adaptive"));
            payload.put("output_config", Json.obj("effort", reasoning.effort().wire()));
        } else if (thinkingBudget != null) {
            payload.put("thinking", Json.obj("type", "enabled", "budget_tokens", thinkingBudget));
        }
        if (config.responseFormat() != null) {
            if (compat.structuredOutput().equals("reject")) {
                // The server accepts output_config.format and ignores the schema
                // (DeepSeek, live 2026-09-03). Silent, so refuse before the wire.
                throw unsupported(provider, provider + ": response_format is silently ignored by this server "
                    + "(output_config.format is accepted and not applied); describe the shape in the prompt");
            }
            JsonObject outputConfig = outputConfig(config.responseFormat(), provider);
            JsonValue existing = payload.get("output_config");
            JsonBuilder merged = existing instanceof JsonObject o ? new JsonBuilder(o) : new JsonBuilder();
            payload.put("output_config", merged.putAll(outputConfig).build());
        }
        // Promoted cross-provider knobs: user_id rides metadata.user_id; store
        // and logprobs have no Anthropic wire field — raise, never silently drop.
        payload.putIfPresent("service_tier", config.serviceTier());
        if (config.userId() != null) payload.put("metadata", Json.obj("user_id", config.userId()));
        if (config.store() != null) {
            throw unsupported(provider, "anthropic: config.store is not supported — the Messages API has no "
                + "response-storage opt-out field (OpenAI and Gemini carry it)");
        }
        if (config.logprobs() != null) {
            throw unsupported(provider, "anthropic: config.logprobs is not supported — the Messages API "
                + "does not expose token log probabilities (OpenAI and Gemini carry them)");
        }
        if (config.extensions() != null) {
            for (Map.Entry<String, JsonValue> e : config.extensions().members().entrySet()) {
                if (!e.getKey().equals("prompt_caching")) payload.put(e.getKey(), e.getValue());
            }
        }
        String prefix = cx.policy().systemPrefix();
        if (prefix != null && !prefix.isEmpty()) {
            // The access path requires this text first in the system prompt
            // (Claude Code's backend checks for it); the caller's system follows,
            // cache markers and all.
            JsonObject prefixBlock = Json.obj("type", "text", "text", prefix);
            JsonValue existing = payload.get("system");
            List<JsonValue> system = new ArrayList<>();
            system.add(prefixBlock);
            if (existing instanceof JsonArray a) {
                for (JsonValue v : a) system.add(v);
            } else if (existing != null && !(existing instanceof dev.lm15.json.JsonNull)) {
                system.add(Json.obj("type", "text", "text", existing instanceof JsonString s ? s.value() : existing.toJson()));
            }
            payload.put("system", new JsonArray(system));
        }
        return payload.build();
    }

    private static JsonObject cacheMarker(boolean longCache) {
        return longCache ? Json.obj("type", "ephemeral", "ttl", "1h") : Json.obj("type", "ephemeral");
    }
}
