package dev.lm15.stream;

import dev.lm15.errors.Errors;
import dev.lm15.errors.LM15Error;
import dev.lm15.errors.StreamAssemblyError;
import dev.lm15.json.Json;
import dev.lm15.types.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** One-shot materialization and the Response → events conversion (the reference's {@code lm15.result}). */
public final class Streams {
    private Streams() {}

    /** Consume a complete coalesced stream, requiring a final end event (MAP-3); the typed error for an error event. */
    public static Response materialize(Iterator<StreamEvent> events, Request request) {
        try (ResponseStream stream = new ResponseStream(events, request)) {
            return stream.response();
        }
    }

    public static Response materialize(List<StreamEvent> events, Request request) {
        return materialize(events.iterator(), request);
    }

    static void checkTerminal(StreamEvent event, Response response) {
        if (response != null) {
            throw new StreamAssemblyError("Stream emitted an event after its end event (MAP-3: the end event is final); the source that produced this stream is defective", response);
        }
        if (event instanceof StreamErrorEvent err) throw exceptionFor(err.error());
    }

    static StreamAssemblyError incomplete(StreamAccumulator acc) {
        return new StreamAssemblyError("Stream ended without an end event: its finish reason and usage never arrived, so the text is not a finished turn (MAP-3)", partialOf(acc));
    }

    static StreamAssemblyError closedEarly(StreamAccumulator acc) {
        return new StreamAssemblyError("Stream closed before its end event: the response was not completed (close() was called while the stream was still open; MAP-3)", partialOf(acc));
    }

    static Response partialOf(StreamAccumulator acc) {
        try {
            return acc.response();
        } catch (StreamAssemblyError e) {
            return e.partial();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The typed error for an error event's detail. */
    public static LM15Error exceptionFor(ErrorDetail detail) {
        return Errors.forCode(detail.code(), detail.message(), dev.lm15.errors.ErrorMeta.NONE.withProviderCode(detail.providerCode()));
    }

    /** Convert a complete Response to stream events; lossless for the Delta vocabulary, a TypeError for a part with no delta form. */
    public static List<StreamEvent> responseToEvents(Response response) {
        List<StreamEvent> out = new ArrayList<>();
        out.add(new StreamStartEvent(response.id(), response.model()));
        List<TokenLogprob> pending = response.logprobs() == null ? List.of() : response.logprobs();
        List<Part> parts = response.message().parts();
        for (int idx = 0; idx < parts.size(); idx++) {
            Part part = parts.get(idx);
            switch (part) {
                case TextPart p -> { out.add(new StreamDeltaEvent(new TextDelta(p.text(), idx, pending))); pending = List.of(); }
                case ThinkingPart p -> out.add(new StreamDeltaEvent(new ThinkingDelta(p.text(), idx)));
                case ToolCallPart p -> out.add(new StreamDeltaEvent(new ToolCallDelta(Json.write(p.input()), idx, p.id(), p.name())));
                case ImagePart p -> out.add(new StreamDeltaEvent(new ImageDelta(p.data(), p.url(), p.fileId(), idx, p.mediaType())));
                case AudioPart p -> {
                    if (p.data() == null) throw ValidationException.type("Cannot convert AudioPart to StreamEvent: AudioDelta only supports inline data");
                    out.add(new StreamDeltaEvent(new AudioDelta(p.data(), null, null, idx, p.mediaType())));
                }
                case CitationPart p -> out.add(new StreamDeltaEvent(new CitationDelta(p.text(), p.url(), p.title(), idx)));
                default -> throw ValidationException.type("Cannot convert " + part.getClass().getSimpleName() + " to StreamEvent: no '" + part.type().wire() + "' Delta variant exists");
            }
            for (ContinuationState s : part.continuation()) out.add(new StreamDeltaEvent(ContinuationDelta.of(s, idx)));
        }
        for (ContinuationState s : response.message().continuation()) out.add(new StreamDeltaEvent(ContinuationDelta.of(s, null)));
        out.add(new StreamEndEvent(response.finishReason(), response.usage(), response.providerData()));
        return out;
    }
}
