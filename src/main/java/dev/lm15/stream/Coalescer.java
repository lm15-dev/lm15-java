package dev.lm15.stream;

import dev.lm15.json.JsonObject;
import dev.lm15.types.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Enforces MAP-3 and MAP-4 over a raw adapter event sequence: one leading
 * start (synthesized with the request's model for dialects without a start
 * frame; duplicates dropped; an error never forces one) and exactly one
 * merged final end event — later non-null fields fill gaps, a null never
 * erases — whose {@code provider_data} follows D9 (the frame that supplied
 * usage, else the frame that supplied finish_reason). Push-based, so the
 * one-shot replay and the live stream share it.
 */
public final class Coalescer {
    private final String model;
    private boolean started;
    private boolean sawEnd;
    private FinishReason finishReason;
    private Usage usage;
    private JsonObject endData;
    private int endDataRank = -1;

    public Coalescer(String model) {
        this.model = model;
    }

    /** The events to emit for one raw event (zero, one or two). */
    public List<StreamEvent> push(StreamEvent event) {
        List<StreamEvent> out = new ArrayList<>(2);
        switch (event) {
            case StreamStartEvent s -> {
                if (!started) {
                    started = true;
                    out.add(s);
                }
            }
            case StreamEndEvent e -> {
                sawEnd = true;
                if (e.finishReason() != null) finishReason = e.finishReason();
                if (e.usage() != null) usage = e.usage();
                if (e.providerData() != null) {
                    int rank = e.usage() != null ? 2 : e.finishReason() != null ? 1 : 0;
                    if (rank >= endDataRank) {
                        endData = e.providerData();
                        endDataRank = rank;
                    }
                }
            }
            case StreamDeltaEvent d -> {
                if (!started) {
                    started = true;
                    out.add(new StreamStartEvent(null, model));
                }
                out.add(d);
            }
            case StreamErrorEvent err -> out.add(err);
        }
        return out;
    }

    /** The events to emit once the raw source is exhausted: the merged end (and a start when nothing else forced one). */
    public List<StreamEvent> finish() {
        List<StreamEvent> out = new ArrayList<>(2);
        if (sawEnd) {
            if (!started) {
                started = true;
                out.add(new StreamStartEvent(null, model));
            }
            out.add(new StreamEndEvent(finishReason, usage, endData));
        }
        return out;
    }

    /** One-shot: coalesce a whole raw sequence. */
    public static List<StreamEvent> coalesce(List<StreamEvent> raw, String model) {
        Coalescer c = new Coalescer(model);
        List<StreamEvent> out = new ArrayList<>();
        for (StreamEvent e : raw) out.addAll(c.push(e));
        out.addAll(c.finish());
        return out;
    }
}
