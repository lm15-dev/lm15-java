package dev.lm15.stream;

import dev.lm15.errors.LM15Error;
import dev.lm15.errors.StreamAssemblyError;
import dev.lm15.types.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Lazy stream-backed response assembler (api-family § The core loop):
 * iterate for text as it arrives, {@link #events()} for the canonical
 * events, {@link #response()} afterwards for the same Response
 * {@code complete} returns. Tool calls are surfaced as data only.
 */
public final class ResponseStream implements Iterable<String>, AutoCloseable {
    private final Iterator<StreamEvent> source;
    private final AutoCloseable closer;
    private final StreamAccumulator accumulator;
    private Response response;
    private RuntimeException failure;
    private boolean done;
    private boolean sourceClosed;
    private final List<Throwable> cleanupErrors = new ArrayList<>();

    public ResponseStream(Iterator<StreamEvent> events, Request request) { this(events, request, null); }

    public ResponseStream(Iterator<StreamEvent> events, Request request, AutoCloseable closer) {
        this.source = events;
        this.closer = closer;
        this.accumulator = new StreamAccumulator(request);
    }

    /** Failures that followed the end event (a read error while draining, a close that raised). */
    public List<Throwable> cleanupErrors() { return List.copyOf(cleanupErrors); }

    /** Canonical stream events, teed through the accumulator. */
    public Iterator<StreamEvent> events() {
        return new Iterator<>() {
            private StreamEvent next;

            private void advance() {
                if (next != null || done) return;
                try {
                    if (source.hasNext()) {
                        StreamEvent event = source.next();
                        Streams.checkTerminal(event, response);
                        accumulator.push(event);
                        if (event instanceof StreamEndEvent) response = accumulator.response();
                        next = event;
                        return;
                    }
                } catch (StreamAssemblyError e) {
                    fail(e);
                } catch (LM15Error e) {
                    if (response == null) fail(e);
                    else { cleanupErrors.add(e); StreamWarnings.warn(e); }
                } catch (RuntimeException e) {
                    if (response == null) fail(e);
                    else { cleanupErrors.add(e); StreamWarnings.warn(e); }
                }
                if (response == null && failure == null) fail(Streams.incomplete(accumulator));
                finish();
            }

            @Override public boolean hasNext() { advance(); return next != null; }

            @Override public StreamEvent next() {
                advance();
                if (next == null) throw new NoSuchElementException();
                StreamEvent e = next;
                next = null;
                return e;
            }
        };
    }

    private void fail(RuntimeException e) {
        if (failure == null) failure = e;
        finish();
        throw e;
    }

    private void finish() {
        done = true;
        closeSource();
    }

    private void closeSource() {
        if (sourceClosed) return;
        sourceClosed = true;
        if (closer == null) return;
        try {
            closer.close();
        } catch (Exception e) {
            if (response != null) { cleanupErrors.add(e); StreamWarnings.warn(e); }
            else if (failure == null) failure = e instanceof RuntimeException r ? r : new RuntimeException(e);
        }
    }

    /** Text fragments as they arrive. */
    @Override public Iterator<String> iterator() {
        Iterator<StreamEvent> events = events();
        return new Iterator<>() {
            private String next;

            private void advance() {
                while (next == null && events.hasNext()) {
                    StreamEvent e = events.next();
                    if (e instanceof StreamDeltaEvent d && d.delta() instanceof TextDelta t) next = t.text();
                }
            }

            @Override public boolean hasNext() { advance(); return next != null; }

            @Override public String next() {
                advance();
                if (next == null) throw new NoSuchElementException();
                String s = next;
                next = null;
                return s;
            }
        };
    }

    /** The same Response {@code complete} returns, consuming any remainder. */
    public Response response() {
        if (failure != null) throw failure;
        if (!done) {
            Iterator<StreamEvent> it = events();
            while (it.hasNext()) it.next();
            if (failure != null) throw failure;
        }
        return response;
    }

    public String text() { return response().text(); }
    public List<ToolCallPart> toolCalls() { return response().toolCalls(); }
    public Usage usage() { return response().usage(); }
    public FinishReason finishReason() { return response().finishReason(); }

    /** Stop reading and release the source without draining it; an unfinished stream has no complete response. */
    @Override public void close() {
        if (!done && response == null && failure == null) failure = Streams.closedEarly(accumulator);
        done = true;
        closeSource();
    }
}
