package dev.lm15.types;

import dev.lm15.json.JsonObject;

/** A snapshot of one provider-side stored cache object (the resource tier of MAP-6). */
public record CacheInfo(String id, String model, Integer tokens, String createdAt, String expiresAt, String label, JsonObject providerData) {
    public CacheInfo {
        Check.nonEmpty(id, "CacheInfo.id");
        Check.nonEmpty(model, "CacheInfo.model");
        Check.nonNegative(tokens, "CacheInfo.tokens");
        Check.optNonEmpty(createdAt, "CacheInfo.created_at");
        Check.optNonEmpty(expiresAt, "CacheInfo.expires_at");
        Check.optNonEmpty(label, "CacheInfo.label");
        Check.jsonObject(providerData, "provider_data", false);
    }
}
