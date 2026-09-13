package dev.lm15.types;

import dev.lm15.json.JsonBool;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Generation parameters (spec/types.md § Config). Universal fields are typed;
 * provider-specific settings go in {@code extensions}. Build one with
 * {@link #builder()}; {@link #DEFAULT} is the all-absent config that
 * serializes to {@code {}}.
 */
public record Config(Integer maxTokens, Double temperature, Double topP, Integer topK, List<String> stop,
                     JsonObject responseFormat, ToolChoice toolChoice, Reasoning reasoning, CacheConfig cache,
                     String serviceTier, String userId, Boolean store, Integer logprobs, JsonObject extensions) {

    public static final Config DEFAULT = new Config(null, null, null, null, List.of(), null, null, null, null, null, null, null, null, null);

    public Config {
        Check.positive(maxTokens, "max_tokens");
        Check.positive(topK, "top_k");
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0)) throw ValidationException.value("temperature must be >= 0");
        if (topP != null && (!Double.isFinite(topP) || topP < 0 || topP > 1)) throw ValidationException.value("top_p must be in [0, 1]");
        stop = Check.nonEmptyStrings(stop, "Config.stop", "stop must contain non-empty strings");
        Check.optNonEmpty(serviceTier, "Config.service_tier");
        Check.optNonEmpty(userId, "Config.user_id");
        Check.nonNegative(logprobs, "logprobs");
        Check.jsonObject(responseFormat, "response_format", false);
        validateResponseFormat(responseFormat);
        extensions = Check.extensions(extensions);
    }

    /** INV-050: exactly two shapes — {@code json_object} or {@code json_schema {schema, name?, strict?}}. */
    static void validateResponseFormat(JsonObject value) {
        if (value == null) return;
        JsonValue type = value.get("type");
        String fmt = type instanceof JsonString s ? s.value() : null;
        if (fmt == null || !(fmt.equals("json_object") || fmt.equals("json_schema"))) {
            throw ValidationException.value("response_format must be {'type': 'json_object'} or {'type': 'json_schema', "
                + "'schema': {...}, 'name'?: str, 'strict'?: bool}; provider-native shapes go in "
                + "Config.extensions (got keys " + new TreeSet<>(value.keys()) + ")");
        }
        Set<String> allowed = fmt.equals("json_object") ? Set.of("type") : Set.of("type", "schema", "name", "strict");
        TreeSet<String> extra = new TreeSet<>(value.keys());
        extra.removeAll(allowed);
        if (!extra.isEmpty()) {
            throw ValidationException.value("response_format '" + fmt + "' does not take keys " + extra + "; provider-native shapes go in Config.extensions");
        }
        if (fmt.equals("json_schema")) {
            if (!(value.get("schema") instanceof JsonObject)) {
                throw ValidationException.value("response_format json_schema requires a 'schema' object");
            }
            if (value.has("name") && !(value.get("name") instanceof JsonString n && !n.value().isEmpty())) {
                throw ValidationException.value("response_format name must be a non-empty string");
            }
            if (value.has("strict") && !(value.get("strict") instanceof JsonBool)) {
                throw ValidationException.type("response_format strict must be a bool");
            }
        }
    }

    public boolean isDefault() { return equals(DEFAULT); }

    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() {
        return new Builder().maxTokens(maxTokens).temperature(temperature).topP(topP).topK(topK).stop(stop)
            .responseFormat(responseFormat).toolChoice(toolChoice).reasoning(reasoning).cache(cache)
            .serviceTier(serviceTier).userId(userId).store(store).logprobs(logprobs).extensions(extensions);
    }

    public Config withCache(CacheConfig c) { return toBuilder().cache(c).build(); }
    public Config withToolChoice(ToolChoice t) { return toBuilder().toolChoice(t).build(); }
    public Config withReasoning(Reasoning r) { return toBuilder().reasoning(r).build(); }
    public Config withExtensions(JsonObject e) { return toBuilder().extensions(e).build(); }

    public static final class Builder {
        private Integer maxTokens;
        private Double temperature;
        private Double topP;
        private Integer topK;
        private List<String> stop = List.of();
        private JsonObject responseFormat;
        private ToolChoice toolChoice;
        private Reasoning reasoning;
        private CacheConfig cache;
        private String serviceTier;
        private String userId;
        private Boolean store;
        private Integer logprobs;
        private JsonObject extensions;

        public Builder maxTokens(Integer v) { maxTokens = v; return this; }
        public Builder temperature(Double v) { temperature = v; return this; }
        public Builder topP(Double v) { topP = v; return this; }
        public Builder topK(Integer v) { topK = v; return this; }
        public Builder stop(List<String> v) { stop = v == null ? List.of() : v; return this; }
        public Builder stop(String... v) { stop = List.of(v); return this; }
        public Builder responseFormat(JsonObject v) { responseFormat = v; return this; }
        public Builder toolChoice(ToolChoice v) { toolChoice = v; return this; }
        public Builder reasoning(Reasoning v) { reasoning = v; return this; }
        public Builder cache(CacheConfig v) { cache = v; return this; }
        public Builder serviceTier(String v) { serviceTier = v; return this; }
        public Builder userId(String v) { userId = v; return this; }
        public Builder store(Boolean v) { store = v; return this; }
        public Builder logprobs(Integer v) { logprobs = v; return this; }
        public Builder extensions(JsonObject v) { extensions = v; return this; }

        public Config build() {
            return new Config(maxTokens, temperature, topP, topK, stop, responseFormat, toolChoice, reasoning, cache,
                serviceTier, userId, store, logprobs, extensions);
        }
    }
}
