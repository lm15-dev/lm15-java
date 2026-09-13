package dev.lm15.transport;

import dev.lm15.errors.TransportError;
import dev.lm15.errors.TimeoutError;
import dev.lm15.wire.TransportRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The default transport over {@code java.net.http} (JDK 11+): zero dependencies. */
public final class HttpTransport implements Transport {
    private final HttpClient client;
    private final Duration readTimeout;
    private final boolean ownsClient;

    public HttpTransport() { this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(), Duration.ofSeconds(600), true); }

    /** A caller-supplied HttpClient remains owned by its caller. */
    public HttpTransport(HttpClient client, Duration readTimeout) { this(client, readTimeout, false); }

    private HttpTransport(HttpClient client, Duration readTimeout, boolean ownsClient) {
        this.client = java.util.Objects.requireNonNull(client);
        this.readTimeout = java.util.Objects.requireNonNull(readTimeout);
        if (readTimeout.isZero() || readTimeout.isNegative()) throw new IllegalArgumentException("read timeout must be positive");
        this.ownsClient = ownsClient;
    }

    private HttpRequest build(TransportRequest request) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(request.fullUrl()));
        byte[] body = request.bodyBytes();
        b.method(request.method(), body.length == 0 && !request.hasBody() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        for (Map.Entry<String, String> h : request.headers()) {
            String name = h.getKey().toLowerCase();
            if (name.equals("host") || name.equals("content-length") || name.equals("connection")) continue;
            b.header(h.getKey(), h.getValue());
        }
        b.timeout(request.readTimeout() != null ? request.readTimeout() : readTimeout);
        return b.build();
    }

    private static List<Map.Entry<String, String>> headersOf(java.net.http.HttpResponse<?> resp) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        resp.headers().map().forEach((k, vs) -> { for (String v : vs) out.add(Map.entry(k, v)); });
        return out;
    }

    @Override public HttpResponse send(TransportRequest request) {
        try {
            java.net.http.HttpResponse<byte[]> resp = client.send(build(request), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            return new HttpResponse(resp.statusCode(), headersOf(resp), resp.body());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new TimeoutError("HTTP request timed out");
        } catch (IOException e) {
            throw new TransportError(e.getMessage() == null ? e.toString() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportError("interrupted");
        }
    }

    @Override public Streaming stream(TransportRequest request) {
        try {
            java.net.http.HttpResponse<InputStream> resp = client.send(build(request), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            Duration timeout = request.readTimeout() != null ? request.readTimeout() : readTimeout;
            return new Streaming(resp.statusCode(), headersOf(resp), new TimedInputStream(resp.body(), timeout));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new TimeoutError("HTTP request timed out");
        } catch (IOException e) {
            throw new TransportError(e.getMessage() == null ? e.toString() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportError("interrupted");
        }
    }

    @Override public void close() { if (ownsClient) client.close(); }
}
