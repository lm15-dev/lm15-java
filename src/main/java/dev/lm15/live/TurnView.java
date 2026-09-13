package dev.lm15.live;

import dev.lm15.errors.TransportError;
import dev.lm15.types.ErrorDetail;
import dev.lm15.types.LiveServerEvent;
import dev.lm15.types.LiveServerEventType;
import dev.lm15.types.Usage;
import dev.lm15.types.ValidationException;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * One buffered half-duplex view of a live session (LIVE-1): iteration
 * stops after {@code turn_end} / {@code interrupted} / {@code error}; a
 * {@code tool_call} is yielded mid-turn and does not end iteration, but
 * {@link #result()} returns at it because the caller must answer.
 */
public final class TurnView implements Iterator<LiveServerEvent>, Iterable<LiveServerEvent> {
    private static final Set<LiveServerEventType> TERMINAL = Set.of(LiveServerEventType.TURN_END, LiveServerEventType.INTERRUPTED, LiveServerEventType.ERROR);

    private final LiveSession session;
    private final List<LiveServerEvent> events = new ArrayList<>();
    private boolean done;
    private RuntimeException failure;
    private Turn result;
    private LiveServerEvent next;

    TurnView(LiveSession session) {
        this.session = session;
    }

    /** Collected data so far; {@code endedBy} is {@code incomplete} until a boundary. */
    public Turn snapshot() { return materialize(events); }

    @Override public Iterator<LiveServerEvent> iterator() { return this; }

    @Override public boolean hasNext() {
        if (next != null) return true;
        if (done) return false;
        if (failure != null) throw failure;
        try {
            LiveServerEvent event = session.receive();
            if (event == null) throw new TransportError("live session closed before the turn reached a boundary");
            events.add(event);
            if (TERMINAL.contains(event.type())) done = true;
            next = event;
            return true;
        } catch (RuntimeException e) {
            failure = e;
            done = true;
            throw e;
        }
    }

    @Override public LiveServerEvent next() {
        if (!hasNext()) throw new NoSuchElementException();
        LiveServerEvent e = next;
        next = null;
        return e;
    }

    /** The materialized turn; reads to the boundary, or stops at a tool call the caller still owes an answer to. */
    public Turn result() {
        if (result != null) return result;
        if (events.isEmpty() || events.get(events.size() - 1).type() != LiveServerEventType.TOOL_CALL) {
            while (hasNext()) {
                LiveServerEvent e = next();
                if (e.type() == LiveServerEventType.TOOL_CALL) break;
            }
        }
        if (failure != null) throw failure;
        Turn t = snapshot();
        if (t.endedBy().equals("incomplete")) throw new TransportError("turn view closed before the turn reached a boundary; inspect snapshot()");
        done = true;
        result = t;
        return t;
    }

    /** Materialize a turn from its events (LIVE-2: usage is the field-wise sum of every usage and turn_end event). */
    public static Turn materialize(List<LiveServerEvent> events) {
        StringBuilder text = new StringBuilder();
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        String audioMediaType = null;
        List<LiveServerEvent.ToolCall> toolCalls = new ArrayList<>();
        Usage usage = null;
        ErrorDetail error = null;
        for (LiveServerEvent event : events) {
            switch (event) {
                case LiveServerEvent.Text t -> text.append(t.text());
                case LiveServerEvent.Audio a -> {
                    if (audioMediaType != null && a.mediaType() != null && !audioMediaType.equals(a.mediaType())) {
                        throw ValidationException.value("a Turn cannot concatenate different audio media types; consume raw events");
                    }
                    audio.writeBytes(Base64.getDecoder().decode(a.data()));
                    if (audioMediaType == null && a.mediaType() != null) audioMediaType = a.mediaType();
                }
                case LiveServerEvent.ToolCall c -> toolCalls.add(c);
                case LiveServerEvent.TurnEnd e -> usage = usage == null ? e.usage() : usage.plus(e.usage());
                case LiveServerEvent.UsageEvent u -> usage = usage == null ? u.usage() : usage.plus(u.usage());
                case LiveServerEvent.Error e -> error = e.error();
                default -> { }
            }
        }
        String endedBy = "incomplete";
        if (!events.isEmpty()) {
            LiveServerEventType last = events.get(events.size() - 1).type();
            if (TERMINAL.contains(last) || last == LiveServerEventType.TOOL_CALL) endedBy = last.wire();
        }
        return new Turn(endedBy, text.toString(), audio.toByteArray(), audioMediaType, List.copyOf(toolCalls), usage, error, List.copyOf(events));
    }
}
