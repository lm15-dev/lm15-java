package dev.lm15.dialects.gemini;

import dev.lm15.dialects.Common;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.InvalidRequestError;
import dev.lm15.errors.ProviderError;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBool;
import dev.lm15.json.JsonNull;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.json.Json;
import dev.lm15.types.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The response side of the Gemini wire: candidate parts → canonical parts
 * (MAP-1, MAP-9), {@code usageMetadata} → Usage (INV-029 with the Gemini
 * zero-count rule), finish reasons, grounding citations, logprobs and the
 * in-band error envelopes.
 */
final class GeminiResponses {
    private GeminiResponses() {}

    /** Provider-executed builtin activity: never a part (MAP-1); verbatim in provider_data. */
    static final Set<String> PROVIDER_EXECUTED_PART_KEYS = Set.of("executableCode", "codeExecutionResult");

    private static final Set<String> CONTENT_FILTER_REASONS = Set.of("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII");

    private static final Set<String> CANDIDATE_FINISH_ERRORS = Set.of(
        "SAFETY", "RECITATION", "LANGUAGE", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "MALFORMED_FUNCTION_CALL",
        "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT", "IMAGE_OTHER", "NO_IMAGE", "IMAGE_RECITATION",
        "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS", "MISSING_THOUGHT_SIGNATURE", "MALFORMED_RESPONSE");

    static final String[] GENERATE_OUTPUT_KEYS = {"candidatesTokenCount", "responseTokenCount"};
    static final String[] LIVE_OUTPUT_KEYS = {"responseTokenCount", "candidatesTokenCount"};

    // ─── usage ───

    /**
     * A {@code usageMetadata} object → Usage. Inside a present object an
     * absent primary counter is a reported 0 (proto3-JSON omits zeros; MAP-3);
     * when the object is absent every counter stays null. Secondary counters
     * stay verbatim.
     */
    static Usage usage(JsonValue payload, String[] outputKeys) {
        if (!(payload instanceof JsonObject u) || u.isEmpty()) return Usage.EMPTY;
        JsonValue output = null;
        boolean found = false;
        for (String key : outputKeys) {
            if (u.has(key)) { output = u.get(key); found = true; break; }
        }
        Integer input = u.has("promptTokenCount") ? GeminiJson.intOrNull(u.get("promptTokenCount")) : Integer.valueOf(0);
        Integer out = found ? GeminiJson.intOrNull(output) : Integer.valueOf(0);
        JsonValue outputDetails = GeminiJson.truthy(u.get("candidatesTokensDetails")) ? u.get("candidatesTokensDetails") : u.get("responseTokensDetails");
        return new Usage(input, out, GeminiJson.intOrNull(u.get("totalTokenCount")), GeminiJson.intOrNull(u.get("cachedContentTokenCount")),
            null, GeminiJson.intOrNull(u.get("thoughtsTokenCount")),
            modalityTokens(u.get("promptTokensDetails"), "AUDIO"), modalityTokens(outputDetails, "AUDIO"));
    }

    /** Sum of {@code tokenCount} over the entries for {@code modality}; null when none (not reported, INV-029). */
    private static Integer modalityTokens(JsonValue details, String modality) {
        if (!(details instanceof JsonArray a)) return null;
        int sum = 0;
        boolean any = false;
        for (JsonValue e : a) {
            if (e instanceof JsonObject o && modality.equals(o.optString("modality"))) {
                Integer count = GeminiJson.intOrNull(o.get("tokenCount"));
                sum += count == null ? 0 : count;
                any = true;
            }
        }
        return any ? sum : null;
    }

    // ─── finish reasons and in-band errors ───

    static FinishReason finishReason(String reason, boolean hasToolCall) {
        if (hasToolCall) return FinishReason.TOOL_CALL;
        String r = (reason == null ? "" : reason).toUpperCase();
        if (r.equals("MAX_TOKENS")) return FinishReason.LENGTH;
        if (CONTENT_FILTER_REASONS.contains(r)) return FinishReason.CONTENT_FILTER;
        return FinishReason.STOP;
    }

