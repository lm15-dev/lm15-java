package dev.lm15.errors;

import dev.lm15.types.ErrorCode;
import dev.lm15.types.Response;

/**
 * A stream cannot become a Response without inventing a fact (MAP-9: an
 * unnamed tool call; MAP-3: no end event, closed early, or an event after the
 * end). {@code partial} is everything that did assemble; {@code partIndex}
 * the first offending part (MAP-9 only).
 */
public class StreamAssemblyError extends LM15Error {
    private final Response partial;
    private final Integer partIndex;

    public StreamAssemblyError(String message, Response partial, Integer partIndex) {
        super(message, ErrorCode.STREAM_ASSEMBLY, ErrorMeta.NONE);
        this.partial = partial;
        this.partIndex = partIndex;
    }

    public StreamAssemblyError(String message, Response partial) { this(message, partial, null); }

    public Response partial() { return partial; }
    public Integer partIndex() { return partIndex; }
}
