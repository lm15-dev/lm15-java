package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

import java.util.List;

/** The catalog matched a model string under more than one provider. Carries {@code model} and {@code providers}. */
public class AmbiguousModelError extends ConfigurationError {
    private final String model;
    private final List<String> providers;

    public AmbiguousModelError(String message, String model, List<String> providers) {
        super(message, ErrorCode.AMBIGUOUS_MODEL, ErrorMeta.NONE);
        this.model = model == null ? "" : model;
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    public String model() { return model; }
    public List<String> providers() { return providers; }
}
