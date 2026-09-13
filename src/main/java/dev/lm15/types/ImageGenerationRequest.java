package dev.lm15.types;

import dev.lm15.json.JsonObject;

import java.util.List;

/** Text (and optionally input images, for edits) in; images out. */
public record ImageGenerationRequest(String model, String prompt, String size, List<ImagePart> images, JsonObject extensions) {
    public ImageGenerationRequest {
        if (model == null || model.isEmpty()) throw ValidationException.value("model is required");
        if (prompt == null) throw ValidationException.type("prompt must be a string");
        if (prompt.isEmpty()) throw ValidationException.value("prompt is required");
        Check.optNonEmpty(size, "size");
        images = Check.list(images, "ImageGenerationRequest.images");
        extensions = Check.extensions(extensions);
    }

    public ImageGenerationRequest(String model, String prompt) { this(model, prompt, null, List.of(), null); }
}
