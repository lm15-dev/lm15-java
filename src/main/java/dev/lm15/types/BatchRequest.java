package dev.lm15.types;

import dev.lm15.json.JsonObject;

import java.util.List;

/** A batch of model requests; {@code model} infers from the first request (INV-032). */
public record BatchRequest(String model, List<Request> requests, String label, JsonObject extensions) {
    public BatchRequest {
        requests = Check.list(requests, "BatchRequest.requests");
        if (requests.isEmpty()) throw ValidationException.value("requests cannot be empty");
        Check.optNonEmpty(model, "BatchRequest.model");
        if (model == null) model = requests.get(0).model();
        Check.optNonEmpty(label, "BatchRequest.label");
        extensions = Check.extensions(extensions);
    }

    public BatchRequest(List<Request> requests) { this(null, requests, null, null); }
}
