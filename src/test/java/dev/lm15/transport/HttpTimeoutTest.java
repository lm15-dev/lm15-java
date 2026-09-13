package dev.lm15.transport;

import com.sun.net.httpserver.HttpServer;
import dev.lm15.errors.TimeoutError;
import dev.lm15.sse.Sse;
import dev.lm15.wire.TransportRequest;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class HttpTimeoutTest {
    static final class StalledServer implements AutoCloseable {
        final HttpServer server;
        final CountDownLatch release = new CountDownLatch(1);
        StalledServer(boolean headers) throws Exception {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext("/", exchange -> {
                try {
                    if (headers) { exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().flush(); }
                    release.await();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
        }
        TransportRequest request() {
            return new TransportRequest("GET", "http://127.0.0.1:" + server.getAddress().getPort() + "/", List.of(), List.of(), null, null, null);
        }
        public void close() { release.countDown(); server.stop(0); }
    }

    @Test void headerTimeoutKeepsTheTimeoutErrorClass() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient(); StalledServer server = new StalledServer(false)) {
            HttpTransport transport = new HttpTransport(client, Duration.ofMillis(200));
            assertThrows(TimeoutError.class, () -> transport.send(server.request()));
        }
    }

    @Test void streamingBodyCannotHangAfterHeadersHaveArrived() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (HttpClient client = HttpClient.newHttpClient(); StalledServer server = new StalledServer(true)) {
                HttpTransport transport = new HttpTransport(client, Duration.ofSeconds(1));
                try (Transport.Streaming response = transport.stream(server.request())) {
                    assertEquals(200, response.status());
                    assertThrows(TimeoutError.class, () -> Sse.iterate(response.body()).hasNext());
                }
            }
        });
    }
}
