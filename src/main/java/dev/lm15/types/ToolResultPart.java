package dev.lm15.types;

import java.util.List;

/**
 * The result of an external computation, sent back to the model. {@code content}
 * is non-empty and holds presentational parts only (INV-013, INV-014);
 * {@code isError} is emitted only when true.
 */
public record ToolResultPart(String id, List<Part> content, String name, boolean isError,
                             List<ContinuationState> continuation) implements Part {
    public ToolResultPart {
        Check.nonEmpty(id, "ToolResultPart.id");
        Check.optNonEmpty(name, "ToolResultPart.name");
        content = Check.list(content, "ToolResultPart.content");
        if (content.isEmpty()) throw ValidationException.value("ToolResultPart requires content");
        for (Part p : content) {
            if (Part.isToolResultForbidden(p)) {
                throw ValidationException.type("ToolResultPart.content cannot contain tool calls, nested tool results, thinking parts, or refusals");
            }
        }
        continuation = Check.continuation(continuation);
    }

    public ToolResultPart(String id, List<Part> content) { this(id, content, null, false, List.of()); }

    @Override public PartType type() { return PartType.TOOL_RESULT; }
    @Override public ToolResultPart withContinuation(List<ContinuationState> c) {
        return new ToolResultPart(id, content, name, isError, c);
    }
}
