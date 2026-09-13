package dev.lm15.types;

import dev.lm15.json.JsonObject;

import java.util.List;

/** Generated images plus any narration the model returned alongside. */
public record ImageGenerationResponse(List<ImagePart> images, String text, String id, String model, Usage usage, JsonObject providerData) {
    public ImageGenerationResponse {
        images = Check.list(images, "ImageGenerationResponse.images");
        if (images.isEmpty()) throw ValidationException.value("ImageGenerationResponse requires at least one image");
        Check.optNonEmpty(text, "ImageGenerationResponse.text");
        Check.optNonEmpty(id, "ImageGenerationResponse.id");
        Check.optNonEmpty(model, "ImageGenerationResponse.model");
        if (usage == null) usage = Usage.EMPTY;
        Check.jsonObject(providerData, "provider_data", false);
    }
}
