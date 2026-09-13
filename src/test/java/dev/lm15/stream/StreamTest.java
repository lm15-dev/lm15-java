package dev.lm15.stream;

import dev.lm15.errors.RateLimitError;
import dev.lm15.errors.StreamAssemblyError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonObject;
import dev.lm15.types.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StreamTest {
    private static final Request REQ = new Request("m", List.of(Message.user("hi")));

    private static StreamDeltaEvent d(Delta delta) { return new StreamDeltaEvent(delta); }

    @Test void coalescerMergesEndsAndSynthesizesStart() {
        JsonObject finishFrame = Json.obj("kind", "finish");
        JsonObject usageFrame = Json.obj("kind", "usage");
        List<StreamEvent> raw = List.of(
            d(new TextDelta("he", 0)), d(new TextDelta("llo", 0)),
            new StreamEndEvent(FinishReason.STOP, null, finishFrame),
            new StreamEndEvent(null, new Usage(1, 2), usageFrame),
            new StreamEndEvent(null, null, null));
        List<StreamEvent> out = Coalescer.coalesce(raw, "m");
        assertEquals(4, out.size());
        assertEquals(new StreamStartEvent(null, "m"), out.get(0));                       // MAP-4
        StreamEndEvent end = (StreamEndEvent) out.get(3);                                // MAP-3
        assertEquals(FinishReason.STOP, end.finishReason());
        assertEquals(new Usage(1, 2), end.usage());
        assertEquals(usageFrame, end.providerData());                                   // D9: the usage frame wins
        // a provider start passes through once; duplicates drop; no end → no end
        List<StreamEvent> out2 = Coalescer.coalesce(List.of(new StreamStartEvent("id", "x"), new StreamStartEvent("id2", "y"), d(new TextDelta("a"))), "m");
        assertEquals(2, out2.size());
        assertEquals("id", ((StreamStartEvent) out2.get(0)).id());
        // an error never forces a start
        assertEquals(1, Coalescer.coalesce(List.of(new StreamErrorEvent(new ErrorDetail(ErrorCode.SERVER, "x"))), "m").size());
    }

    @Test void accumulatorAssemblesInKindOrderAndMintsIds() {
        StreamAccumulator acc = new StreamAccumulator(REQ);
        acc.push(new StreamStartEvent("r1", "served"));
        acc.push(d(new ToolCallDelta("{\"a\":", 0, null, "f")));
        acc.push(d(new TextDelta("hi", 0)));
        acc.push(d(new ToolCallDelta("1}", 0)));
        acc.push(d(new ThinkingDelta("t", 0)));
        acc.push(d(new ContinuationDelta("openai", "k", Json.obj("x", 1), 0)));
        acc.push(d(new ContinuationDelta("openai", "msg", JsonObject.EMPTY, null)));
        acc.push(d(new TextDelta("", 3)));
        acc.push(new StreamEndEvent(FinishReason.STOP, new Usage(1, 1), null));
        Response r = acc.response();
        assertEquals("r1", r.id());
        assertEquals("served", r.model());
        List<Part> parts = r.message().parts();
        assertEquals(4, parts.size());
        assertInstanceOf(ThinkingPart.class, parts.get(0));
        assertInstanceOf(TextPart.class, parts.get(1));
        ToolCallPart call = (ToolCallPart) parts.get(2);
        assertEquals("tool_call_0", call.id());                                          // MAP-9: minted id
        assertEquals(Json.obj("a", 1), call.input());
        assertEquals(1, call.continuation().size());
        assertEquals(1, r.message().continuation().size());
        assertEquals(FinishReason.TOOL_CALL, r.finishReason());                          // stop + tool call → tool_call
    }

    @Test void unnamedToolCallRefusesWithPartial() {
        StreamAccumulator acc = new StreamAccumulator(REQ);
        acc.push(d(new TextDelta("text", 0)));
        acc.push(d(new ToolCallDelta("{}", 1, "id1", null)));
        acc.push(new StreamEndEvent(FinishReason.TOOL_CALL, null, null));
        StreamAssemblyError e = assertThrows(StreamAssemblyError.class, acc::response);
        assertEquals(1, e.partIndex());
        assertEquals("text", e.partial().text());
        assertEquals(FinishReason.TOOL_CALL, e.partial().finishReason());
    }

    @Test void emptySlotAndEmptyMessage() {
        StreamAccumulator acc = new StreamAccumulator(REQ);
        acc.push(d(new ContinuationDelta("anthropic", "sig", JsonObject.EMPTY, 2)));
        acc.push(new StreamEndEvent(FinishReason.LENGTH, null, null));
        Response r = acc.response();
        assertEquals(1, r.message().parts().size());
        assertEquals("", ((TextPart) r.message().parts().get(0)).text());
        assertEquals(1, r.message().parts().get(0).continuation().size());
        assertEquals("", new StreamAccumulator(REQ).response().text());                 // MAP-2
    }

    @Test void materializeRequiresAnEnd() {
        StreamAssemblyError e = assertThrows(StreamAssemblyError.class, () -> Streams.materialize(List.of(new StreamStartEvent(null, "m"), d(new TextDelta("a"))), REQ));
        assertEquals("a", e.partial().text());
        assertThrows(StreamAssemblyError.class, () -> Streams.materialize(List.of(new StreamEndEvent(FinishReason.STOP, null, null), d(new TextDelta("late"))), REQ));
        assertThrows(RateLimitError.class, () -> Streams.materialize(List.of(new StreamErrorEvent(new ErrorDetail(ErrorCode.RATE_LIMIT, "slow"))), REQ));
        Response ok = Streams.materialize(List.of(new StreamStartEvent(null, "m"), d(new TextDelta("a")), new StreamEndEvent(FinishReason.STOP, new Usage(1, 1), null)), REQ);
        assertEquals("a", ok.text());
    }

    @Test void responseStreamYieldsTextThenResponse() {
        List<StreamEvent> events = List.of(new StreamStartEvent(null, "m"), d(new TextDelta("a")), d(new TextDelta("b")), new StreamEndEvent(FinishReason.STOP, null, null));
        ResponseStream rs = new ResponseStream(events.iterator(), REQ);
        StringBuilder sb = new StringBuilder();
        for (String t : rs) sb.append(t);
        assertEquals("ab", sb.toString());
        assertEquals("ab", rs.response().text());
        // closing early: no complete response
        ResponseStream early = new ResponseStream(events.iterator(), REQ);
        early.iterator().next();
        early.close();
        assertThrows(StreamAssemblyError.class, early::response);
        // a source failure after completion is a warning, not an error
        List<Throwable> warned = new ArrayList<>();
        StreamWarnings.setHandler(warned::add);
        Iterator<StreamEvent> failing = new Iterator<>() {
            final Iterator<StreamEvent> it = events.iterator();
            @Override public boolean hasNext() { if (it.hasNext()) return true; throw new dev.lm15.errors.TransportError("drain failed"); }
            @Override public StreamEvent next() { return it.next(); }
        };
        ResponseStream rs2 = new ResponseStream(failing, REQ);
        assertEquals("ab", rs2.response().text());
        assertEquals(1, warned.size());
        assertEquals(1, rs2.cleanupErrors().size());
        StreamWarnings.setHandler(null);
    }

    @Test void responseToEventsRoundTrips() {
        Message m = new Message(Role.ASSISTANT, List.of(new ThinkingPart("t"), new TextPart("a"), new ToolCallPart("id", "f", Json.obj("x", 1))),
            List.of(new ContinuationState("openai", "k")));
        Response r = new Response("id", "m", m, FinishReason.TOOL_CALL, new Usage(1, 2), null, null);
        Response back = Streams.materialize(Streams.responseToEvents(r), REQ);
        assertEquals(r, back);
    }
}
