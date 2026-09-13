package dev.lm15.types;

import dev.lm15.json.JsonObject;

import java.util.List;

/** An event the client sends on a live session (closed sum). */
public sealed interface LiveClientEvent {
    LiveClientEventType type();

    record Turn(List<Part> parts, boolean turnComplete) implements LiveClientEvent {
        public Turn {
            parts = Check.list(parts, "LiveClientTurnEvent.parts");
            if (parts.isEmpty()) throw ValidationException.value("LiveClientTurnEvent requires at least one part");
            for (Part p : parts) if (Part.isPromptForbidden(p)) throw ValidationException.type("LiveClientTurnEvent.parts cannot contain model/tool protocol parts");
        }
        public Turn(List<Part> parts) { this(parts, true); }
        public static Turn text(String t) { return new Turn(List.of(new TextPart(t)), true); }
        @Override public LiveClientEventType type() { return LiveClientEventType.TURN; }
    }

    record Audio(String data, String mediaType) implements LiveClientEvent {
        public static final String DEFAULT_MEDIA_TYPE = "audio/pcm;rate=16000";
        public Audio {
            Check.nonEmpty(data, "LiveClientAudioEvent.data");
            MediaPart.Media.validateBase64("LiveClientAudioEvent", data);
            if (mediaType == null) mediaType = DEFAULT_MEDIA_TYPE;
            Check.nonEmpty(mediaType, "LiveClientAudioEvent.media_type");
            if (!mediaType.startsWith("audio/")) throw ValidationException.value("LiveClientAudioEvent.media_type must start with 'audio/'");
        }
        public Audio(String data) { this(data, DEFAULT_MEDIA_TYPE); }
        @Override public LiveClientEventType type() { return LiveClientEventType.AUDIO; }
    }

    record Image(String data, String mediaType) implements LiveClientEvent {
        public static final String DEFAULT_MEDIA_TYPE = "image/jpeg";
        public Image {
            Check.nonEmpty(data, "LiveClientImageEvent.data");
            MediaPart.Media.validateBase64("LiveClientImageEvent", data);
            if (mediaType == null) mediaType = DEFAULT_MEDIA_TYPE;
            Check.nonEmpty(mediaType, "LiveClientImageEvent.media_type");
            if (!mediaType.startsWith("image/")) throw ValidationException.value("LiveClientImageEvent.media_type must start with 'image/'");
        }
        public Image(String data) { this(data, DEFAULT_MEDIA_TYPE); }
        @Override public LiveClientEventType type() { return LiveClientEventType.IMAGE; }
    }

    record Text(String text) implements LiveClientEvent {
        public Text { Check.text(text, "LiveClientTextEvent.text"); }
        @Override public LiveClientEventType type() { return LiveClientEventType.TEXT; }
    }

    record ToolResult(String id, List<Part> content) implements LiveClientEvent {
        public ToolResult {
            Check.nonEmpty(id, "LiveClientToolResultEvent.id");
            content = Check.list(content, "LiveClientToolResultEvent.content");
            if (content.isEmpty()) throw ValidationException.value("LiveClientToolResultEvent requires content");
            for (Part p : content) if (Part.isToolResultForbidden(p)) throw ValidationException.type("LiveClientToolResultEvent.content cannot contain model or protocol parts");
        }
        public ToolResult(String id, String output) { this(id, List.of(new TextPart(output))); }
        public ToolResult(String id, JsonObject output) { this(id, List.of(new TextPart(output.toJson()))); }
        @Override public LiveClientEventType type() { return LiveClientEventType.TOOL_RESULT; }
    }

    record Interrupt() implements LiveClientEvent {
        @Override public LiveClientEventType type() { return LiveClientEventType.INTERRUPT; }
    }

    record EndAudio() implements LiveClientEvent {
        @Override public LiveClientEventType type() { return LiveClientEventType.END_AUDIO; }
    }
}
