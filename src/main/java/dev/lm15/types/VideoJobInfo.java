package dev.lm15.types;

import dev.lm15.json.JsonObject;

/** A snapshot of a provider-side video job — the ticket. */
public record VideoJobInfo(String id, VideoStatus status, Integer progress, String createdAt, String model, JsonObject providerData) {
    public VideoJobInfo {
        Check.nonEmpty(id, "VideoJobInfo.id");
        Check.required(status, "video status");
        if (progress != null && (progress < 0 || progress > 100)) throw ValidationException.value("VideoJobInfo.progress must be an int percentage 0-100");
        Check.optNonEmpty(createdAt, "VideoJobInfo.created_at");
        Check.optNonEmpty(model, "VideoJobInfo.model");
        Check.jsonObject(providerData, "provider_data", false);
    }

    public boolean done() { return status.isTerminal(); }
}
