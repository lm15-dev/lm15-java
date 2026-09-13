package dev.lm15.transport;

import dev.lm15.wire.TransportRequest;

import java.io.InputStream;

/**
 * The network seam. {@link #send} buffers the reply; {@link #stream} opens
 * it for incremental reading (SSE). Implementations translate their own
 * failures into {@link dev.lm15.errors.TransportError}.
 */
public interface Transport extends AutoCloseable {
    HttpResponse send(TransportRequest request);

    /** An open response: status, headers and a body stream the caller closes. */
    record Streaming(int status, java.util.List<java.util.Map.Entry<String, String>> headers, InputStream body) implements AutoCloseable {
        @Override public void close() {
            try {
                body.close();
            } catch (java.io.IOException error) {
                throw new dev.lm15.errors.TransportError("response stream close failed");
            }
        }
    }

    Streaming stream(TransportRequest request);

    @Override default void close() {}
}
