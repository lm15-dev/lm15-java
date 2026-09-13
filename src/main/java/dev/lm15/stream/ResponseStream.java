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
    private Iterator<StreamEvent> eventView;
    private final List<Throwable> cleanupErrors = new ArrayList<>();

    public ResponseStream(Iterator<StreamEvent> events, Request request) { this(events, request, null); }

    public ResponseStream(Iterator<StreamEvent> events, Request request, AutoCloseable closer) {
        this.source = events;
        this.closer = closer != null ? closer : events instanceof AutoCloseable closeable ? closeable : null;
        this.accumulator = new StreamAccumulator(request);
    }

    /** Failures that followed the end event (a read error while draining, a close that raised). */
    public List<Throwable> cleanupErrors() { return List.copyOf(cleanupErrors); }

    /** Canonical stream events, teed through the accumulator. */
    public Iterator<StreamEvent> events() {
        if (eventView != null) return eventView;
        eventView = new Iterator<>() {
            private StreamEvent next;

            private void advance() {
                if (failure != null) throw failure;
                if (done) { next = null; return; }
                if (next != null) return;
                try {
                    if (source.hasNext()) {
                        next = source.next();
                        return;
                    }
                } catch (StreamAssemblyError e) {
                    throw fail(e);
                } catch (LM15Error e) {
                    if (response == null) throw fail(e);
                    else { cleanupErrors.add(e); StreamWarnings.warn(e); }
                } catch (RuntimeException e) {
                    if (response == null) throw fail(e);
                    else { cleanupErrors.add(e); StreamWarnings.warn(e); }
                }
                if (response == null && failure == null) throw fail(Streams.incomplete(accumulator));
                finish();
            }

            @Override public boolean hasNext() { advance(); return next != null; }

            @Override public StreamEvent next() {
                advance();
                if (next == null) throw new NoSuchElementException();
                StreamEvent e = next;
                next = null;
                try {
                    Streams.checkTerminal(e, response);
                    accumulator.push(e);
                    if (e instanceof StreamEndEvent) response = accumulator.response();
                    return e;
                } catch (RuntimeException error) {
                    throw fail(error);
                }
            }
        };
        return eventView;
    }

    private RuntimeException fail(RuntimeException e) {
        if (failure == null) failure = e;
        finish();
        return failure;
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
            cleanupErrors.add(e);
            if (failure != null && failure != e) failure.addSuppressed(e);
            if (response == null && failure == null) failure = e instanceof RuntimeException r ? r : new RuntimeException(e);
            StreamWarnings.warn(e);
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
