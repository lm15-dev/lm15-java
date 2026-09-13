package dev.lm15.types;

import dev.lm15.json.JsonObject;

/** An event the server sends on a live session (closed sum). */
public sealed interface LiveServerEvent {
    LiveServerEventType type();

    record Audio(String data, String mediaType) implements LiveServerEvent {
        public Audio {
            Check.nonEmpty(data, "LiveServerAudioEvent.data");
            MediaPart.Media.validateBase64("LiveServerAudioEvent", data);
            Check.optNonEmpty(mediaType, "LiveServerAudioEvent.media_type");
            if (mediaType != null && !mediaType.startsWith("audio/")) throw ValidationException.value("LiveServerAudioEvent.media_type must start with 'audio/'");
        }
        @Override public LiveServerEventType type() { return LiveServerEventType.AUDIO; }
    }

    record Text(String text) implements LiveServerEvent {
        public Text { Check.text(text, "LiveServerTextEvent.text"); }
        @Override public LiveServerEventType type() { return LiveServerEventType.TEXT; }
    }

    record ToolCall(String id, String name, JsonObject input) implements LiveServerEvent {
        public ToolCall {
            Check.nonEmpty(id, "LiveServerToolCallEvent.id");
            Check.nonEmpty(name, "LiveServerToolCallEvent.name");
            Check.jsonObject(input, "input", true);
        }
        @Override public LiveServerEventType type() { return LiveServerEventType.TOOL_CALL; }
    }

    record ToolCallDelta(String inputDelta, String id, String name) implements LiveServerEvent {
        public ToolCallDelta {
            Check.text(inputDelta, "LiveServerToolCallDeltaEvent.input_delta");
            Check.optNonEmpty(id, "LiveServerToolCallDeltaEvent.id");
            Check.optNonEmpty(name, "LiveServerToolCallDeltaEvent.name");
        }
        @Override public LiveServerEventType type() { return LiveServerEventType.TOOL_CALL_DELTA; }
    }

    record Interrupted() implements LiveServerEvent {
        @Override public LiveServerEventType type() { return LiveServerEventType.INTERRUPTED; }
    }

    record TurnEnd(Usage usage) implements LiveServerEvent {
        public TurnEnd { if (usage == null) throw ValidationException.type("LiveServerTurnEndEvent.usage must be a Usage"); }
        @Override public LiveServerEventType type() { return LiveServerEventType.TURN_END; }
    }

    /** Billed usage for a response that did not end the turn; never a turn boundary. */
    record UsageEvent(Usage usage) implements LiveServerEvent {
        public UsageEvent { if (usage == null) throw ValidationException.type("LiveServerUsageEvent.usage must be a Usage"); }
        @Override public LiveServerEventType type() { return LiveServerEventType.USAGE; }
    }

    record Error(ErrorDetail error) implements LiveServerEvent {
        public Error { if (error == null) throw ValidationException.type("LiveServerErrorEvent.error must be an ErrorDetail"); }
        @Override public LiveServerEventType type() { return LiveServerEventType.ERROR; }
    }
}
