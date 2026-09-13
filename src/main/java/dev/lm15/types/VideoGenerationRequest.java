package dev.lm15.types;

import dev.lm15.json.JsonObject;

import java.util.List;

/** Text (and optionally an input image) in; a video JOB out. */
public record VideoGenerationRequest(String model, String prompt, Integer seconds, List<ImagePart> images, JsonObject extensions) {
    public VideoGenerationRequest {
        if (model == null || model.isEmpty()) throw ValidationException.value("model is required");
        if (prompt == null) throw ValidationException.type("prompt must be a string");
        if (prompt.isEmpty()) throw ValidationException.value("prompt is required");
        if (seconds != null && seconds <= 0) throw ValidationException.value("VideoGenerationRequest.seconds must be a positive int");
        images = Check.list(images, "VideoGenerationRequest.images");
        extensions = Check.extensions(extensions);
    }

    public VideoGenerationRequest(String model, String prompt) { this(model, prompt, null, List.of(), null); }
}
