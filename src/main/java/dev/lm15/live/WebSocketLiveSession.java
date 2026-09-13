package dev.lm15.live;

import dev.lm15.ProviderLM;
import dev.lm15.auth.Access;
import dev.lm15.auth.Credential;
import dev.lm15.errors.TransportError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonValue;
import dev.lm15.types.LiveClientEvent;
import dev.lm15.types.LiveServerEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;

/** The live session over {@code java.net.http.WebSocket}; frames go through the dialect's {@link LiveCodec}. */
public final class WebSocketLiveSession implements LiveSession {
    private static final Object CLOSED = new Object();

    private final WebSocket socket;
    private final LiveCodec codec;
    private final LinkedBlockingQueue<Object> frames = new LinkedBlockingQueue<>();
    private final ArrayDeque<LiveServerEvent> pending = new ArrayDeque<>();
    private final StringBuilder textBuffer = new StringBuilder();
    private volatile boolean closed;

    private WebSocketLiveSession(WebSocket socket, LiveCodec codec) {
        this.socket = socket;
        this.codec = codec;
    }

    static LiveSession open(ProviderLM lm, LiveCodec codec, Credential credential) {
        WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder();
        for (Map.Entry<String, String> h : codec.headers()) builder.header(h.getKey(), h.getValue());
        String url = codec.url();
        if (credential != null && !url.contains("key=")) {
            Map.Entry<String, String> auth = Access.authHeader(lm.policy(), credential, lm.dialect().apiKeyHeader());
            if (auth != null) builder.header(auth.getKey(), auth.getValue());
            else if (credential instanceof Credential.ApiKey k) url = url + (url.contains("?") ? "&" : "?") + "key=" + dev.lm15.wire.Wire.formEncode(k.value());
        }
        WebSocketLiveSession[] holder = new WebSocketLiveSession[1];
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override public void onOpen(WebSocket ws) { ws.request(1); }

            @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                WebSocketLiveSession s = holder[0];
                s.textBuffer.append(data);
                if (last) {
                    s.frames.add(s.textBuffer.toString());
                    s.textBuffer.setLength(0);
                }
                ws.request(1);
                return null;
            }

            @Override public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                holder[0].frames.add(bytes);
                ws.request(1);
                return null;
            }

            @Override public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                holder[0].frames.add(CLOSED);
                return null;
            }

            @Override public void onError(WebSocket ws, Throwable error) {
                holder[0].frames.add(error);
            }
        };
        WebSocket ws;
        try {
            ws = builder.buildAsync(URI.create(url), listener).join();
        } catch (RuntimeException e) {
            throw new TransportError("websocket connect failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
        WebSocketLiveSession session = new WebSocketLiveSession(ws, codec);
        holder[0] = session;
        for (JsonValue frame : codec.setupFrames()) session.sendFrame(Json.write(frame));
        return session;
    }

    private void sendFrame(String text) {
        try {
            socket.sendText(text, true).join();
        } catch (RuntimeException e) {
            throw new TransportError("websocket send failed: " + e.getMessage());
        }
    }

    @Override public synchronized void send(LiveClientEvent event) {
        if (closed) throw new IllegalStateException("live session is closed");
        for (JsonValue frame : codec.encode(event)) sendFrame(Json.write(frame));
    }

    @Override public LiveServerEvent receive() {
        while (true) {
            synchronized (pending) {
                if (!pending.isEmpty()) return pending.poll();
            }
            if (closed) return null;
            Object frame;
            try {
                frame = frames.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TransportError("interrupted");
            }
            if (frame == CLOSED) { closed = true; return null; }
            if (frame instanceof Throwable t) throw new TransportError("websocket failed: " + t.getMessage());
            byte[] raw = frame instanceof String s ? s.getBytes(StandardCharsets.UTF_8) : (byte[]) frame;
            List<LiveServerEvent> decoded = codec.decode(raw);
            synchronized (pending) { pending.addAll(decoded); }
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        } catch (RuntimeException ignored) {
            // closing a closed socket
        }
        frames.add(CLOSED);
    }
}
