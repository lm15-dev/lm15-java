package dev.lm15.types;

import dev.lm15.json.JsonObject;

/** Text-to-speech. Omitted voice/format mean the server's defaults. */
public record SpeechGenerationRequest(String model, String prompt, String voice, String format, JsonObject extensions) {
    public SpeechGenerationRequest {
        if (model == null || model.isEmpty()) throw ValidationException.value("model is required");
        if (prompt == null) throw ValidationException.type("prompt must be a string");
        if (prompt.isEmpty()) throw ValidationException.value("prompt is required");
        Check.optNonEmpty(voice, "voice");
        Check.optNonEmpty(format, "format");
        extensions = Check.extensions(extensions);
    }

    public SpeechGenerationRequest(String model, String prompt) { this(model, prompt, null, null, null); }
}
