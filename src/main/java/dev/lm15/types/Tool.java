package dev.lm15.types;

/** A tool offered to the model: a {@link FunctionTool} or a provider {@link BuiltinTool}. */
public sealed interface Tool permits FunctionTool, BuiltinTool {
    String name();
    /** The wire discriminator: {@code function} or {@code builtin}. */
    String type();
}
