package dev.lm15;

import dev.lm15.errors.*;
import dev.lm15.json.JsonObject;
import dev.lm15.sse.Sse;
import dev.lm15.stream.*;
import dev.lm15.transport.HttpResponse;
import dev.lm15.transport.Transport;
import dev.lm15.types.*;
import dev.lm15.wire.TransportRequest;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StreamLifecycleTest {
    static final Request REQUEST = Request.builder("gpt-4.1").user("hello").build();
    static final StreamEndEvent END = new StreamEndEvent(FinishReason.STOP, null, null);

    static final class Source implements Iterator<StreamEvent>, AutoCloseable {
        final Iterator<StreamEvent> events;
        int closes;
        RuntimeException tailError, closeError;
        Source(StreamEvent... events) { this.events = List.of(events).iterator(); }
        public boolean hasNext() {
            if (!events.hasNext() && tailError != null) throw tailError;
            return events.hasNext();
        }
        public StreamEvent next() { return events.next(); }
        public void close() { closes++; if (closeError != null) throw closeError; }
    }

    @Test void implicitCloseableSourceIsReleasedOnce() {
        Source source = new Source(END);
        try (ResponseStream stream = new ResponseStream(source, REQUEST)) {
            assertEquals(FinishReason.STOP, stream.response().finishReason());
            assertEquals(1, source.closes);
        }
        assertEquals(1, source.closes);
    }

    @Test void materializerKeepsCompletedAnswerAfterGenericTailErrorAndClosesSource() {
        Source source = new Source(END);
        source.tailError = new IllegalStateException("tail failure");
        assertEquals(FinishReason.STOP, Streams.materialize(source, REQUEST).finishReason());
        assertEquals(1, source.closes);
    }

    @Test void failedSourceRetainsItsOriginalErrorAndCloses() {
        Source source = new Source();
        source.tailError = new TransportError("original failure");
        source.closeError = new IllegalStateException("close failure");
        ResponseStream stream = new ResponseStream(source, REQUEST);
        assertSame(source.tailError, assertThrows(TransportError.class, stream::response));
        assertSame(source.tailError, assertThrows(TransportError.class, stream::response));
        assertEquals(1, source.closes);
        assertTrue(stream.cleanupErrors().contains(source.closeError));
    }

    @Test void prefetchedEventCannotEscapeAfterExplicitClose() {
        Source source = new Source(new StreamStartEvent(null, null), END);
        ResponseStream stream = new ResponseStream(source, REQUEST);
        Iterator<StreamEvent> events = stream.events();
        assertTrue(events.hasNext());
        stream.close();
        assertThrows(StreamAssemblyError.class, events::next);
        assertEquals(1, source.closes);
    }

    @Test void eventViewsShareTheSamePendingEvent() {
        StreamEvent start = new StreamStartEvent(null, null);
        ResponseStream stream = new ResponseStream(new Source(start, END), REQUEST);
        assertTrue(stream.events().hasNext());
        assertSame(start, stream.events().next());
        assertEquals(FinishReason.STOP, stream.response().finishReason());
    }

    @Test void peekingAtEndDoesNotMakeEarlyCloseSuccessful() {
        ResponseStream stream = new ResponseStream(new Source(END), REQUEST);
        assertTrue(stream.events().hasNext());
        stream.close();
        assertThrows(StreamAssemblyError.class, stream::response);
    }

    @Test void warningHandlerCannotInvalidateACompletedResponse() {
        Source source = new Source(END);
        source.tailError = new TransportError("cleanup");
        StreamWarnings.setHandler(error -> { throw new IllegalStateException("broken logger"); });
        try {
            assertEquals(FinishReason.STOP, Streams.materialize(source, REQUEST).finishReason());
        } finally { StreamWarnings.setHandler(null); }
    }

    static ProviderLM withBody(InputStream body) {
        Transport transport = new Transport() {
            public HttpResponse send(TransportRequest r) { throw new AssertionError("unexpected send"); }
            public Streaming stream(TransportRequest r) { return new Streaming(200, List.of(), body); }
        };
        return OpenAIChatLM.builder().apiKey("test").transport(transport).build();
    }

    @Test void parserFailureClosesRawStreamWithoutCallerCleanup() {
        AtomicInteger closes = new AtomicInteger();
        InputStream body = new ByteArrayInputStream("data: invalid-json\n\n".getBytes(StandardCharsets.UTF_8)) {
            public void close() { closes.incrementAndGet(); }
        };
        try (ProviderLM lm = withBody(body)) {
            var events = lm.stream(REQUEST);
            assertThrows(RuntimeException.class, events::hasNext);
            assertEquals(1, closes.get());
            events.close();
            assertEquals(1, closes.get());
        }
    }

    @Test void closeFailureFollowsTheYieldedEndRatherThanReplacingIt() {
        InputStream body = new ByteArrayInputStream("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)) {
            public void close() throws IOException { throw new IOException("close failure"); }
        };
        try (ProviderLM lm = withBody(body); ResponseStream stream = lm.responseStream(REQUEST)) {
            assertEquals(FinishReason.STOP, stream.response().finishReason());
            assertEquals(1, stream.cleanupErrors().size());
        }
    }

    @Test void sseSupportsAllLineEndingsBomAndExactWhitespace() {
        for (String eol : List.of("\n", "\r", "\r\n")) {
            var events = Sse.parse(("\uFEFFevent:  padded " + eol + "data:  café 🌱 " + eol + eol + "data" + eol + eol).getBytes(StandardCharsets.UTF_8));
            assertEquals(2, events.size());
            assertEquals(" padded ", events.get(0).event());
            assertEquals(" café 🌱 ", events.get(0).data());
            assertEquals("", events.get(1).data());
        }
    }

    @Test void sseRejectsOversizedLineBeforeReadingItsEntireRemainder() {
        AtomicInteger reads = new AtomicInteger();
        InputStream body = new InputStream() {
            public int read() {
                if (reads.incrementAndGet() > Sse.MAX_LINE_BYTES * 2) throw new AssertionError("line limit was enforced too late");
                return 'a';
            }
        };
        assertThrows(TransportError.class, () -> Sse.iterate(body).hasNext());
        assertTrue(reads.get() <= Sse.MAX_LINE_BYTES * 2);
    }
}
