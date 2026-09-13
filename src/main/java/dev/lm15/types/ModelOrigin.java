package dev.lm15.types;

import dev.lm15.json.JsonObject;

/** Where a model comes from; {@code providerData} is the listing's wire entry verbatim. */
public record ModelOrigin(String type, String id, String baseModel, JsonObject providerData) {
    public static final ModelOrigin PROVIDER = new ModelOrigin("provider", null, null, null);

    public ModelOrigin {
        if (type == null) type = "provider";
        if (type.isEmpty()) throw ValidationException.value("ModelOrigin.type must be a non-empty string");
        Check.optNonEmpty(id, "ModelOrigin.id");
        Check.optNonEmpty(baseModel, "ModelOrigin.base_model");
    }
}
