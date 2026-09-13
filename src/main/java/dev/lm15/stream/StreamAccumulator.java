package dev.lm15.stream;

import dev.lm15.errors.StreamAssemblyError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.types.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Accumulates canonical stream events into a complete Response — the
 * push-based engine every port shares (MAP-9 assembly algorithm). Feed it
 * events, ask it for the Response.
 */
public final class StreamAccumulator {
    private final Request request;
    private String startedId;
    private String startedModel;
    private FinishReason finishReason;
    private Usage usage;
    private final TreeMap<Integer, StringBuilder> textParts = new TreeMap<>();
    private final TreeMap<Integer, StringBuilder> thinkingParts = new TreeMap<>();
    private final TreeMap<Integer, List<String>> audioChunks = new TreeMap<>();
    private final Map<Integer, String> audioMediaTypes = new LinkedHashMap<>();
    private final TreeMap<Integer, ImagePart> imageParts = new TreeMap<>();
    private final TreeMap<Integer, List<CitationPart>> citationParts = new TreeMap<>();
    private final TreeMap<Integer, StringBuilder> toolCallRaw = new TreeMap<>();
    private final TreeMap<Integer, ToolMeta> toolCallMeta = new TreeMap<>();
    private final List<ContinuationState> messageContinuation = new ArrayList<>();
    private final TreeMap<Integer, List<ContinuationState>> partContinuation = new TreeMap<>();
    private final List<TokenLogprob> logprobs = new ArrayList<>();
    private JsonObject providerData;

    private static final class ToolMeta {
        String id;
        String name;
    }

    public StreamAccumulator(Request request) {
        this.request = request;
    }

    /** Fold one canonical stream event into the accumulated state (error events are ignored here). */
    public void push(StreamEvent event) {
        switch (event) {
            case StreamStartEvent s -> {
                if (s.id() != null) startedId = s.id();
                if (s.model() != null) startedModel = s.model();
            }
            case StreamEndEvent e -> {
                if (e.finishReason() != null) finishReason = e.finishReason();
                if (e.usage() != null) usage = e.usage();
                if (e.providerData() != null) providerData = e.providerData();
            }
            case StreamErrorEvent e -> { /* the caller decides */ }
            case StreamDeltaEvent d -> pushDelta(d.delta());
        }
    }

    private void pushDelta(Delta delta) {
        switch (delta) {
            case TextDelta t -> {
                textParts.computeIfAbsent(t.partIndex(), k -> new StringBuilder()).append(t.text());
                logprobs.addAll(t.logprobs());
            }
            case ThinkingDelta t -> thinkingParts.computeIfAbsent(t.partIndex(), k -> new StringBuilder()).append(t.text());
            case AudioDelta a -> {
                audioChunks.computeIfAbsent(a.partIndex(), k -> new ArrayList<>()).add(a.data() == null ? "" : a.data());
                audioMediaTypes.putIfAbsent(a.partIndex(), a.mediaType());
            }
            case ToolCallDelta t -> {
                ToolMeta meta = toolCallMeta.computeIfAbsent(t.partIndex(), k -> new ToolMeta());
                if (t.id() != null) meta.id = t.id();
                if (t.name() != null) meta.name = t.name();
                toolCallRaw.computeIfAbsent(t.partIndex(), k -> new StringBuilder()).append(t.input());
            }
            case ImageDelta i -> {
                String mt = i.mediaType() == null ? "image/png" : i.mediaType();
                ImagePart part;
                if (i.data() != null) part = new ImagePart(mt, i.data(), null, null, null, null, List.of());
                else if (i.url() != null) part = new ImagePart(mt, null, i.url(), null, null, null, List.of());
                else if (i.fileId() != null) part = new ImagePart(mt, null, null, i.fileId(), null, null, List.of());
                else return;
                imageParts.put(i.partIndex(), part);
            }
            case CitationDelta c -> citationParts.computeIfAbsent(c.partIndex(), k -> new ArrayList<>())
                .add(new CitationPart(c.url(), c.title(), c.text()));
            case ContinuationDelta c -> {
                if (c.partIndex() == null) messageContinuation.add(c.toState());
                else partContinuation.computeIfAbsent(c.partIndex(), k -> new ArrayList<>()).add(c.toState());
            }
        }
    }

    /** The complete Response; a StreamAssemblyError when a tool call never got a name (MAP-9). */
    public Response response() {
        TreeSet<Integer> unnamed = new TreeSet<>();
        for (Map.Entry<Integer, ToolMeta> e : toolCallMeta.entrySet()) {
            if (e.getValue().name == null || e.getValue().name.isEmpty()) unnamed.add(e.getKey());
        }
        if (!unnamed.isEmpty()) {
            Response partial = assemble(unnamed);
            throw new StreamAssemblyError("tool call at part " + unnamed.first() + " arrived without a name; the adapter "
                + "that produced this stream must set ToolCallDelta.name on the call's first fragment (MAP-9: lm15 does not guess which tool the model meant)",
                partial, unnamed.first());
        }
        return assemble(Set.of());
    }

