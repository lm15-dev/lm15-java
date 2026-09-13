package dev.lm15.compat;

import dev.lm15.json.JsonObject;
import dev.lm15.types.ReasoningEffort;
import dev.lm15.types.ValidationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The resolved compatibility policy of one Chat Completions server (the
 * reference's {@code ResolvedOpenAIChatCompat} with the partial's
 * {@code model_overrides} kept so {@link #forModel} can apply them per
 * request). The presets are data copied from {@code lm15/compat.py}
 * (playbooks/port.md rule 2): every value is pinned by a live receipt or the
 * server's own documentation, cited there; a preset changes with new
 * evidence, not with a hunch.
 *
 * <p>Knob vocabularies (the resolved form — no {@code auto}, no absent):
 * <ul>
 * <li>{@code instructionRole}: system | developer</li>
 * <li>{@code maxTokensField}: max_completion_tokens | max_tokens</li>
 * <li>{@code streamUsage}: include | omit</li>
 * <li>{@code toolResultName}: include | omit</li>
 * <li>{@code assistantAfterToolResult}: insert | omit</li>
 * <li>{@code thinkingFormat}: none | reasoning_effort | openrouter | deepseek | kimi | qwen | qwen_chat_template</li>
 * <li>{@code thinkingReplay}: native | as_text | omit</li>
 * <li>{@code assistantReasoningContent}: include_empty | omit</li>
 * <li>{@code strictTools}: include | omit</li>
 * <li>{@code builtinTools}: reject | groq</li>
 * <li>{@code toolResultMedia}: native | images | reject</li>
 * <li>{@code cacheControl}: none | openai | openai_implicit | anthropic</li>
 * <li>{@code userField}: user | user_id | safety_identifier</li>
 * <li>{@code forcedToolChoice}: send | reject</li>
 * <li>{@code jsonSchema}: send | reject</li>
 * <li>{@code reasoningEfforts}: the server's native levels when it does not refuse the others, else null</li>
 * </ul>
 */
public record OpenAIChatCompat(String preset, String instructionRole, String maxTokensField, String streamUsage,
                               String toolResultName, String assistantAfterToolResult, String thinkingFormat,
                               String thinkingReplay, String assistantReasoningContent, String strictTools,
                               String builtinTools, String toolResultMedia, String cacheControl, String userField,
                               String forcedToolChoice, String jsonSchema, List<String> reasoningEfforts,
                               JsonObject routing, JsonObject extensions, List<ModelOverride> modelOverrides) implements Compat {

    /** One per-model-family override: the first matching model-id prefix wins; the knobs are the overridable fields. */
    public record ModelOverride(String prefix, Map<String, String> knobs) {
        public ModelOverride {
            if (prefix == null || prefix.isEmpty()) throw ValidationException.value("model_overrides: each prefix is a non-empty string");
            knobs = Map.copyOf(new LinkedHashMap<>(knobs));
            for (String name : knobs.keySet()) {
                if (!OVERRIDABLE.contains(name)) throw ValidationException.value("model_overrides: '" + name + "' is not an overridable knob (" + OVERRIDABLE + ")");
            }
        }
    }

    /** The knobs a {@link ModelOverride} may set. */
    public static final Set<String> OVERRIDABLE = Set.of(
        "instruction_role", "max_tokens_field", "stream_usage", "thinking_format", "thinking_replay",
        "assistant_reasoning_content", "strict_tools", "cache_control", "user_field",
        "forced_tool_choice", "json_schema", "reasoning_efforts", "tool_result_media");

    private static final Set<String> INSTRUCTION_ROLES = Set.of("system", "developer");
    private static final Set<String> MAX_TOKENS_FIELDS = Set.of("max_completion_tokens", "max_tokens");
    private static final Set<String> INCLUDE_OMIT = Set.of("include", "omit");
    private static final Set<String> INSERT_OMIT = Set.of("insert", "omit");
    private static final Set<String> THINKING_FORMATS = Set.of("none", "reasoning_effort", "openrouter", "deepseek", "kimi", "qwen", "qwen_chat_template");
    private static final Set<String> THINKING_REPLAYS = Set.of("native", "as_text", "omit");
    private static final Set<String> REASONING_CONTENTS = Set.of("include_empty", "omit");
    private static final Set<String> BUILTIN_TOOLS = Set.of("reject", "groq");
    private static final Set<String> TOOL_RESULT_MEDIA = Set.of("native", "images", "reject");
    private static final Set<String> CACHE_CONTROLS = Set.of("none", "openai", "openai_implicit", "anthropic");
    private static final Set<String> USER_FIELDS = Set.of("user", "user_id", "safety_identifier");
    private static final Set<String> SEND_REJECT = Set.of("send", "reject");

    public OpenAIChatCompat {
        instructionRole = check(instructionRole, INSTRUCTION_ROLES, "instruction_role");
        maxTokensField = check(maxTokensField, MAX_TOKENS_FIELDS, "max_tokens_field");
        streamUsage = check(streamUsage, INCLUDE_OMIT, "stream_usage");
        toolResultName = check(toolResultName, INCLUDE_OMIT, "tool_result_name");
        assistantAfterToolResult = check(assistantAfterToolResult, INSERT_OMIT, "assistant_after_tool_result");
        thinkingFormat = check(thinkingFormat, THINKING_FORMATS, "thinking_format");
        thinkingReplay = check(thinkingReplay, THINKING_REPLAYS, "thinking_replay");
        assistantReasoningContent = check(assistantReasoningContent, REASONING_CONTENTS, "assistant_reasoning_content");
        strictTools = check(strictTools, INCLUDE_OMIT, "strict_tools");
        builtinTools = check(builtinTools, BUILTIN_TOOLS, "builtin_tools");
        toolResultMedia = check(toolResultMedia, TOOL_RESULT_MEDIA, "tool_result_media");
        cacheControl = check(cacheControl, CACHE_CONTROLS, "cache_control");
        userField = check(userField, USER_FIELDS, "user_field");
        forcedToolChoice = check(forcedToolChoice, SEND_REJECT, "forced_tool_choice");
        jsonSchema = check(jsonSchema, SEND_REJECT, "json_schema");
        if (reasoningEfforts != null) {
            reasoningEfforts = List.copyOf(reasoningEfforts);
            for (String word : reasoningEfforts) {
                if (word.equals("off") || !ReasoningEffort.wires().contains(word)) {
                    throw ValidationException.value("reasoning_efforts must be a tuple of ReasoningEffort words other than 'off'; got " + reasoningEfforts);
                }
            }
        }
        modelOverrides = modelOverrides == null ? List.of() : List.copyOf(modelOverrides);
    }

    private static String check(String value, Set<String> allowed, String field) {
        if (value == null) throw ValidationException.value(field + " must be one of " + allowed + "; got null");
        if (!allowed.contains(value)) throw ValidationException.value(field + " must be one of " + allowed + "; got '" + value + "'");
        return value;
    }

    // ─── the dialect's own behaviour (the reference's _CHAT_AUTO_DEFAULTS) ───

    /** Every knob at the dialect's own default: plain OpenAI policy, no preset name. */
    public static final OpenAIChatCompat DEFAULT = new Builder(null).build();

    /** A mutable builder over the auto defaults. */
    public static final class Builder {
        private final String preset;
        private final Map<String, String> knobs = new LinkedHashMap<>();
        private List<String> reasoningEfforts;
        private JsonObject routing;
        private JsonObject extensions;
        private final List<ModelOverride> overrides = new ArrayList<>();

        public Builder(String preset) {
            this.preset = preset;
            knobs.put("instruction_role", "system");
            knobs.put("max_tokens_field", "max_completion_tokens");
            knobs.put("stream_usage", "include");
            knobs.put("tool_result_name", "omit");
            knobs.put("assistant_after_tool_result", "omit");
            knobs.put("thinking_format", "reasoning_effort");
            knobs.put("thinking_replay", "as_text"); // decision G (2026-09-01): unsigned thinking is replayed as text, never dropped
            knobs.put("assistant_reasoning_content", "omit");
            knobs.put("strict_tools", "omit");
            knobs.put("builtin_tools", "reject");
            knobs.put("tool_result_media", "reject");
            knobs.put("cache_control", "openai");
            knobs.put("user_field", "user");
            knobs.put("forced_tool_choice", "send");
            knobs.put("json_schema", "send");
        }

        /** Set a knob by its wire-spelled name ({@code "auto"} and null keep the default). */
        public Builder knob(String name, String value) {
            if (!knobs.containsKey(name)) throw ValidationException.value("unknown OpenAIChatCompat knob: " + name);
            if (value != null && !value.equals("auto")) knobs.put(name, value);
            return this;
        }

        public Builder instructionRole(String v) { return knob("instruction_role", v); }
        public Builder maxTokensField(String v) { return knob("max_tokens_field", v); }
        public Builder streamUsage(String v) { return knob("stream_usage", v); }
        public Builder toolResultName(String v) { return knob("tool_result_name", v); }
        public Builder assistantAfterToolResult(String v) { return knob("assistant_after_tool_result", v); }
        public Builder thinkingFormat(String v) { return knob("thinking_format", v); }
        public Builder thinkingReplay(String v) { return knob("thinking_replay", v); }
        public Builder assistantReasoningContent(String v) { return knob("assistant_reasoning_content", v); }
        public Builder strictTools(String v) { return knob("strict_tools", v); }
        public Builder builtinTools(String v) { return knob("builtin_tools", v); }
        public Builder toolResultMedia(String v) { return knob("tool_result_media", v); }
        public Builder cacheControl(String v) { return knob("cache_control", v); }
        public Builder userField(String v) { return knob("user_field", v); }
        public Builder forcedToolChoice(String v) { return knob("forced_tool_choice", v); }
        public Builder jsonSchema(String v) { return knob("json_schema", v); }
        public Builder reasoningEfforts(String... words) { reasoningEfforts = words == null ? null : List.of(words); return this; }
        public Builder routing(JsonObject v) { routing = v; return this; }
        public Builder extensions(JsonObject v) { extensions = v; return this; }
        public Builder modelOverride(String prefix, Map<String, String> knobs) { overrides.add(new ModelOverride(prefix, knobs)); return this; }

        public OpenAIChatCompat build() {
            return new OpenAIChatCompat(preset, knobs.get("instruction_role"), knobs.get("max_tokens_field"), knobs.get("stream_usage"),
                knobs.get("tool_result_name"), knobs.get("assistant_after_tool_result"), knobs.get("thinking_format"),
                knobs.get("thinking_replay"), knobs.get("assistant_reasoning_content"), knobs.get("strict_tools"),
                knobs.get("builtin_tools"), knobs.get("tool_result_media"), knobs.get("cache_control"), knobs.get("user_field"),
                knobs.get("forced_tool_choice"), knobs.get("json_schema"), reasoningEfforts, routing, extensions, overrides);
        }
    }

    public Builder toBuilder() {
        Builder b = new Builder(preset)
            .instructionRole(instructionRole).maxTokensField(maxTokensField).streamUsage(streamUsage).toolResultName(toolResultName)
            .assistantAfterToolResult(assistantAfterToolResult).thinkingFormat(thinkingFormat).thinkingReplay(thinkingReplay)
            .assistantReasoningContent(assistantReasoningContent).strictTools(strictTools).builtinTools(builtinTools)
            .toolResultMedia(toolResultMedia).cacheControl(cacheControl).userField(userField).forcedToolChoice(forcedToolChoice)
            .jsonSchema(jsonSchema).routing(routing).extensions(extensions);
        b.reasoningEfforts = reasoningEfforts;
        b.overrides.addAll(modelOverrides);
        return b;
    }

    /** This compat with the first matching {@code model_overrides} entry applied (overrides cleared). */
    public OpenAIChatCompat forModel(String model) {
        if (model == null) return this;
        for (ModelOverride o : modelOverrides) {
            if (model.startsWith(o.prefix())) {
                Builder b = toBuilder();
                b.overrides.clear();
                for (Map.Entry<String, String> knob : o.knobs().entrySet()) {
                    if (knob.getKey().equals("reasoning_efforts")) b.reasoningEfforts = List.of(knob.getValue().split(","));
                    else b.knob(knob.getKey(), knob.getValue());
                }
                return b.build();
            }
        }
        return this;
    }

    // ─── presets (data; lm15/compat.py OPENAI_CHAT_PRESETS) ───

    /** Spelling aliases → canonical preset key. Every alias is permanent. */
    private static final Map<String, String> PRESET_ALIASES = Map.ofEntries(
        Map.entry("openai_chat", "openai"),
        Map.entry("chat", "openai"),
        Map.entry("chat_completions", "openai"),
        Map.entry("responses", "openai"),
        Map.entry("openai_responses", "openai"),
        Map.entry("lm_studio", "lmstudio"),
        Map.entry("dashscope_qwen", "qwen"),
        Map.entry("z_ai", "zai"));

    /** The canonical key of a preset name: lowercased, {@code -}/space/{@code .} as {@code _}, aliases applied. */
    public static String presetKey(String name) {
        String key = name.toLowerCase().replace('-', '_').replace(' ', '_').replace('.', '_');
        return PRESET_ALIASES.getOrDefault(key, key);
    }

    private static final Map<String, OpenAIChatCompat> PRESETS;

    static {
        LinkedHashMap<String, OpenAIChatCompat> p = new LinkedHashMap<>();
        p.put("openai", new Builder("openai").instructionRole("system").maxTokensField("max_completion_tokens").streamUsage("include")
            .thinkingFormat("reasoning_effort").toolResultName("omit").strictTools("omit").cacheControl("openai")
            .toolResultMedia("reject") // MAP-10: text-only tool row; gpt-5.4 received the USER image and not the tool image
            .build());
        // ollama: max_tokens, no reasoning dial on the wire.
        p.put("ollama", new Builder("ollama").instructionRole("system").maxTokensField("max_tokens").streamUsage("include")
            .thinkingFormat("none").toolResultName("omit").strictTools("omit").cacheControl("none")
            .toolResultMedia("reject") // MAP-10: openai.go builds image rows without ToolCallID; no receipt — reject until one exists
            .build());
        // LM Studio: ollama's wire policy at its own documented address (http://localhost:1234/v1).
        p.put("lmstudio", new Builder("lmstudio").instructionRole("system").maxTokensField("max_tokens").streamUsage("include")
            .thinkingFormat("none").toolResultName("omit").strictTools("omit").cacheControl("none").toolResultMedia("reject").build());
        // Groq: server-executed builtin tools (browser_search / code_interpreter, live 2026-09-01); reasoning_effort dial; no cache_control field.
        p.put("groq", new Builder("groq").instructionRole("system").maxTokensField("max_tokens").streamUsage("include")
            .thinkingFormat("reasoning_effort").toolResultName("omit").strictTools("omit").builtinTools("groq").cacheControl("none")
            .toolResultMedia("reject") // MAP-10: server 400 "messages[2].content must be a string" despite the SDK union
            .build());
        // OpenRouter: unified reasoning object; OpenAI-shaped cache_control.
        p.put("openrouter", new Builder("openrouter").instructionRole("system").maxTokensField("max_tokens").streamUsage("include")
            .thinkingFormat("openrouter").toolResultName("omit").strictTools("omit").cacheControl("openai")
            .toolResultMedia("reject") // MAP-10: schema says image; no receipt (401 during the pass) — reject until one exists
            .build());
        // xAI, pinned live 2026-09-01 against grok-4.6: max_tokens accepted, reasoning arrives as message.reasoning_content
        // (deepseek shape), stream_options.include_usage honored, no cache_control field.
        p.put("xai", new Builder("xai").instructionRole("system").maxTokensField("max_tokens").streamUsage("include")
            .thinkingFormat("deepseek").toolResultName("omit").strictTools("omit").cacheControl("none")
            .toolResultMedia("images") // MAP-10: image tool results received (grok-4.20); documents 400 "use /v1/responses"
            .build());
        p.put("vllm", new Builder("vllm").instructionRole("system").maxTokensField("max_tokens").streamUsage("include")
            .thinkingFormat("reasoning_effort").toolResultName("omit").strictTools("omit").cacheControl("none")
            .toolResultMedia("reject") // MAP-10: parser carries it; no server reachable in the pass — reject until a receipt
            .build());
        p.put("sglang", new Builder("sglang").instructionRole("system").maxTokensField("max_tokens").streamUsage("include")
            .thinkingFormat("reasoning_effort").toolResultName("omit").strictTools("omit").cacheControl("none")
            .toolResultMedia("reject") // MAP-10: schema carries it; no server reachable in the pass — reject until a receipt
            .build());
        // DeepSeek (api-docs.deepseek.com, scraped 2026-09-03): thinking={"type": enabled|disabled} + reasoning_effort; max_tokens;
        // usage in the final stream chunk; no cache_control field; thinking_replay=native + include_empty (400 without it next to tools).
        p.put("deepseek", new Builder("deepseek").instructionRole("system").maxTokensField("max_tokens").streamUsage("include")
            .thinkingFormat("deepseek").thinkingReplay("native").assistantReasoningContent("include_empty")
            .toolResultName("omit").strictTools("omit").cacheControl("none").userField("user_id")
            .toolResultMedia("reject") // MAP-10: HTTP 200 and the model sees [Unsupported Image] — silent degrade
            .build());
        p.put("qwen", new Builder("qwen").instructionRole("system").maxTokensField("max_tokens").streamUsage("include")
            .thinkingFormat("qwen").toolResultName("omit").strictTools("omit").cacheControl("none").build());
        // Amazon Bedrock's OpenAI Chat Completions door on bedrock-runtime (live 2026-09-03, receipts/2026-09-03-bedrock-chat/):
        // the door forwards tool_choice and response_format to every vendor's model; the two families that ignore them are refused per model (MAP-8).
        p.put("bedrock", new Builder("bedrock").instructionRole("system").maxTokensField("max_completion_tokens").streamUsage("include")
            .thinkingFormat("reasoning_effort").toolResultName("omit").strictTools("omit").cacheControl("none").userField("user")
            .forcedToolChoice("send").jsonSchema("send")
            .modelOverride("openai.gpt-oss", Map.of("forced_tool_choice", "reject", "json_schema", "reject"))
            .modelOverride("google.gemma", Map.of("forced_tool_choice", "reject"))
            .toolResultMedia("reject") // MAP-10: 400 validation_error on the array form
            .build());
        // Bedrock-mantle Chat Completions (live 2026-09-04): same dialect, a different host; gpt-oss ignores both knobs (HTTP 200).
        p.put("bedrock_mantle", new Builder("bedrock_mantle").instructionRole("system").maxTokensField("max_completion_tokens").streamUsage("include")
            .thinkingFormat("reasoning_effort").toolResultName("omit").strictTools("omit").cacheControl("none").userField("user")
            .forcedToolChoice("send").jsonSchema("send")
            .modelOverride("openai.gpt-oss", Map.of("forced_tool_choice", "reject", "json_schema", "reject"))
            .build());
        // Z.AI (docs.z.ai, scraped 2026-09-03): the deepseek wire shape; max_tokens; user_id; tool_choice beyond auto and json_schema
        // are accepted and ignored silently (live 2026-09-03) — refused (MAP-8).
        p.put("zai", new Builder("zai").instructionRole("system").maxTokensField("max_tokens").streamUsage("include")
            .thinkingFormat("deepseek").thinkingReplay("native").toolResultName("omit").strictTools("omit").cacheControl("none")
            .userField("user_id").forcedToolChoice("reject").jsonSchema("reject")
            .toolResultMedia("images") // MAP-10: image tool results received (glm-4.6v); a file part is 400 (code 1210)
            .build());
        // Meta Model API (dev.meta.ai, scraped 2026-09-03): `developer` instruction role; max_completion_tokens; reasoning_effort;
        // prompt_cache_key honoured, caching otherwise automatic; safety_identifier supersedes user.
        p.put("meta", new Builder("meta").instructionRole("developer").maxTokensField("max_completion_tokens").streamUsage("include")
            .thinkingFormat("reasoning_effort").toolResultName("omit").strictTools("omit").cacheControl("openai_implicit")
            .userField("safety_identifier")
            .toolResultMedia("reject") // MAP-10: 400 "did not match any supported type"; Meta's Responses door carries media
            .build());
        // Moonshot AI / Kimi (platform.kimi.ai, scraped 2026-09-03): the "kimi" shape (effort word alone; off as thinking.type=disabled);
        // reasoning_content required back verbatim (native replay); prompt_cache_key only; safety_identifier; kimi-k3 documents low|high|max
        // and answers 200 to any other word (live 2026-09-03).
        p.put("moonshotai", new Builder("moonshotai").instructionRole("system").maxTokensField("max_completion_tokens").streamUsage("include")
            .thinkingFormat("kimi").thinkingReplay("native").toolResultName("omit").strictTools("omit").cacheControl("openai_implicit")
            .userField("safety_identifier").reasoningEfforts("low", "high", "max")
            .toolResultMedia("images") // MAP-10: image tool results received (kimi-k2.6); a file part is 400
            .build());
        PRESETS = Map.copyOf(p);
    }

    /** The 15 preset names in declaration order. */
    public static List<String> presetNames() {
        return List.of("openai", "ollama", "lmstudio", "groq", "openrouter", "xai", "vllm", "sglang", "deepseek", "qwen",
            "bedrock", "bedrock_mantle", "zai", "meta", "moonshotai");
    }

    /** The named server dialect; accepts the permanent spelling aliases; a ValueError for an unknown name. */
    public static OpenAIChatCompat preset(String name) {
        OpenAIChatCompat c = PRESETS.get(presetKey(name));
        if (c == null) throw ValidationException.value("unknown OpenAIChatCompat preset: '" + name + "'");
        return c;
    }
}
