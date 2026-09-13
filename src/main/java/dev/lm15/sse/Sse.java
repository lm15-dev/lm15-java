package dev.lm15.sse;

import dev.lm15.errors.TransportError;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** The SSE parser (the reference's {@code lm15.sse}): byte lines in, events out; comments skipped; limits enforced. */
public final class Sse {
    private Sse() {}

    public static final int MAX_LINE_BYTES = 64 * 1024;
    public static final int MAX_EVENT_BYTES = 1024 * 1024;

    /** Parse a whole body (the replay path). */
    public static List<SseEvent> parse(byte[] body) {
        List<SseEvent> out = new ArrayList<>();
        Iterator<SseEvent> it = iterate(new java.io.ByteArrayInputStream(body));
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    /** Parse incrementally from a stream: one event per blank-line boundary. */
    public static Iterator<SseEvent> iterate(InputStream in) {
        return new Iterator<>() {
            private final LineReader lines = new LineReader(in);
            private SseEvent next;
            private boolean done;

            private void advance() {
                if (next != null || done) return;
                String eventName = null;
                List<String> data = new ArrayList<>();
                int eventBytes = 0;
                byte[] raw;
                while ((raw = lines.readLine()) != null) {
                    if (raw.length > MAX_LINE_BYTES) throw new TransportError("SSE line exceeds limit (" + raw.length + " > " + MAX_LINE_BYTES + ")");
                    eventBytes += raw.length;
                    if (eventBytes > MAX_EVENT_BYTES) throw new TransportError("SSE event exceeds limit (" + eventBytes + " > " + MAX_EVENT_BYTES + ")");
                    String line = stripEol(new String(raw, StandardCharsets.UTF_8));
                    if (line.isEmpty()) {
                        if (!data.isEmpty()) {
                            next = new SseEvent(eventName, String.join("\n", data));
                            return;
                        }
                        eventName = null;
                        eventBytes = 0;
                        continue;
                    }
                    if (line.startsWith(":")) continue;
                    if (line.startsWith("event:")) { eventName = line.substring(6).strip(); continue; }
                    if (line.startsWith("data:")) { data.add(line.substring(5).stripLeading()); continue; }
                }
                done = true;
                if (!data.isEmpty()) next = new SseEvent(eventName, String.join("\n", data));
            }

            @Override public boolean hasNext() { advance(); return next != null; }

            @Override public SseEvent next() {
                advance();
                if (next == null) throw new NoSuchElementException();
                SseEvent e = next;
                next = null;
                return e;
            }
        };
    }

    private static String stripEol(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
        return s.substring(0, end);
    }

    /** Reads newline-terminated byte lines (the terminator kept) from a stream. */
    static final class LineReader {
        private final InputStream in;
        private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();

        LineReader(InputStream in) { this.in = in; }

        byte[] readLine() {
            buf.reset();
            try {
                int b;
                while ((b = in.read()) >= 0) {
                    buf.write(b);
                    if (b == '\n') return buf.toByteArray();
                }
            } catch (IOException e) {
                throw new TransportError(e.getMessage() == null ? e.toString() : e.getMessage());
            }
            return buf.size() == 0 ? null : buf.toByteArray();
        }
    }
}
