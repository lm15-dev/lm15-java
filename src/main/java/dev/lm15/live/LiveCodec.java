package dev.lm15.live;

import dev.lm15.json.JsonValue;
import dev.lm15.types.LiveClientEvent;
import dev.lm15.types.LiveServerEvent;

import java.util.List;
import java.util.Map;

/**
 * The live websocket codec — three pure transformations, no socket (the
 * contract's {@code replay_live}): the setup frames sent at connect time,
 * the wire frames for one client event, and the canonical events for one
 * server frame (an empty list = a housekeeping frame, deliberately ignored).
 */
public interface LiveCodec {
    /** The websocket URL and the headers to connect with. */
    String url();

    List<Map.Entry<String, String>> headers();

    List<JsonValue> setupFrames();

    List<JsonValue> encode(LiveClientEvent event);

    List<LiveServerEvent> decode(byte[] frame);

    /** True when this frame is the provider's setup acknowledgement. */
    default boolean isSetupComplete(byte[] frame) { return false; }
}