    /** {@code candidates[0]} when it is an object, else an empty object (the reference's {@code (data.get("candidates") or [{}])[0]}). */
    static JsonObject firstCandidate(JsonObject data) {
        JsonArray candidates = GeminiJson.arrayAt(data, "candidates");
        if (candidates == null || candidates.isEmpty()) return JsonObject.EMPTY;
        return candidates.get(0) instanceof JsonObject o ? o : null;
    }

    /** A blocked prompt or an error-class candidate finish reason as a typed error, else null. */
    static ProviderError inbandError(JsonObject data, String provider) {
        JsonObject feedback = GeminiJson.objectAt(data, "promptFeedback");
        if (feedback != null) {
            String blockReason = GeminiJson.strOrEmpty(feedback.get("blockReason"));
            if (!blockReason.isEmpty() && !blockReason.equals("BLOCK_REASON_UNSPECIFIED")) {
                return new InvalidRequestError("Prompt blocked: " + blockReason, ErrorMeta.of(provider).withProviderCode("promptFeedback"));
            }
        }
        JsonObject candidate = firstCandidate(data);
        if (candidate != null) {
            String finish = GeminiJson.strOrEmpty(candidate.get("finishReason"));
            if (CANDIDATE_FINISH_ERRORS.contains(finish)) {
                String message = GeminiJson.strOrEmpty(candidate.get("finishMessage"));
                return new InvalidRequestError(message.isEmpty() ? "Candidate blocked: " + finish : message,
                    ErrorMeta.of(provider).withProviderCode(finish.isEmpty() ? "finishReason" : finish));
            }
        }
        return null;
    }

    // ─── parts ───

    /** The part's {@code thoughtSignature} as canonical replay state, or an empty list. */
    static List<ContinuationState> thoughtSignatureState(JsonObject part) {
        JsonValue signature = part.get("thoughtSignature");
        if (signature == null || signature instanceof JsonNull) return List.of();
        return List.of(signatureState(signature));
    }

    static ContinuationState signatureState(JsonValue signature) {
        return new ContinuationState("gemini", "thought_signature", Json.obj("value", GeminiJson.str(signature)));
    }

    /** {@code part.thoughtSignature or functionCall.thoughtSignature} (Python {@code or}: a falsy part value falls through). */
    static JsonValue functionCallSignature(JsonObject part, JsonObject fc) {
        JsonValue sig = part.get("thoughtSignature");
        if (GeminiJson.truthy(sig)) return sig;
        JsonValue inner = fc.get("thoughtSignature");
        return inner instanceof JsonNull ? null : inner;
    }

    static boolean isThought(JsonObject part) {
        return GeminiJson.truthy(part.get("thought")) && part.has("text");
    }

