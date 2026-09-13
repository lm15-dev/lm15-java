package dev.lm15.compat;

import dev.lm15.json.JsonNull;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.types.ValidationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The OpenAI Responses-family compat policy (lm15-python {@code compat.py}
 * {@code OpenAIResponsesCompat} / {@code ResolvedOpenAIResponsesCompat}),
 * copied as data. A null knob inherits; {@code "auto"} means adapter
 * auto-detection; {@link #resolve()} turns the partial value into the
 * concrete serializer policy. Presets name one server's quirks each.
 */
public record OpenAIResponsesCompat(String preset, String developerRole, String maxOutputTokensField, String reasoningFormat,
                                    String toolResultName, String strictTools, String cacheControl, String commentaryPhase,
                                    String editImageField, String builtinTools, String toolResultMedia, JsonObject routing,
                                    JsonObject extensions, List<ModelOverride> modelOverrides) implements Compat {

    public static final Set<String> DEVELOPER_ROLES = Set.of("developer", "system");
    public static final Set<String> MAX_OUTPUT_TOKENS_FIELDS = Set.of("max_output_tokens", "max_completion_tokens", "max_tokens");
    public static final Set<String> REASONING_FORMATS = Set.of("none", "responses_reasoning", "reasoning_effort", "openrouter", "deepseek", "qwen", "qwen_chat_template", "zai");
    public static final Set<String> INCLUDE_OMIT = Set.of("include", "omit");
    public static final Set<String> CACHE_CONTROLS = Set.of("none", "openai", "openai_implicit", "anthropic");
    public static final Set<String> COMMENTARY_PHASES = Set.of("omit", "tag");
    public static final Set<String> EDIT_IMAGE_FIELDS = Set.of("array", "indexed");
    public static final Set<String> BUILTIN_TOOLS = Set.of("openai", "verbatim");
    public static final Set<String> TOOL_RESULT_MEDIA = Set.of("native", "images", "reject");

    /** The knobs a per-model override (or a request-level extension) may set. */
    public static final Set<String> OVERRIDABLE = Set.of("developer_role", "max_output_tokens_field", "reasoning_format", "tool_result_name",
        "strict_tools", "cache_control", "commentary_phase", "edit_image_field", "builtin_tools", "tool_result_media");

    /** A per-model override: the first entry whose prefix matches the model wins. */
    public record ModelOverride(String prefix, Map<String, String> knobs) {
        public ModelOverride {
            if (prefix == null || prefix.isEmpty()) throw ValidationException.value("model_overrides: each prefix is a non-empty string");
            LinkedHashMap<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : knobs.entrySet()) {
                if (!OVERRIDABLE.contains(e.getKey())) {
                    throw ValidationException.value("model_overrides: '" + e.getKey() + "' is not an overridable knob (" + new java.util.TreeSet<>(OVERRIDABLE) + ")");
                }
                checkKnob(e.getKey(), e.getValue());
                copy.put(e.getKey(), e.getValue());
            }
            knobs = java.util.Collections.unmodifiableMap(copy);
        }
    }

    public OpenAIResponsesCompat {
        checkKnob("developer_role", developerRole);
        checkKnob("max_output_tokens_field", maxOutputTokensField);
        checkKnob("reasoning_format", reasoningFormat);
        checkKnob("tool_result_name", toolResultName);
        checkKnob("strict_tools", strictTools);
        checkKnob("cache_control", cacheControl);
        checkKnob("commentary_phase", commentaryPhase);
        checkKnob("edit_image_field", editImageField);
        checkKnob("builtin_tools", builtinTools);
        checkKnob("tool_result_media", toolResultMedia);
        modelOverrides = modelOverrides == null ? List.of() : List.copyOf(modelOverrides);
    }

    private static Set<String> vocabulary(String knob) {
        return switch (knob) {
            case "developer_role" -> DEVELOPER_ROLES;
            case "max_output_tokens_field" -> MAX_OUTPUT_TOKENS_FIELDS;
            case "reasoning_format" -> REASONING_FORMATS;
            case "tool_result_name", "strict_tools" -> INCLUDE_OMIT;
            case "cache_control" -> CACHE_CONTROLS;
            case "commentary_phase" -> COMMENTARY_PHASES;
            case "edit_image_field" -> EDIT_IMAGE_FIELDS;
            case "builtin_tools" -> BUILTIN_TOOLS;
            case "tool_result_media" -> TOOL_RESULT_MEDIA;
            default -> throw ValidationException.value("unknown OpenAIResponsesCompat knob: " + knob);
        };
    }

    private static void checkKnob(String knob, String value) {
        if (value == null || value.equals("auto")) return;
        if (!vocabulary(knob).contains(value)) {
            throw ValidationException.value("OpenAIResponsesCompat." + knob + " must be one of " + new java.util.TreeSet<>(vocabulary(knob)) + " or 'auto', got '" + value + "'");
        }
    }

    /** The all-inheriting partial value. */
    public static final OpenAIResponsesCompat EMPTY = new OpenAIResponsesCompat(null, null, null, null, null, null, null, null, null, null, null, null, null, List.of());

    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.preset = preset; b.developerRole = developerRole; b.maxOutputTokensField = maxOutputTokensField; b.reasoningFormat = reasoningFormat;
        b.toolResultName = toolResultName; b.strictTools = strictTools; b.cacheControl = cacheControl; b.commentaryPhase = commentaryPhase;
        b.editImageField = editImageField; b.builtinTools = builtinTools; b.toolResultMedia = toolResultMedia; b.routing = routing;
        b.extensions = extensions; b.modelOverrides = new ArrayList<>(modelOverrides);
        return b;
    }

    // ─── presets (compat.py OPENAI_RESPONSES_PRESETS, copied as data) ───

    private static OpenAIResponsesCompat preset(String name, String developerRole, String maxField, String reasoningFormat, String cacheControl) {
        return new OpenAIResponsesCompat(name, developerRole, maxField, reasoningFormat, "omit", "omit", cacheControl, null, null, null, null, null, null, List.of());
    }

    public static final Map<String, OpenAIResponsesCompat> PRESETS;

    static {
        LinkedHashMap<String, OpenAIResponsesCompat> p = new LinkedHashMap<>();
        p.put("openai", preset("openai", "developer", "max_output_tokens", "responses_reasoning", "openai"));
        // MAP-10: no receipt on this door — reject until one exists
        p.put("openrouter", preset("openrouter", "developer", "max_tokens", "openrouter", "openai").toBuilder().toolResultMedia("reject").build());
        p.put("ollama", preset("ollama", "system", "max_tokens", "none", "none").toBuilder().toolResultMedia("reject").build());
        p.put("vllm", preset("vllm", "system", "max_tokens", "reasoning_effort", "none").toBuilder().toolResultMedia("reject").build());
        p.put("sglang", preset("sglang", "system", "max_tokens", "reasoning_effort", "none").toBuilder().toolResultMedia("reject").build());
        p.put("qwen", preset("qwen", "system", "max_tokens", "qwen", "none").toBuilder().toolResultMedia("reject").build());
        // MAP-10: its Chat and Messages doors both show the model [Unsupported Image] — reject
        p.put("deepseek", preset("deepseek", "system", "max_tokens", "deepseek", "none").toBuilder().toolResultMedia("reject").build());
        p.put("zai", preset("zai", "system", "max_tokens", "zai", "none").toBuilder().toolResultMedia("reject").build());
        // Meta Model API (dev.meta.ai): developer role, max_output_tokens, reasoning.effort + summary,
        // prompt_cache_key/retention honoured, the breakpoint mark never sent (openai_implicit),
        // commentary phase tagged, indexed multipart image fields, builtin names verbatim.
        p.put("meta", preset("meta", "developer", "max_output_tokens", "responses_reasoning", "openai_implicit").toBuilder()
            .commentaryPhase("tag").editImageField("indexed").builtinTools("verbatim").toolResultMedia("native").build());
        // Moonshot AI over the Responses wire (platform.kimi.ai): kimi-k3; stateless; images received, input_file is 400.
        p.put("moonshotai", preset("moonshotai", "developer", "max_output_tokens", "responses_reasoning", "openai_implicit").toBuilder()
            .builtinTools("verbatim").toolResultMedia("images").build());
        // lmstudio shares ollama's policy object.
        p.put("lmstudio", p.get("ollama").toBuilder().preset("lmstudio").build());
        PRESETS = java.util.Collections.unmodifiableMap(p);
    }

    /** Spelling aliases → canonical preset key (compat.py {@code _OPENAI_CHAT_PRESET_ALIASES}; one map serves every dialect table). */
    public static String presetKey(String name) {
        String key = name.toLowerCase().replace('-', '_').replace(' ', '_').replace('.', '_');
        return switch (key) {
            case "openai_chat", "chat", "chat_completions", "responses", "openai_responses" -> "openai";
            case "lm_studio" -> "lmstudio";
            case "dashscope_qwen" -> "qwen";
            case "z_ai" -> "zai";
            default -> key;
        };
    }

    /** The named server dialect; a ValueError for an unknown name. */
    public static OpenAIResponsesCompat preset(String name) {
        OpenAIResponsesCompat c = PRESETS.get(presetKey(name));
        if (c == null) throw ValidationException.value("unknown OpenAIResponsesCompat preset: '" + name + "'");
        return c;
    }

    // ─── layering ───

    /** None fields inherit; non-null fields (including "auto") override; extensions merge key-wise. */
    public OpenAIResponsesCompat merge(OpenAIResponsesCompat over) {
        if (over == null) return this;
        JsonObject ext;
        if (extensions == null) ext = over.extensions;
        else if (over.extensions == null) ext = extensions;
        else ext = extensions.toBuilder().putAll(over.extensions).build();
        return new OpenAIResponsesCompat(preset,
            over.developerRole != null ? over.developerRole : developerRole,
            over.maxOutputTokensField != null ? over.maxOutputTokensField : maxOutputTokensField,
            over.reasoningFormat != null ? over.reasoningFormat : reasoningFormat,
            over.toolResultName != null ? over.toolResultName : toolResultName,
            over.strictTools != null ? over.strictTools : strictTools,
            over.cacheControl != null ? over.cacheControl : cacheControl,
            over.commentaryPhase != null ? over.commentaryPhase : commentaryPhase,
            over.editImageField != null ? over.editImageField : editImageField,
            over.builtinTools != null ? over.builtinTools : builtinTools,
            over.toolResultMedia != null ? over.toolResultMedia : toolResultMedia,
            over.routing != null ? over.routing : routing,
            ext,
            modelOverrides);
    }

    /** This compat with the first matching {@code modelOverrides} entry applied (and the override list cleared). */
    public OpenAIResponsesCompat forModel(String model) {
        for (ModelOverride o : modelOverrides) {
            if (model != null && model.startsWith(o.prefix())) {
                Builder b = toBuilder();
                b.modelOverrides = new ArrayList<>();
                for (Map.Entry<String, String> e : o.knobs().entrySet()) b.knob(e.getKey(), e.getValue());
                return b.build();
            }
        }
        return this;
    }

    /** A partial value from a JSON object of knob names (the request-level hatch shape). */
    public static OpenAIResponsesCompat fromJson(JsonObject object) {
        Builder b = new Builder();
        for (String knob : OVERRIDABLE) {
            JsonValue v = object.get(knob);
            if (v == null || v instanceof JsonNull) continue;
            if (!(v instanceof JsonString s)) throw ValidationException.type("OpenAIResponsesCompat." + knob + " must be a string");
            b.knob(knob, s.value());
        }
        JsonValue routing = object.get("routing");
        if (routing != null && !(routing instanceof JsonNull)) {
            if (!(routing instanceof JsonObject o)) throw ValidationException.type("routing must be a JSON object or None");
            b.routing = o;
        }
        JsonValue ext = object.get("extensions");
        if (ext != null && !(ext instanceof JsonNull)) {
            if (!(ext instanceof JsonObject o)) throw ValidationException.type("extensions must be a JSON object or None");
            b.extensions = o;
        }
        return b.build();
    }

    /**
     * Request-level compat from {@code Config.extensions}: {@code openai_responses_compat},
     * {@code openai_compat}, {@code compat.openai_responses} or {@code compat.openai}; null when absent.
     */
    public static OpenAIResponsesCompat fromExtensions(JsonObject extensions) {
        if (extensions == null || extensions.isEmpty()) return null;
        JsonValue raw = extensions.opt("openai_responses_compat");
        if (raw == null) raw = extensions.opt("openai_compat");
        if (raw == null) {
            JsonValue compat = extensions.opt("compat");
            if (compat instanceof JsonObject c) {
                raw = c.opt("openai_responses");
                if (raw == null || dev.lm15.json.Json.isEmpty(raw)) raw = c.opt("openai");
            }
        }
        if (!(raw instanceof JsonObject o)) return null;
        return fromJson(o);
    }

    /** The concrete serializer policy: null and "auto" fall to the OpenAI defaults. */
    public Resolved resolve() {
        return new Resolved(
            pick(developerRole, "developer"), pick(maxOutputTokensField, "max_output_tokens"), pick(reasoningFormat, "responses_reasoning"),
            pick(toolResultName, "omit"), pick(strictTools, "omit"), pick(cacheControl, "openai"), pick(commentaryPhase, "omit"),
            pick(editImageField, "array"), pick(builtinTools, "openai"), pick(toolResultMedia, "native"), routing, extensions);
    }

    private static String pick(String value, String dflt) {
        return value == null || value.equals("auto") ? dflt : value;
    }

    /** The fully resolved policy (compat.py {@code ResolvedOpenAIResponsesCompat}). */
    public record Resolved(String developerRole, String maxOutputTokensField, String reasoningFormat, String toolResultName, String strictTools,
                           String cacheControl, String commentaryPhase, String editImageField, String builtinTools, String toolResultMedia,
                           JsonObject routing, JsonObject extensions) {
        public static final Resolved DEFAULT = EMPTY.resolve();
    }

    public static final class Builder {
        private String preset, developerRole, maxOutputTokensField, reasoningFormat, toolResultName, strictTools, cacheControl,
            commentaryPhase, editImageField, builtinTools, toolResultMedia;
        private JsonObject routing, extensions;
        private List<ModelOverride> modelOverrides = new ArrayList<>();

        public Builder preset(String v) { preset = v; return this; }
        public Builder developerRole(String v) { developerRole = v; return this; }
        public Builder maxOutputTokensField(String v) { maxOutputTokensField = v; return this; }
        public Builder reasoningFormat(String v) { reasoningFormat = v; return this; }
        public Builder toolResultName(String v) { toolResultName = v; return this; }
        public Builder strictTools(String v) { strictTools = v; return this; }
        public Builder cacheControl(String v) { cacheControl = v; return this; }
        public Builder commentaryPhase(String v) { commentaryPhase = v; return this; }
        public Builder editImageField(String v) { editImageField = v; return this; }
        public Builder builtinTools(String v) { builtinTools = v; return this; }
        public Builder toolResultMedia(String v) { toolResultMedia = v; return this; }
        public Builder routing(JsonObject v) { routing = v; return this; }
        public Builder extensions(JsonObject v) { extensions = v; return this; }
        public Builder modelOverride(String prefix, Map<String, String> knobs) { modelOverrides.add(new ModelOverride(prefix, knobs)); return this; }

        /** Set a knob by its wire name. */
        public Builder knob(String name, String value) {
            switch (name) {
                case "developer_role" -> developerRole = value;
                case "max_output_tokens_field" -> maxOutputTokensField = value;
                case "reasoning_format" -> reasoningFormat = value;
                case "tool_result_name" -> toolResultName = value;
                case "strict_tools" -> strictTools = value;
                case "cache_control" -> cacheControl = value;
                case "commentary_phase" -> commentaryPhase = value;
                case "edit_image_field" -> editImageField = value;
                case "builtin_tools" -> builtinTools = value;
                case "tool_result_media" -> toolResultMedia = value;
                default -> throw ValidationException.value("unknown OpenAIResponsesCompat knob: " + name);
            }
            return this;
        }

        public OpenAIResponsesCompat build() {
            return new OpenAIResponsesCompat(preset, developerRole, maxOutputTokensField, reasoningFormat, toolResultName, strictTools, cacheControl,
                commentaryPhase, editImageField, builtinTools, toolResultMedia, routing, extensions, modelOverrides);
        }
    }
}
