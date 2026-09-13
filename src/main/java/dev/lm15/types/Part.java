package dev.lm15.types;

import java.util.List;

/**
 * The atoms of content — a closed sum (playbooks/port.md § Idioms): check
 * with {@code instanceof} / pattern matching on the sealed variants.
 *
 * <p>Streamable partition (INV-035): text, thinking, image, audio, tool_call,
 * citation assemble from deltas; video, document, binary, tool_result,
 * refusal do not.
 */
public sealed interface Part permits
        TextPart, ThinkingPart, RefusalPart, CitationPart, MediaPart,
        ToolCallPart, ToolResultPart {

    /** The wire discriminator. */
    PartType type();

    /** Continuation state attached to this part (empty when none). */
    List<ContinuationState> continuation();

    /** This part with a different continuation list. */
    Part withContinuation(List<ContinuationState> continuation);

    /** Streamable parts have a Delta variant (INV-035). */
    default boolean isStreamable() {
        return switch (type()) {
            case TEXT, THINKING, IMAGE, AUDIO, TOOL_CALL, CITATION -> true;
            case VIDEO, DOCUMENT, BINARY, TOOL_RESULT, REFUSAL -> false;
        };
    }

    /** Protocol parts a prompt (user/developer/system) may not carry (INV-024). */
    static boolean isPromptForbidden(Part p) {
        return p instanceof ToolCallPart || p instanceof ToolResultPart || p instanceof ThinkingPart
            || p instanceof RefusalPart || p instanceof CitationPart;
    }

    /** Parts a tool result may not carry (INV-013). */
    static boolean isToolResultForbidden(Part p) {
        return p instanceof ToolCallPart || p instanceof ToolResultPart || p instanceof ThinkingPart
            || p instanceof RefusalPart;
    }
}