    /** {@code candidates[0].content.parts[]} → canonical parts; unknown entries recorded under {@code unmapped} when given. */
    static List<Part> candidateParts(JsonArray payload, List<JsonValue> unmapped, String pathPrefix, String provider) {
        List<Part> parts = new ArrayList<>();
        if (payload == null) return parts;
        for (int i = 0; i < payload.size(); i++) {
            JsonValue raw = payload.get(i);
            String path = pathPrefix + "[" + i + "]";
            if (!(raw instanceof JsonObject part)) {
                if (unmapped != null) GeminiJson.record(unmapped, path, GeminiJson.pyType(raw));
                continue;
            }
            if (isThought(part)) {
                // Classified by the flag, not by non-empty text: 3.x can send {thought, text: "", thoughtSignature}.
                parts.add(new ThinkingPart(GeminiJson.strOrEmpty(part.get("text")), thoughtSignatureState(part)));
            } else if (part.has("text")) {
                // On 3.x the final answer text carries the turn's thoughtSignature: replay state, back on the wire.
                parts.add(new TextPart(GeminiJson.strOrEmpty(part.get("text")), thoughtSignatureState(part)));
            } else if (part.get("functionCall") instanceof JsonObject fc) {
                JsonValue signature = functionCallSignature(part, fc);
                List<ContinuationState> continuation = signature == null ? List.of() : List.of(signatureState(signature));
                if (!GeminiJson.truthy(fc.get("name"))) throw Common.unnamedToolCallError(provider, path);
                // MAP-9: a missing call id is the lm15 correlator tool_call_<index>, the same one the stream assembler mints.
                String id = GeminiJson.truthy(fc.get("id")) ? GeminiJson.str(fc.get("id")) : "tool_call_" + parts.size();
                JsonObject input = fc.get("args") instanceof JsonObject args ? args : JsonObject.EMPTY;
                parts.add(new ToolCallPart(id, GeminiJson.str(fc.get("name")), input, continuation));
            } else if (part.get("inlineData") instanceof JsonObject inline) {
                String mime = GeminiJson.strOr(inline, "mimeType", "application/octet-stream");
                String data = GeminiJson.strOrEmpty(inline.get("data"));
                if (data.isEmpty()) continue;
                if (mime.startsWith("image/")) parts.add(new ImagePart(mime, data, null, null, null, null, List.of()));
                else if (mime.startsWith("audio/")) parts.add(new AudioPart(mime, data, null, null, null, List.of()));
                else parts.add(new DocumentPart(mime, data, null, null, null, List.of()));
            } else if (part.get("fileData") instanceof JsonObject fd) {
                String uri = GeminiJson.strOrEmpty(fd.get("fileUri"));
                String mime = GeminiJson.strOr(fd, "mimeType", "application/octet-stream");
                if (uri.isEmpty()) continue;
                if (mime.startsWith("image/")) parts.add(new ImagePart(mime, null, uri, null, null, null, List.of()));
                else if (mime.startsWith("audio/")) parts.add(new AudioPart(mime, null, uri, null, null, List.of()));
                else parts.add(new DocumentPart(mime, null, uri, null, null, List.of()));
            } else if (hasAny(part, PROVIDER_EXECUTED_PART_KEYS)) {
                continue;
            } else if (unmapped != null) {
                String keys = String.join("+", new TreeSet<>(part.keys()));
                GeminiJson.record(unmapped, path, keys.isEmpty() ? "<empty>" : keys);
            }
        }
        return parts;
    }

    private static boolean hasAny(JsonObject part, Set<String> keys) {
        for (String k : keys) if (part.has(k)) return true;
        return false;
    }

    // ─── grounding citations ───

    private static String segmentText(JsonObject segment, String fullText) {
        JsonValue text = segment.get("text");
        if (text instanceof JsonString s && !s.value().isEmpty()) return s.value();
        Integer start = GeminiJson.intOrNull(segment.get("startIndex"));
        Integer end = GeminiJson.intOrNull(segment.get("endIndex"));
        if (start != null && end != null && 0 <= start && start < end && end <= fullText.length()) return fullText.substring(start, end);
        return null;
    }

    private static JsonObject groundingChunk(JsonArray chunks, JsonValue index) {
        Integer idx = GeminiJson.intOrNull(index);
        if (idx == null || idx < 0 || idx >= chunks.size()) return JsonObject.EMPTY;
        return chunks.get(idx) instanceof JsonObject o ? o : JsonObject.EMPTY;
    }

