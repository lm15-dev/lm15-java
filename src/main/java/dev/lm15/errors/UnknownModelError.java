package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

/** The router found no provider for a model string (unknown_model, 2026-09-08). Carries {@code model}. */
public class UnknownModelError extends ConfigurationError {
    private final String model;

    public UnknownModelError(String message, String model) {
        super(message, ErrorCode.UNKNOWN_MODEL, ErrorMeta.NONE);
        this.model = model == null ? "" : model;
    }

    public String model() { return model; }
}