    private Response assemble(Set<Integer> skip) {
        List<Part> parts = new ArrayList<>();
        TreeSet<Integer> indexes = new TreeSet<>();
        indexes.addAll(thinkingParts.keySet());
        indexes.addAll(textParts.keySet());
        indexes.addAll(imageParts.keySet());
        indexes.addAll(audioChunks.keySet());
        indexes.addAll(citationParts.keySet());
        indexes.addAll(toolCallMeta.keySet());
        indexes.addAll(partContinuation.keySet());

        for (int idx : indexes) {
            List<ContinuationState> continuation = List.copyOf(partContinuation.getOrDefault(idx, List.of()));
            boolean hasTool = toolCallMeta.containsKey(idx) && !skip.contains(idx);
            if (thinkingParts.containsKey(idx)) parts.add(new ThinkingPart(thinkingParts.get(idx).toString(), continuation));
            if (textParts.containsKey(idx)) parts.add(new TextPart(textParts.get(idx).toString(), continuation));
            if (imageParts.containsKey(idx)) parts.add(imageParts.get(idx).withContinuation(continuation));
            if (audioChunks.containsKey(idx)) {
                byte[] raw = concatBase64(audioChunks.get(idx));
                String mediaType = audioMediaTypes.get(idx);
                if (mediaType == null || mediaType.equals("audio/pcm") || mediaType.equals("audio/pcm16")) {
                    parts.add(new AudioPart("audio/wav", Base64.getEncoder().encodeToString(pcmToWav(raw, 24000, 1, 16)), null, null, null, continuation));
                } else {
                    parts.add(new AudioPart(mediaType, Base64.getEncoder().encodeToString(raw), null, null, null, continuation));
                }
            }
            if (citationParts.containsKey(idx)) {
                for (CitationPart c : citationParts.get(idx)) parts.add(c.withContinuation(continuation));
            }
            if (hasTool) {
                ToolMeta meta = toolCallMeta.get(idx);
                JsonObject payload = parseJsonBestEffort(toolCallRaw.containsKey(idx) ? toolCallRaw.get(idx).toString() : "");
                String id = (meta.id == null || meta.id.isEmpty()) ? "tool_call_" + idx : meta.id;
                parts.add(new ToolCallPart(id, meta.name, payload, continuation));
            } else if (!skip.contains(idx) && !thinkingParts.containsKey(idx) && !textParts.containsKey(idx) && !imageParts.containsKey(idx)
                && !audioChunks.containsKey(idx) && !citationParts.containsKey(idx)) {
                parts.add(new TextPart("", continuation));
            }
        }
        if (parts.isEmpty()) parts.add(new TextPart(""));

        FinishReason finish = finishReason;
        boolean hasToolCalls = parts.stream().anyMatch(p -> p instanceof ToolCallPart);
        if (finish == null) finish = hasToolCalls ? FinishReason.TOOL_CALL : FinishReason.STOP;
        else if (finish == FinishReason.STOP && hasToolCalls) finish = FinishReason.TOOL_CALL;

        return new Response(startedId, startedModel != null ? startedModel : request.model(),
            new Message(Role.ASSISTANT, parts, List.copyOf(messageContinuation)), finish, usage == null ? Usage.EMPTY : usage,
            logprobs.isEmpty() ? null : List.copyOf(logprobs), providerData);
    }

    /** Best-effort JSON: an object as-is, another value under {@code value}, unparsable text under {@code partial_json}. */
    public static JsonObject parseJsonBestEffort(String raw) {
        if (raw == null || raw.isEmpty()) return JsonObject.EMPTY;
        try {
            JsonValue v = Json.parse(raw);
            return v instanceof JsonObject o ? o : Json.obj("value", v);
        } catch (RuntimeException e) {
            return Json.obj("partial_json", raw);
        }
    }

    static byte[] concatBase64(List<String> chunks) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (String chunk : chunks) {
            if (chunk == null || chunk.isEmpty()) continue;
            try {
                raw.writeBytes(Base64.getDecoder().decode(chunk));
            } catch (IllegalArgumentException e) {
                String padded = chunk + "=".repeat((4 - chunk.length() % 4) % 4);
                try {
                    raw.writeBytes(Base64.getDecoder().decode(padded));
                } catch (IllegalArgumentException ignored) {
                    // an undecodable chunk contributes nothing
                }
            }
        }
        return raw.toByteArray();
    }

    /** Wrap raw PCM bytes in a WAV header. */
    public static byte[] pcmToWav(byte[] pcm, int sampleRate, int channels, int bits) {
        int byteRate = sampleRate * channels * bits / 8;
        int blockAlign = channels * bits / 8;
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16).putShort((short) 1)
            .putShort((short) channels).putInt(sampleRate).putInt(byteRate).putShort((short) blockAlign).putShort((short) bits)
            .put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }
}