    static List<CitationPart> citations(JsonObject candidate, String fullText) {
        List<CitationPart> out = new ArrayList<>();
        JsonObject grounding = GeminiJson.objectAt(candidate, "groundingMetadata");
        if (grounding == null) return out;
        JsonArray chunks = GeminiJson.arrayAt(grounding, "groundingChunks");
        if (chunks == null) chunks = JsonArray.EMPTY;
        JsonValue supportsValue = grounding.get("groundingSupports");
        if (!GeminiJson.truthy(supportsValue)) return out;
        if (!(supportsValue instanceof JsonArray supports)) return out;
        Set<List<String>> seen = new HashSet<>();
        for (JsonValue sv : supports) {
            if (!(sv instanceof JsonObject support)) continue;
            JsonObject segment = GeminiJson.objectAt(support, "segment");
            String cited = segment == null ? null : segmentText(segment, fullText);
            JsonValue indicesValue = support.get("groundingChunkIndices");
            if (!GeminiJson.truthy(indicesValue)) continue;
            if (!(indicesValue instanceof JsonArray indices)) continue;
            for (JsonValue index : indices) {
                JsonObject chunk = groundingChunk(chunks, index);
                JsonObject source = firstTruthyObject(chunk, "web", "retrievedContext", "googleSearch");
                JsonValue url = GeminiJson.truthy(source.get("uri")) ? source.get("uri") : source.get("url");
                JsonValue title = GeminiJson.truthy(source.get("title")) ? source.get("title") : source.get("name");
                String urlS = GeminiJson.truthy(url) ? GeminiJson.str(url) : null;
                String titleS = GeminiJson.truthy(title) ? GeminiJson.str(title) : null;
                List<String> key = java.util.Arrays.asList(urlS, titleS, cited);
                if (seen.contains(key) || (urlS == null && titleS == null && cited == null)) continue;
                seen.add(key);
                out.add(new CitationPart(urlS, titleS, cited));
            }
        }
        return out;
    }

    private static JsonObject firstTruthyObject(JsonObject chunk, String... keys) {
        for (String k : keys) {
            JsonValue v = chunk.get(k);
            if (GeminiJson.truthy(v)) return v instanceof JsonObject o ? o : JsonObject.EMPTY;
        }
        return JsonObject.EMPTY;
    }

    // ─── logprobs ───

    private static double logprobOf(JsonObject o) {
        JsonValue v = o.get("logProbability");
        if (!GeminiJson.truthy(v) || !v.isNumber()) return 0.0;
        return v.asDouble();
    }

    /** {@code logprobsResult} → canonical TokenLogprobs: chosenCandidates[i] pairs with topCandidates[i] (doc-based). */
    static List<TokenLogprob> tokenLogprobs(JsonValue logprobsResult) {
        List<TokenLogprob> out = new ArrayList<>();
        if (!(logprobsResult instanceof JsonObject result)) return out;
        JsonArray chosen = GeminiJson.truthy(result.get("chosenCandidates")) ? GeminiJson.arr(result.get("chosenCandidates")) : JsonArray.EMPTY;
        JsonArray topSteps = GeminiJson.truthy(result.get("topCandidates")) ? GeminiJson.arr(result.get("topCandidates")) : JsonArray.EMPTY;
        if (chosen == null) return out;
        for (int i = 0; i < chosen.size(); i++) {
            if (!(chosen.get(i) instanceof JsonObject cand)) continue;
            List<TopLogprob> top = new ArrayList<>();
            JsonObject step = topSteps != null && i < topSteps.size() && topSteps.get(i) instanceof JsonObject s ? s : JsonObject.EMPTY;
            JsonArray alts = GeminiJson.truthy(step.get("candidates")) ? GeminiJson.arr(step.get("candidates")) : null;
            if (alts != null) {
                for (JsonValue av : alts) {
                    if (!(av instanceof JsonObject alt)) continue;
                    top.add(new TopLogprob(GeminiJson.strOrEmpty(alt.get("token")), logprobOf(alt), null, GeminiJson.intOrNull(alt.get("tokenId"))));
                }
            }
            out.add(new TokenLogprob(GeminiJson.strOrEmpty(cand.get("token")), logprobOf(cand), null, GeminiJson.intOrNull(cand.get("tokenId")), top));
        }
        return out;
    }

    /** {@code provider_data} with the unmapped recorder attached when non-empty. */
    static JsonObject attachUnmapped(JsonObject data, List<JsonValue> unmapped) {
        if (unmapped.isEmpty()) return data;
        return data.with("_lm15_unmapped", new JsonArray(unmapped));
    }

    static boolean boolAt(JsonObject o, String key) {
        JsonValue v = o.get(key);
        return v instanceof JsonBool b ? b.value() : GeminiJson.truthy(v);
    }
}
