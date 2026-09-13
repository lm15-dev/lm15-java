package dev.lm15.sse;

import dev.lm15.errors.TimeoutError;
import dev.lm15.errors.TransportError;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** Incremental SSE byte framing shared by live reads and recorded replay. */
public final class Sse {
    private Sse() {}

    public static final int MAX_LINE_BYTES = 64 * 1024;
    public static final int MAX_EVENT_BYTES = 1024 * 1024;

    public static List<SseEvent> parse(byte[] body) {
        List<SseEvent> out = new ArrayList<>();
        iterate(new java.io.ByteArrayInputStream(body)).forEachRemaining(out::add);
        return out;
    }

    /** The caller owns the input stream. LF, CRLF and CR all delimit lines. */
    public static Iterator<SseEvent> iterate(InputStream in) {
        return new Iterator<>() {
            private final LineReader lines = new LineReader(in);
            private SseEvent next;
            private boolean done;
            private boolean firstLine = true;

            private void advance() {
                if (next != null || done) return;
                String eventName = null;
                List<String> data = new ArrayList<>();
                int eventBytes = 0;
                byte[] raw;
                while ((raw = lines.readLine()) != null) {
                    // Count one normalized separator, independent of its spelling.
                    eventBytes += raw.length + 1;
                    if (eventBytes > MAX_EVENT_BYTES) throw new TransportError("SSE event exceeds limit");
                    String line = new String(raw, StandardCharsets.UTF_8);
                    if (firstLine) {
                        if (line.startsWith("\uFEFF")) line = line.substring(1);
                        firstLine = false;
                    }
                    if (line.isEmpty()) {
                        if (!data.isEmpty()) {
                            next = new SseEvent(eventName, String.join("\n", data));
                            return;
                        }
                        eventName = null;
                        eventBytes = 0;
                        continue;
                    }
                    int colon = line.indexOf(':');
                    String field = colon < 0 ? line : line.substring(0, colon);
                    String value = colon < 0 ? "" : line.substring(colon + 1);
                    if (value.startsWith(" ")) value = value.substring(1);
                    if (field.equals("event")) eventName = value;
                    else if (field.equals("data")) data.add(value);
                }
                done = true;
                // Retain the existing final-record-at-EOF behavior. Assembly still
                // refuses a response that never received a canonical end event.
                if (!data.isEmpty()) next = new SseEvent(eventName, String.join("\n", data));
            }

            @Override public boolean hasNext() { advance(); return next != null; }
            @Override public SseEvent next() {
                advance();
                if (next == null) throw new NoSuchElementException();
                SseEvent event = next;
                next = null;
                return event;
            }
        };
    }

    static final class LineReader {
        private final InputStream in;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean skipLf;

        LineReader(InputStream in) { this.in = new BufferedInputStream(in); }

        byte[] readLine() {
            buffer.reset();
            try {
                int b;
                while ((b = in.read()) >= 0) {
                    if (skipLf) {
                        skipLf = false;
                        if (b == '\n') continue;
                    }
                    if (b == '\r' || b == '\n') {
                        skipLf = b == '\r';
                        return buffer.toByteArray();
                    }
                    if (buffer.size() == MAX_LINE_BYTES) throw new TransportError("SSE line exceeds limit");
                    buffer.write(b);
                }
            } catch (SocketTimeoutException e) {
                throw new TimeoutError("response body read timed out");
            } catch (IOException e) {
                throw new TransportError("response body read failed");
            }
            return buffer.size() == 0 ? null : buffer.toByteArray();
        }
    }
}
