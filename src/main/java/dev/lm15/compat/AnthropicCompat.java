package dev.lm15.compat;

import dev.lm15.json.JsonObject;
import dev.lm15.types.ReasoningEffort;
import dev.lm15.types.ValidationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The resolved Anthropic Messages compatibility policy (the reference's
 * {@code ResolvedAnthropicCompat}, {@code lm15/compat.py} § Anthropic
 * Messages compatibility). The wire has few dialects, so the policy is
 * small: it exists for servers that speak the format but not all of it —
 * DeepSeek, Meta and Moonshot over their Anthropic endpoints.
 *
 * <p>Every knob is resolved to a concrete word; a {@link Partial} holds the
 * user's overrides ({@code null} = inherit, {@code "auto"} = the dialect's own
 * behaviour) and {@link #resolve(Partial)} fills the defaults. Presets are
 * the reference's table, copied as data (playbooks/port.md rule 2).
 *
 * <ul>
 *   <li>{@code thinkingFormat}: {@code anthropic} (the model-class rule, MAP-7 rule 10),
 *       {@code deepseek} ({@code thinking.type=enabled|disabled} + {@code output_config.effort}, off MUST be sent),
 *       {@code adaptive} (every model adaptive; off is {@code thinking.type=disabled}),
 *       {@code effort} ({@code output_config.effort} alone; off is {@code thinking.type=disabled}).</li>
 *   <li>{@code thinkingReplay}: {@code signed} (only a signed block goes back as {@code thinking})
 *       or {@code unsigned} (an unsigned block goes back as {@code thinking} without a signature).</li>
 *   <li>{@code cacheControl}: {@code anthropic} (marks are placed) or {@code none} (the server caches implicitly).</li>
 *   <li>{@code structuredOutput}, {@code parallelToolCalls}, {@code samplingParams}: {@code send} or {@code reject}.</li>
 *   <li>{@code toolResultMedia}: {@code native}, {@code images} or {@code reject} (MAP-10).</li>
 *   <li>{@code reasoningEfforts}: the allowlist of effort words the server honours, or null for any.</li>
 *   <li>{@code modelPrefixes}: a model id must start with one of them, or null for any.</li>
 * </ul>
 */
public record AnthropicCompat(String thinkingFormat, String thinkingReplay, String cacheControl, String structuredOutput,
                              String parallelToolCalls, String samplingParams, String toolResultMedia,
                              List<String> reasoningEfforts, List<String> modelPrefixes, JsonObject extensions,
                              String preset) implements Compat {

    public static final Set<String> THINKING_FORMATS = Set.of("auto", "anthropic", "deepseek", "adaptive", "effort");
    public static final Set<String> THINKING_REPLAYS = Set.of("auto", "signed", "unsigned");
    public static final Set<String> CACHE_CONTROLS = Set.of("auto", "anthropic", "none");
    public static final Set<String> SEND_REJECT = Set.of("auto", "send", "reject");
    public static final Set<String> TOOL_RESULT_MEDIA = Set.of("auto", "native", "images", "reject");

    public AnthropicCompat {
        reasoningEfforts = reasoningEfforts == null ? null : List.copyOf(reasoningEfforts);
        modelPrefixes = modelPrefixes == null ? null : List.copyOf(modelPrefixes);
    }

    /** The user-facing partial policy: {@code null} inherits, {@code "auto"} is the dialect's own behaviour. */
    public record Partial(String thinkingFormat, String thinkingReplay, String cacheControl, String structuredOutput,
                          String parallelToolCalls, String samplingParams, String toolResultMedia,
                          List<String> reasoningEfforts, List<String> modelPrefixes, JsonObject extensions) {
        public static final Partial EMPTY = new Partial(null, null, null, null, null, null, null, null, null, null);

        public Partial {
            check(thinkingFormat, THINKING_FORMATS, "thinking_format");
            check(thinkingReplay, THINKING_REPLAYS, "thinking_replay");
            check(cacheControl, CACHE_CONTROLS, "cache_control");
            check(structuredOutput, SEND_REJECT, "structured_output");
            check(parallelToolCalls, SEND_REJECT, "parallel_tool_calls");
            check(samplingParams, SEND_REJECT, "sampling_params");
            check(toolResultMedia, TOOL_RESULT_MEDIA, "tool_result_media");
            if (reasoningEfforts != null) {
                for (String word : reasoningEfforts) {
                    boolean known = false;
                    for (ReasoningEffort e : ReasoningEffort.values()) if (e.wire().equals(word)) known = true;
                    if (!known || word.equals("off")) {
                        throw ValidationException.value("reasoning_efforts must be a tuple of ReasoningEffort words other than 'off'; got " + reasoningEfforts);
                    }
                }
                reasoningEfforts = List.copyOf(reasoningEfforts);
            }
            if (modelPrefixes != null) {
                if (modelPrefixes.isEmpty()) throw ValidationException.value("model_prefixes must be None or a non-empty tuple");
                modelPrefixes = List.copyOf(modelPrefixes);
            }
        }

        private static void check(String value, Set<String> allowed, String field) {
            if (value != null && !allowed.contains(value)) {
                throw ValidationException.value(field + " must be one of " + allowed + " or None; got '" + value + "'");
            }
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private String thinkingFormat, thinkingReplay, cacheControl, structuredOutput, parallelToolCalls, samplingParams, toolResultMedia;
            private List<String> reasoningEfforts, modelPrefixes;
            private JsonObject extensions;

            public Builder thinkingFormat(String v) { thinkingFormat = v; return this; }
            public Builder thinkingReplay(String v) { thinkingReplay = v; return this; }
            public Builder cacheControl(String v) { cacheControl = v; return this; }
            public Builder structuredOutput(String v) { structuredOutput = v; return this; }
            public Builder parallelToolCalls(String v) { parallelToolCalls = v; return this; }
            public Builder samplingParams(String v) { samplingParams = v; return this; }
            public Builder toolResultMedia(String v) { toolResultMedia = v; return this; }
            public Builder reasoningEfforts(String... v) { reasoningEfforts = List.of(v); return this; }
            public Builder modelPrefixes(String... v) { modelPrefixes = List.of(v); return this; }
            public Builder extensions(JsonObject v) { extensions = v; return this; }
            public Partial build() {
                return new Partial(thinkingFormat, thinkingReplay, cacheControl, structuredOutput, parallelToolCalls, samplingParams,
                    toolResultMedia, reasoningEfforts, modelPrefixes, extensions);
            }
        }
    }

    // ─── the preset table (lm15/compat.py ANTHROPIC_PRESETS) ───

    /** The partial presets, keyed by canonical name. */
    public static final Map<String, Partial> PRESETS;

    static {
        LinkedHashMap<String, Partial> table = new LinkedHashMap<>();
        table.put("anthropic", Partial.EMPTY);
        // DeepSeek over the Anthropic wire (live 2026-09-03): thinking={"type":
        // enabled|disabled} + output_config.effort, budget_tokens ignored;
        // cache_control ignored; output_config.format accepted and ignored;
        // disable_parallel_tool_use ignored; claude-* names silently remapped.
        table.put("deepseek", Partial.builder()
            .thinkingFormat("deepseek").cacheControl("none").structuredOutput("reject").parallelToolCalls("reject")
            .modelPrefixes("deepseek-")
            .toolResultMedia("reject") // MAP-10: 200 and the model sees [Unsupported Image] — silent degrade
            .build());
        // Meta Model API over the Anthropic wire (protocols--messages.md, 2026-09-03):
        // thinking={"type": "adaptive"} + output_config.effort; `disabled` is 400;
        // caching automatic; output_config.format and disable_parallel_tool_use honoured.
        table.put("meta", Partial.builder()
            .thinkingFormat("adaptive").cacheControl("none").structuredOutput("send").parallelToolCalls("send")
            .toolResultMedia("native") // MAP-10: image/mixed/pair/pdf received (muse-spark-1.3)
            .build());
        // Moonshot AI over the Anthropic wire (messages--create.md, live 2026-09-03):
        // kimi-k3 only, always reasons; output_config.effort low|high|max is the
        // whole dial; thinking blocks come back with signature "" and replay
        // unsigned; temperature and top_k swallowed silently; no parallel knob.
        table.put("moonshotai", Partial.builder()
            .thinkingFormat("effort").thinkingReplay("unsigned").cacheControl("none").structuredOutput("send")
            .parallelToolCalls("reject").samplingParams("reject").reasoningEfforts("low", "high", "max")
            .modelPrefixes("kimi-")
            .toolResultMedia("images") // MAP-10: images received (kimi-k3); a document block is 400
            .build());
        PRESETS = Map.copyOf(table);
    }

    /** The reference's {@code _preset_key}: lower-cased, {@code - . space} as underscores, then the spelling aliases. */
    public static String presetKey(String name) {
        String key = name.toLowerCase().replace('-', '_').replace(' ', '_').replace('.', '_');
        return PresetAddresses.canonicalPreset(key);
    }

    /** The partial preset by name (aliases accepted); a ValueError for an unknown name. */
    public static Partial partialPreset(String name) {
        Partial p = PRESETS.get(presetKey(name));
        if (p == null) throw ValidationException.value("unknown AnthropicCompat preset: '" + name + "'");
        return p;
    }

    /** The resolved preset by name. */
    public static AnthropicCompat preset(String name) {
        return resolve(partialPreset(name), presetKey(name));
    }

    /** The dialect's own behaviour on every knob (the reference's {@code ResolvedAnthropicCompat()}). */
    public static final AnthropicCompat DEFAULT = resolve(Partial.EMPTY, null);

    private static String pick(String value, String fallback) {
        return value == null || value.equals("auto") ? fallback : value;
    }

    /** The reference's {@code resolve_anthropic_compat}: {@code null} and {@code "auto"} become the dialect default. */
    public static AnthropicCompat resolve(Partial partial, String preset) {
        return new AnthropicCompat(
            pick(partial.thinkingFormat(), "anthropic"),
            pick(partial.thinkingReplay(), "signed"),
            pick(partial.cacheControl(), "anthropic"),
            pick(partial.structuredOutput(), "send"),
            pick(partial.parallelToolCalls(), "send"),
            pick(partial.samplingParams(), "send"),
            // tool_result.content takes image and document blocks (anthropic/claude-code exact 2026-09-07).
            pick(partial.toolResultMedia(), "native"),
            partial.reasoningEfforts(), partial.modelPrefixes(), partial.extensions(), preset);
    }

    public static AnthropicCompat resolve(Partial partial) { return resolve(partial, null); }
}
