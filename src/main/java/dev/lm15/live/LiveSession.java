package dev.lm15.live;

import dev.lm15.ProviderLM;
import dev.lm15.auth.Credential;
import dev.lm15.types.LiveClientEvent;
import dev.lm15.types.LiveServerEvent;
import dev.lm15.types.Part;
import dev.lm15.types.TextPart;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A live (realtime) session over a websocket: send canonical client events,
 * receive canonical server events, {@link #turn()} for one materialized
 * turn (LIVE-1/LIVE-2). The codec is the contract; the socket is Java's.
 */
public interface LiveSession extends AutoCloseable, Iterable<LiveServerEvent> {
    void send(LiveClientEvent event);

    /** The next server event, or null once the session is closed. */
    LiveServerEvent receive();

    default TurnView turn() { return new TurnView(this); }

    default void sendText(String text) { send(new LiveClientEvent.Text(text)); }
    default void sendTurn(String text) { send(LiveClientEvent.Turn.text(text)); }
    default void sendTurn(List<Part> parts, boolean turnComplete) { send(new LiveClientEvent.Turn(parts, turnComplete)); }
    default void sendAudio(byte[] pcm, String mediaType) { send(new LiveClientEvent.Audio(java.util.Base64.getEncoder().encodeToString(pcm), mediaType)); }
    default void sendImage(byte[] bytes, String mediaType) { send(new LiveClientEvent.Image(java.util.Base64.getEncoder().encodeToString(bytes), mediaType)); }
    default void sendToolResult(String callId, String output) { send(new LiveClientEvent.ToolResult(callId, List.of(new TextPart(output)))); }
    default void sendToolResults(Map<String, String> results) { for (Map.Entry<String, String> e : results.entrySet()) sendToolResult(e.getKey(), e.getValue()); }
    default void interrupt() { send(new LiveClientEvent.Interrupt()); }
    default void endAudio() { send(new LiveClientEvent.EndAudio()); }

    @Override default Iterator<LiveServerEvent> iterator() {
        return new Iterator<>() {
            private LiveServerEvent next;
            @Override public boolean hasNext() {
                if (next == null) next = receive();
                return next != null;
            }
            @Override public LiveServerEvent next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                LiveServerEvent e = next;
                next = null;
                return e;
            }
        };
    }

    @Override void close();

    static LiveSession open(ProviderLM lm, LiveCodec codec, Credential credential) {
        return WebSocketLiveSession.open(lm, codec, credential);
    }
}
