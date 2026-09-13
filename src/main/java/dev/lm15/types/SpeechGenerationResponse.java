package dev.lm15.types;

import dev.lm15.json.JsonObject;

/** Generated speech; {@code audio.mediaType} comes from the wire verbatim. */
public record SpeechGenerationResponse(AudioPart audio, String id, String model, Usage usage, JsonObject providerData) {
    public SpeechGenerationResponse {
        if (audio == null) throw ValidationException.type("audio must be an AudioPart");
        Check.optNonEmpty(id, "SpeechGenerationResponse.id");
        Check.optNonEmpty(model, "SpeechGenerationResponse.model");
        if (usage == null) usage = Usage.EMPTY;
        Check.jsonObject(providerData, "provider_data", false);
    }
}
