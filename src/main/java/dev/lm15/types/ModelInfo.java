package dev.lm15.types;

import dev.lm15.json.JsonObject;

import java.util.List;

/** Model metadata from a provider catalog (the {@code models} direction). */
public record ModelInfo(String id, String provider, String apiFamily, List<String> aliases, ModelOrigin origin,
                        InferenceModelInfo inference, JsonObject extensions) {
    public ModelInfo {
        if (id == null || id.isEmpty()) throw ValidationException.value("ModelInfo.id must be a non-empty string");
        if (provider == null || provider.isEmpty()) throw ValidationException.value("ModelInfo.provider must be a non-empty string");
        if (apiFamily == null || apiFamily.isEmpty()) throw ValidationException.value("ModelInfo.api_family must be a non-empty string");
        aliases = Check.nonEmptyStrings(aliases, "aliases", "ModelInfo.aliases must contain non-empty strings");
        if (origin == null) origin = ModelOrigin.PROVIDER;
    }

    public ModelInfo(String id, String provider, String apiFamily) { this(id, provider, apiFamily, List.of(), ModelOrigin.PROVIDER, null, null); }
}
