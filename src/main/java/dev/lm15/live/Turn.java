package dev.lm15.live;

import dev.lm15.types.ErrorDetail;
import dev.lm15.types.LiveServerEvent;
import dev.lm15.types.Usage;

import java.util.List;

/**
 * One materialized live turn (LIVE-1 boundary, LIVE-2 bill): {@code endedBy}
 * is turn_end / interrupted / error / tool_call; text and audio are
 * concatenated; usage is the field-wise sum of every usage and turn_end
 * event the turn saw; {@code ok} is {@code endedBy == turn_end}.
 */
public record Turn(String endedBy, String text, byte[] audio, String audioMediaType, List<LiveServerEvent.ToolCall> toolCalls,
                   Usage usage, ErrorDetail error, List<LiveServerEvent> events) {
    public boolean ok() { return "turn_end".equals(endedBy); }
}
