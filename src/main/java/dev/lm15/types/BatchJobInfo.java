package dev.lm15.types;

import dev.lm15.json.JsonObject;

/** A snapshot of a provider-side batch job — the ticket. */
public record BatchJobInfo(String id, BatchStatus status, String label, String createdAt, JsonObject providerData) {
    public BatchJobInfo {
        Check.nonEmpty(id, "BatchJobInfo.id");
        Check.required(status, "batch status");
        Check.optNonEmpty(label, "BatchJobInfo.label");
        Check.optNonEmpty(createdAt, "BatchJobInfo.created_at");
        Check.jsonObject(providerData, "provider_data", false);
    }

    public boolean done() { return status.isTerminal(); }
}
