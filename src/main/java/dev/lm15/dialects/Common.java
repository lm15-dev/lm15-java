package dev.lm15.dialects;

import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.ProviderError;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBool;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonFloat;
import dev.lm15.json.JsonInt;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.types.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Helpers shared by the dialects (the reference's {@code lm15/providers/common.py}):
 * text rendering under MAP-10, media sources, the tool-result media policy,
 * timestamps, multipart bodies, the effort→budget table, logprob mapping.
 */
public final class Common {
    private Common() {}

    /** The part kinds that carry bytes/addresses rather than words (MAP-10: a native block or a raise; never text). */
    public static final Set<PartType> MEDIA_KINDS = Set.of(PartType.IMAGE, PartType.AUDIO, PartType.VIDEO, PartType.DOCUMENT, PartType.BINARY);

    /** MAP-7 rule 3: the one effort → thinking-budget grading table (Anthropic manual class, Gemini 2.5). */
    public static final Map<ReasoningEffort, Integer> EFFORT_THINKING_BUDGETS = Map.of(
        ReasoningEffort.MINIMAL, 1024, ReasoningEffort.LOW, 2048, ReasoningEffort.MEDIUM, 8192,
        ReasoningEffort.HIGH, 16384, ReasoningEffort.XHIGH, 24576, ReasoningEffort.MAX, 32768);

    // ─── text rendering (MAP-10) ───

    /**
     * Text rendering for wire fields that take text only: text, thinking and
     * citation parts render; a media part RAISES before any wire (MAP-10 rule 2).
     */
    public static String partsToText(List<? extends Part> parts, String provider, String where) {
        List<String> out = new ArrayList<>();
        for (Part part : parts) {
            if (MEDIA_KINDS.contains(part.type())) {
                String head = provider == null ? "" : provider + ": ";
                throw new UnsupportedFeatureError(head + "a " + part.type().wire() + " part cannot reach " + where
                    + ", which takes text only; no text rendering of a media part is made (MAP-10)", ErrorMeta.of(provider));
            }
            if (part instanceof TextPart t) out.add(t.text());
            else if (part instanceof ThinkingPart th && !th.text().isEmpty()) out.add(th.text());
            else if (part instanceof CitationPart c) {
                List<String> bits = new ArrayList<>();
                if (c.title() != null) bits.add(c.title());
                if (c.url() != null) bits.add(c.url());
                if (c.text() != null) bits.add(c.text());
                if (!bits.isEmpty()) out.add(String.join(" — ", bits));
            }
        }
        return String.join("\n", out);
    }

    public static String partsToText(List<? extends Part> parts) { return partsToText(parts, null, "a text-only wire field"); }

    public static String messageText(Message msg) { return partsToText(msg.parts()); }

    // ─── media sources ───

    /** The part's bytes as base64: inline data, or the path read now; a url/file_id part has no bytes here. */
    public static String mediaBase64(MediaPart part) {
        if (part.data() != null) return part.data();
        if (part.path() != null) return MediaPart.Media.encode(part.bytes());
        throw ValidationException.value(part.type().wire() + " part has no inline data or path");
    }

    public static String mediaDataUri(MediaPart part) {
        return "data:" + part.mediaType() + ";base64," + mediaBase64(part);
    }

    /** Anthropic's {@code source} block for an image/document/binary part. */
    public static JsonObject anthropicSource(MediaPart part) {
        if (part.url() != null) return Json.obj("type", "url", "url", part.url());
        if (part.fileId() != null) return Json.obj("type", "file", "file_id", part.fileId());
        if (part.data() != null) return Json.obj("type", "base64", "media_type", part.mediaType(), "data", part.data());
        if (part.path() != null) return Json.obj("type", "base64", "media_type", part.mediaType(), "data", MediaPart.Media.encode(part.bytes()));
        throw ValidationException.value(part.type().wire() + " part has no usable source");
    }

    // ─── MAP-10: tool-result media policy ───

    private static Set<PartType> admits(String policy) {
        return switch (policy) {
            case "native" -> Set.of(PartType.IMAGE, PartType.DOCUMENT);
            case "images" -> Set.of(PartType.IMAGE);
            default -> Set.of();
        };
    }

    private static String mediaDoor(PartType kind) {
        return switch (kind) {
            case IMAGE -> "the OpenAI Responses, Anthropic Messages and Gemini dialects (and the xai/moonshotai/zai chat presets)";
            case DOCUMENT -> "the OpenAI Responses, Anthropic Messages and Gemini dialects";
            default -> "no lm15 door yet";
        };
    }

    /** Raise before any wire when a result carries a part kind the preset's {@code tool_result_media} policy does not admit (MAP-10 rules 1–3). */
    public static void checkToolResultMedia(String provider, ToolResultPart part, String policy, String wire) {
        Set<PartType> admitted = admits(policy);
        for (Part p : part.content()) {
            if (MEDIA_KINDS.contains(p.type()) && !admitted.contains(p.type())) {
                String why = policy.equals("reject") ? "this server takes text-only tool results"
                    : "this server carries images but not " + p.type().wire() + " parts in a tool result";
                throw new UnsupportedFeatureError(provider + ": a " + p.type().wire() + " part in tool_result '" + part.id() + "' cannot reach " + wire + " — " + why
                    + " (compat tool_result_media='" + policy + "', measured: lm15-contract/research/tool-result-content/). "
                    + "Carried natively by " + mediaDoor(p.type()) + "; or render the part to text yourself before building the tool result (MAP-10)",
                    ErrorMeta.of(provider));
            }
        }
    }

    /** MAP-10 rule 5 on wires with no error flag: the text carries it. */
    public static String toolResultErrorText(ToolResultPart part, String text) {
        return part.isError() ? "[error] " + text : text;
    }

    public static boolean textOnly(List<? extends Part> parts) {
        for (Part p : parts) if (MEDIA_KINDS.contains(p.type())) return false;
        return true;
    }

    // ─── OpenAI-shaped helpers shared by both OpenAI dialects ───

    /** One prompt part → one Responses input block; a part with no slot RAISES (MAP-10). */
    public static JsonObject partToOpenAIInput(Part part, String provider) {
        switch (part) {
            case TextPart t -> { return Json.obj("type", "input_text", "text", t.text()); }
            case ImagePart img -> {
                JsonBuilder b = new JsonBuilder().put("type", "input_image");
                if (img.fileId() != null) b.put("file_id", img.fileId());
                else b.put("image_url", img.url() != null ? img.url() : mediaDataUri(img));
                if (img.detail() != null) b.put("detail", img.detail().wire());
                return b.build();
            }
            case AudioPart a -> {
                if (a.url() != null) return Json.obj("type", "input_audio", "audio_url", a.url());
                if (a.fileId() != null) return Json.obj("type", "input_audio", "file_id", a.fileId());
                String media = a.mediaType().contains("/") ? a.mediaType().substring(a.mediaType().indexOf('/') + 1) : a.mediaType();
                if (media.equals("mpeg") || media.equals("mp3")) media = "mp3";
                return Json.obj("type", "input_audio", "audio", mediaBase64(a), "format", media);
            }
            case DocumentPart d -> { return fileInput(d); }
            case BinaryPart b -> { return fileInput(b); }
            case VideoPart v -> {
                if (v.url() != null) return Json.obj("type", "input_video", "video_url", v.url());
                if (v.fileId() != null) return Json.obj("type", "input_video", "file_id", v.fileId());
                return Json.obj("type", "input_video", "video_data", mediaDataUri(v));
            }
            case CitationPart c -> { return Json.obj("type", "input_text", "text", partsToText(List.of(c))); }
            case ThinkingPart th -> { return Json.obj("type", "input_text", "text", partsToText(List.of(th))); }
            default -> {
                String head = provider == null ? "" : provider + ": ";
                throw new UnsupportedFeatureError(head + "a " + part.type().wire() + " part has no input block on the Responses wire (MAP-10)", ErrorMeta.of(provider));
            }
        }
    }

    private static JsonObject fileInput(MediaPart part) {
        if (part.url() != null) return Json.obj("type", "input_file", "file_url", part.url());
        if (part.fileId() != null) return Json.obj("type", "input_file", "file_id", part.fileId());
        // OpenAI requires a filename alongside inline file_data; derive it from the media-type subtype.
        String mt = part.mediaType();
        String ext = mt.contains("/") ? mt.substring(mt.indexOf('/') + 1) : mt;
        if (ext.contains("+")) ext = ext.substring(0, ext.indexOf('+'));
        if (ext.isEmpty()) ext = "bin";
        return Json.obj("type", "input_file", "filename", "file." + ext, "file_data", mediaDataUri(part));
    }

    /** {@code function_call_output.output}: a string when text-only, the documented block array otherwise (MAP-10). */
    public static JsonValue toolResultOutputOpenAI(String provider, ToolResultPart part, String policy) {
        checkToolResultMedia(provider, part, policy, "function_call_output");
        if (textOnly(part.content())) {
            return new JsonString(toolResultErrorText(part, partsToText(part.content(), provider, "function_call_output")));
        }
        List<JsonObject> blocks = new ArrayList<>();
        for (Part p : part.content()) blocks.add(partToOpenAIInput(p, provider));
        if (part.isError()) {
            int first = -1;
            for (int i = 0; i < blocks.size(); i++) if ("input_text".equals(blocks.get(i).optString("type"))) { first = i; break; }
            if (first < 0) blocks.add(0, Json.obj("type", "input_text", "text", "[error]"));
            else blocks.set(first, blocks.get(first).with("text", new JsonString("[error] " + blocks.get(first).optString("text"))));
        }
        return new JsonArray(new ArrayList<>(blocks));
    }

    /** OpenAI-style logprob entries ({token, logprob, bytes, top_logprobs}) → canonical; malformed entries are skipped. */
    public static List<TokenLogprob> openaiTokenLogprobs(JsonValue entries) {
        List<TokenLogprob> out = new ArrayList<>();
        if (!(entries instanceof JsonArray arr)) return out;
        for (JsonValue entry : arr) {
            if (!(entry instanceof JsonObject e) || !e.has("token") || !e.has("logprob")) continue;
            List<TopLogprob> top = new ArrayList<>();
            JsonValue tops = e.opt("top_logprobs");
            if (tops instanceof JsonArray ta) {
                for (JsonValue alt : ta) {
                    if (!(alt instanceof JsonObject a) || !a.has("token") || !a.has("logprob")) continue;
                    top.add(new TopLogprob(a.get("token").asString(), a.get("logprob").asDouble(), intList(a.opt("bytes")), null));
                }
            }
            out.add(new TokenLogprob(e.get("token").asString(), e.get("logprob").asDouble(), intList(e.opt("bytes")), null, top));
        }
        return out;
    }

    private static List<Integer> intList(JsonValue v) {
        if (!(v instanceof JsonArray a)) return null;
        List<Integer> out = new ArrayList<>();
        for (JsonValue x : a) out.add(x.asInt());
        return out;
    }

    /** MAP-9 on the complete path: a tool call the provider sent without a name is refused, never guessed. */
    public static ProviderError unnamedToolCallError(String provider, String path) {
        return new ProviderError(provider + ": " + path + " is a tool call with no name; lm15 does not guess which tool the model meant (MAP-9)", ErrorMeta.of(provider));
    }

    /** A JSON object from a wire value: an object as-is, a string parsed (partial text under partial_json, a non-object under value), else empty. */
    public static JsonObject parseJsonObject(JsonValue value) {
        if (value instanceof JsonObject o) return o;
        if (value instanceof JsonString s && !s.value().isEmpty()) {
            try {
                JsonValue parsed = Json.parse(s.value());
                return parsed instanceof JsonObject o ? o : Json.obj("value", parsed);
            } catch (RuntimeException e) {
                return Json.obj("partial_json", s.value());
            }
        }
        return JsonObject.EMPTY;
    }

    // ─── file readiness ───

    /** The OpenAI-shaped {@code status} fold (D6): uploaded|pending → pending, error|failed → failed, else ready. */
    public static FileReadiness openaiFileReadiness(JsonValue status) {
        if (!(status instanceof JsonString s)) return FileReadiness.READY;
        return switch (s.value()) {
            case "uploaded", "pending" -> FileReadiness.PENDING;
            case "error", "failed" -> FileReadiness.FAILED;
            default -> FileReadiness.READY;
        };
    }

    // ─── timestamps ───

    private static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /** A provider timestamp (unix epoch number or ISO-8601 string) → canonical {@code YYYY-MM-DDTHH:MM:SSZ}; null when unparseable. */
    public static String isoUtc(JsonValue value) {
        try {
            if (value == null || value instanceof JsonBool) return null;
            if (value instanceof JsonInt i) return ISO_UTC.format(Instant.ofEpochSecond(i.value().longValueExact()));
            if (value instanceof JsonFloat f) return ISO_UTC.format(Instant.ofEpochMilli((long) Math.floor(f.value() * 1000)));
            if (value instanceof JsonString s && !s.value().isEmpty()) {
                String text = s.value().strip();
                if (text.endsWith("Z")) text = text.substring(0, text.length() - 1) + "+00:00";
                text = text.replaceAll("\\.(\\d{6})\\d+", ".$1");
                Instant when;
                try {
                    when = OffsetDateTime.parse(text).toInstant();
                } catch (RuntimeException e) {
                    when = java.time.LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
                }
                return ISO_UTC.format(when);
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ─── model listing ───

    /** Map list-models entries to ModelInfo: {@code idOf} extracts the usable id; the wire entry rides under origin.provider_data; no id → skipped. */
    public static List<ModelInfo> modelInfosFromEntries(JsonValue entries, String provider, String apiFamily, java.util.function.Function<JsonObject, String> idOf) {
        List<ModelInfo> out = new ArrayList<>();
        if (!(entries instanceof JsonArray arr)) return out;
        for (JsonValue entry : arr) {
            if (!(entry instanceof JsonObject e)) continue;
            String id = idOf.apply(e);
            if (id == null || id.isEmpty()) continue;
            out.add(new ModelInfo(id, provider, apiFamily, List.of(), new ModelOrigin("provider", null, null, e), null, null));
        }
        return out;
    }

    // ─── multipart ───

    /** A multipart/form-data body; returns {content-type, body}. {@code files} entries are (field, filename, contentType, bytes). */
    public static Map.Entry<String, byte[]> multipartFormBody(List<Map.Entry<String, String>> fields, List<FilePart> files) {
        String boundary = "lm15-" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (fields != null) {
            for (Map.Entry<String, String> f : fields) {
                out.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.writeBytes(("Content-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.writeBytes((f.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        if (files != null) {
            for (FilePart f : files) {
                String safe = f.filename().replace("\"", "%22");
                out.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.writeBytes(("Content-Disposition: form-data; name=\"" + f.field() + "\"; filename=\"" + safe + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                out.writeBytes(("Content-Type: " + f.contentType() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.writeBytes(f.data());
                out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return Map.entry("multipart/form-data; boundary=" + boundary, out.toByteArray());
    }

    public record FilePart(String field, String filename, String contentType, byte[] data) {}

    /** A multipart/related body (Gemini media upload): JSON metadata then one media part. */
    public static Map.Entry<String, byte[]> multipartRelatedBody(JsonObject metadata, String mediaType, byte[] data) {
        String boundary = "lm15-" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes("Content-Type: application/json; charset=UTF-8\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.writeBytes(Json.writeBytes(metadata));
        out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("Content-Type: " + mediaType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(data);
        out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return Map.entry("multipart/related; boundary=" + boundary, out.toByteArray());
    }

    // ─── unmapped recorder ───

    /** Record one unmapped response element ({@code {path, type}}) on a provider_data builder (PROTOCOL.md § Unmapped recorder). */
    public static void recordUnmapped(List<JsonValue> recorder, String path, JsonValue type) {
        String t;
        if (type == null || type instanceof dev.lm15.json.JsonNull) t = "<missing>";
        else if (type instanceof JsonString s) t = s.value().isEmpty() ? "<missing>" : s.value();
        else if (type instanceof JsonBool b) t = b.value() ? "True" : "<missing>";
        else if (type instanceof JsonObject) t = "dict";
        else if (type instanceof JsonArray) t = "list";
        else t = type.toJson();
        recorder.add(Json.obj("path", path, "type", t));
    }
}
