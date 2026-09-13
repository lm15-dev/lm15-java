package dev.lm15.types;

import dev.lm15.json.JsonObject;

import java.util.List;

/** Inference capabilities of a model. */
public record InferenceModelInfo(List<String> inputModalities, List<String> outputModalities, Integer contextWindow,
                                 Integer maxOutputTokens, boolean supportsReasoning, List<String> reasoningEfforts,
                                 InferencePricing pricing, JsonObject extensions) {
    public InferenceModelInfo {
        inputModalities = inputModalities == null ? List.of("text") : Check.nonEmptyStrings(inputModalities, "input_modalities", "input_modalities must contain non-empty strings");
        outputModalities = outputModalities == null ? List.of("text") : Check.nonEmptyStrings(outputModalities, "output_modalities", "output_modalities must contain non-empty strings");
        reasoningEfforts = Check.nonEmptyStrings(reasoningEfforts, "reasoning_efforts", "reasoning_efforts must contain non-empty strings");
        if (contextWindow != null && contextWindow <= 0) throw ValidationException.value("context_window must be a positive integer or None");
        if (maxOutputTokens != null && maxOutputTokens <= 0) throw ValidationException.value("max_output_tokens must be a positive integer or None");
    }

    public static final InferenceModelInfo DEFAULT = new InferenceModelInfo(null, null, null, null, false, List.of(), null, null);
}
