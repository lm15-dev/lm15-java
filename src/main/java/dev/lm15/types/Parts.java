package dev.lm15.types;

import dev.lm15.json.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Factory functions for the common construction patterns (spec/types.md
 * "Factory" rows): {@code Parts.text("hi")}, {@code Parts.image(...)},
 * {@code Parts.toolResult(id, "output")}.
 */
public final class Parts {
    private Parts() {}

    public static TextPart text(String content) { return new TextPart(content); }
    public static ThinkingPart thinking(String content) { return new ThinkingPart(content); }
    public static RefusalPart refusal(String content) { return new RefusalPart(content); }
    public static CitationPart citation(String url, String title, String text) { return new CitationPart(url, title, text); }

    public static ToolCallPart toolCall(String id, String name, JsonObject input) { return new ToolCallPart(id, name, input); }

    /** A tool result whose content is one text part (an empty string is one empty TextPart, INV-014). */
    public static ToolResultPart toolResult(String id, String output) {
        return new ToolResultPart(id, List.of(new TextPart(output)));
    }

    public static ToolResultPart toolResult(String id, String output, boolean isError) {
        return new ToolResultPart(id, List.of(new TextPart(output)), null, isError, List.of());
    }

    public static ToolResultPart toolResult(String id, List<Part> content) { return new ToolResultPart(id, content); }

    public static ToolResultPart toolResult(String id, Part... content) { return new ToolResultPart(id, Arrays.asList(content)); }

    /** A media address: exactly one of the four. */
    public record Address(String data, String url, String fileId, Path path) {
        public static Address ofData(byte[] bytes) { return new Address(MediaPart.Media.encode(bytes), null, null, null); }
        public static Address ofData(String base64) { return new Address(base64, null, null, null); }
        public static Address ofUrl(String url) { return new Address(null, url, null, null); }
        public static Address ofFileId(String fileId) { return new Address(null, null, fileId, null); }
        public static Address ofPath(Path path) { return new Address(null, null, null, path); }
        public static Address ofPath(String path) {
            if (path.isEmpty()) throw ValidationException.value("path cannot be empty");
            return new Address(null, null, null, Path.of(path));
        }
    }

    private static String resolveMediaType(Address a, String mediaType, String fallback) {
        if (mediaType != null) return mediaType;
        if (a.path() != null) {
            String guessed = MediaPart.Media.guessMediaType(a.path());
            if (guessed != null) return guessed;
        }
        return fallback;
    }

    public static ImagePart image(Address a) { return image(a, null, null); }
    public static ImagePart image(Address a, String mediaType) { return image(a, mediaType, null); }
    public static ImagePart image(Address a, String mediaType, ImageDetail detail) {
        return new ImagePart(resolveMediaType(a, mediaType, ImagePart.DEFAULT_MEDIA_TYPE), a.data(), a.url(), a.fileId(), a.path(), detail, List.of());
    }

    public static AudioPart audio(Address a) { return audio(a, null); }
    public static AudioPart audio(Address a, String mediaType) {
        return new AudioPart(resolveMediaType(a, mediaType, AudioPart.DEFAULT_MEDIA_TYPE), a.data(), a.url(), a.fileId(), a.path(), List.of());
    }

    public static VideoPart video(Address a) { return video(a, null); }
    public static VideoPart video(Address a, String mediaType) {
        return new VideoPart(resolveMediaType(a, mediaType, VideoPart.DEFAULT_MEDIA_TYPE), a.data(), a.url(), a.fileId(), a.path(), List.of());
    }

    public static DocumentPart document(Address a) { return document(a, null); }
    public static DocumentPart document(Address a, String mediaType) {
        return new DocumentPart(resolveMediaType(a, mediaType, DocumentPart.DEFAULT_MEDIA_TYPE), a.data(), a.url(), a.fileId(), a.path(), List.of());
    }

    public static BinaryPart binary(Address a) { return binary(a, null); }
    public static BinaryPart binary(Address a, String mediaType) {
        return new BinaryPart(resolveMediaType(a, mediaType, BinaryPart.DEFAULT_MEDIA_TYPE), a.data(), a.url(), a.fileId(), a.path(), List.of());
    }

    /** Text of every text-bearing part joined with newlines; media parts are skipped (MAP-10.2 forbids rendering them). */
    public static String textOf(List<? extends Part> parts) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Part p : parts) {
            if (p instanceof TextPart t) {
                if (!first) sb.append('\n');
                sb.append(t.text());
                first = false;
            }
        }
        return sb.toString();
    }

    /** INV-021: a string becomes one TextPart. */
    public static List<Part> normalize(String content) {
        return List.of(new TextPart(content));
    }

    public static List<Part> normalize(Part part) {
        return List.of(part);
    }

    public static List<Part> normalize(List<? extends Part> parts) {
        if (parts == null || parts.isEmpty()) throw ValidationException.value("content sequence cannot be empty");
        return new ArrayList<>(parts);
    }
}
