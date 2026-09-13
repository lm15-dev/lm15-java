package dev.lm15.live;

import dev.lm15.json.Json;
import dev.lm15.types.ErrorCode;
import dev.lm15.types.ErrorDetail;
import dev.lm15.types.LiveClientEvent;
import dev.lm15.types.LiveServerEvent;
import dev.lm15.types.Usage;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** LIVE-1 (turn boundary) and LIVE-2 (the bill) over a scripted session. */
class TurnViewTest {
    private static LiveSession scripted(LiveServerEvent... events) {
        ArrayDeque<LiveServerEvent> q = new ArrayDeque<>(List.of(events));
        return new LiveSession() {
            @Override public void send(LiveClientEvent event) {}
            @Override public LiveServerEvent receive() { return q.poll(); }
            @Override public void close() {}
        };
    }

    @Test void resultStopsAtToolCallAndSumsUsage() {
        LiveSession s = scripted(
            new LiveServerEvent.Text("hi "), new LiveServerEvent.ToolCall("c1", "f", Json.obj()),
            new LiveServerEvent.UsageEvent(new Usage(10, 5)), new LiveServerEvent.Text("done"), new LiveServerEvent.TurnEnd(new Usage(3, 4)));
        Turn first = s.turn().result();
        assertEquals("tool_call", first.endedBy());
        assertFalse(first.ok());
        assertEquals(1, first.toolCalls().size());
        assertNull(first.usage());
        Turn second = s.turn().result();                       // the continuation turn carries the tool-call response's tokens
        assertEquals("turn_end", second.endedBy());
        assertTrue(second.ok());
        assertEquals("done", second.text());
        assertEquals(new Usage(13, 9), second.usage());
    }

    @Test void iterationYieldsToolCallMidTurn() {
        LiveSession s = scripted(new LiveServerEvent.ToolCall("c1", "f", Json.obj()), new LiveServerEvent.Text("x"), new LiveServerEvent.Interrupted());
        TurnView view = s.turn();
        int n = 0;
        for (LiveServerEvent e : view) n++;
        assertEquals(3, n);                                     // a tool_call does not end iteration
        assertEquals("interrupted", view.result().endedBy());
    }

    @Test void errorAndAudio() {
        LiveSession s = scripted(new LiveServerEvent.Audio("AAAA", "audio/pcm"), new LiveServerEvent.Audio("AAAA", null),
            new LiveServerEvent.Error(new ErrorDetail(ErrorCode.SERVER, "boom")));
        Turn t = s.turn().result();
        assertEquals("error", t.endedBy());
        assertEquals(6, t.audio().length);
        assertEquals("audio/pcm", t.audioMediaType());
        assertEquals("boom", t.error().message());
        assertThrows(dev.lm15.errors.TransportError.class, () -> scripted(new LiveServerEvent.Text("a")).turn().result());
    }
}
