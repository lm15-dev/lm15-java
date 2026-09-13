package dev.lm15.dialects.gemini;

import dev.lm15.compat.Compat;
import dev.lm15.compat.PresetAddresses;
import dev.lm15.dialects.Common;
import dev.lm15.dialects.Dialect;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.LM15Error;
import dev.lm15.errors.ProviderError;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.live.LiveCodec;
import dev.lm15.registry.DialectId;
import dev.lm15.sse.SseEvent;
import dev.lm15.transport.HttpResponse;
import dev.lm15.types.*;
import dev.lm15.wire.BuildContext;
import dev.lm15.wire.Wire;
import dev.lm15.wire.WireRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The Gemini GenerateContent dialect (the reference's {@code GeminiLM}):
 * chat (complete + SSE stream), errors, the model catalog, Files (resumable
 * multipart upload on the upload host), Batch Mode (inline requests),
 * cachedContents (the MAP-6 resource tier), image/speech generation through
 * the same chat call, Veo video jobs, and the Live websocket codec.
 * Providers: {@code gemini}, and the cloud doors {@code vertex} /
 * {@code vertex-express} whose host rewrites happen in {@code Hosts}.
 */
public final class GeminiDialect implements Dialect {
    private static final String DEFAULT_UPLOAD_BASE_URL = "https://generativelanguage.googleapis.com/upload/v1beta";

    public GeminiDialect() {}

    @Override public DialectId id() { return DialectId.GEMINI; }

    @Override public Compat compat(String preset) {
        String name = PresetAddresses.canonicalPreset(preset);
        return switch (name) {
            case "gemini", "vertex", "vertex_express" -> new GeminiCompat(name);
            default -> throw ValidationException.value("unknown Gemini compat preset: " + preset + " (the Gemini dialect has no compat table)");
        };
    }

    @Override public Compat defaultCompat(String provider) { return GeminiCompat.DEFAULT; }

    @Override public String apiKeyHeader() { return "x-goog-api-key"; }

    private static UnsupportedFeatureError unsupported(BuildContext cx, String message) {
        return new UnsupportedFeatureError(message, ErrorMeta.of(cx.provider()));
    }

    private static ProviderError providerError(BuildContext cx, String message) {
        return new ProviderError(message, ErrorMeta.of(cx.provider()));
    }

    /** The reference's per-hook {@code read_timeout}. */
    private static WireRequest timeout(WireRequest wire, long seconds) {
        wire.readTimeout = Duration.ofSeconds(seconds);
        return wire;
    }

    private static WireRequest jsonPost(String path, JsonValue body, long timeoutSeconds) {
        return timeout(WireRequest.post(path, body).header("Content-Type", "application/json"), timeoutSeconds);
    }

    private static WireRequest get(String path, long timeoutSeconds) {
        return timeout(WireRequest.get(path), timeoutSeconds);
    }

    /** MAP-11 rule 1: a resource-name id keeps its slashes. */
    private static String resourcePath(String id) { return "/" + Wire.pathId(id, true); }

    // ─── chat core ───

    @Override public WireRequest build(Request request, boolean stream, BuildContext cx) {
        String endpoint = stream ? "streamGenerateContent" : "generateContent";
        WireRequest wire = jsonPost("/" + GeminiContents.modelPath(cx.model()) + ":" + endpoint,
            GeminiContents.payload(request, cx.model(), cx.provider()), stream ? 120 : 60)
            .endpoint("generateContent").model(cx.model());
        if (stream) wire.param("alt", "sse");
        return wire;
    }

    @Override public Response parseResponse(Request request, BuildContext cx, HttpResponse response) {
        JsonObject data = response.json().asObject();
        ProviderError inband = GeminiResponses.inbandError(data, cx.provider());
        if (inband != null) throw inband;
        JsonObject candidate = GeminiResponses.firstCandidate(data);
        if (candidate == null) candidate = JsonObject.EMPTY;
        JsonObject content = GeminiJson.objectAt(candidate, "content");
        List<JsonValue> unmapped = new ArrayList<>();
        JsonArray partsPayload = content == null ? null : GeminiJson.arrayAt(content, "parts");
        List<Part> parts = GeminiResponses.candidateParts(partsPayload, unmapped, "candidates[0].content.parts", cx.provider());
        StringBuilder fullText = new StringBuilder();
        for (Part p : parts) if (p instanceof TextPart t) fullText.append(t.text());
        parts.addAll(GeminiResponses.citations(candidate, fullText.toString()));
        if (parts.isEmpty()) parts.add(new TextPart(""));
        Usage usage = GeminiResponses.usage(data.get("usageMetadata"), GeminiResponses.GENERATE_OUTPUT_KEYS);
        boolean hasTool = false;
        for (Part p : parts) if (p instanceof ToolCallPart) hasTool = true;
        List<TokenLogprob> logprobs = GeminiResponses.tokenLogprobs(candidate.get("logprobsResult"));
        // D8 (2026-09-06): Response.id carries responseId; no message-level continuation state is minted for it.
        String id = GeminiJson.truthy(data.get("responseId")) ? GeminiJson.str(data.get("responseId")) : null;
        return new Response(id, request.model(), new Message(Role.ASSISTANT, parts),
            GeminiResponses.finishReason(GeminiJson.strOrEmpty(candidate.get("finishReason")), hasTool),
            usage, logprobs.isEmpty() ? null : logprobs, GeminiResponses.attachUnmapped(data, unmapped));
    }

    @Override public List<StreamEvent> parseStreamEvent(Request request, BuildContext cx, SseEvent event) {
        List<StreamEvent> out = new ArrayList<>();
        if (event.data() == null || event.data().isEmpty()) return out;
        JsonValue parsed = Json.parse(event.data());
        if (!(parsed instanceof JsonObject payload)) return out;
        if (payload.has("error")) {
            JsonValue err = payload.get("error");
            out.add(new StreamErrorEvent(GeminiErrors.errorDetail(cx, GeminiErrors.envelopeCode(err), GeminiErrors.envelopeMessage(err))));
            return out;
        }
        ProviderError inband = GeminiResponses.inbandError(payload, cx.provider());
        if (inband != null) {
            out.add(new StreamErrorEvent(new ErrorDetail(inband.code(), inband.getMessage(), "inband_finish_reason")));
            return out;
        }
        JsonArray candidates = GeminiJson.arrayAt(payload, "candidates");
        JsonObject candidate = candidates != null && !candidates.isEmpty() && candidates.get(0) instanceof JsonObject c ? c : null;
        boolean yieldedDelta = false;
        boolean sawTool = false;
        String finish = null;
        if (candidate != null) {
            JsonObject content = GeminiJson.objectAt(candidate, "content");
            // Chunk-level decoding telemetry rides the chunk's first text delta (doc-based).
            List<TokenLogprob> chunkLogprobs = GeminiResponses.tokenLogprobs(candidate.get("logprobsResult"));
            JsonArray parts = content == null ? null : GeminiJson.arrayAt(content, "parts");
            for (int idx = 0; parts != null && idx < parts.size(); idx++) {
                if (!(parts.get(idx) instanceof JsonObject part)) continue;
                if (GeminiResponses.isThought(part)) {
                    yieldedDelta = true;
                    out.add(new StreamDeltaEvent(new ThinkingDelta(GeminiJson.strOrEmpty(part.get("text")), idx)));
                    addSignature(out, part.get("thoughtSignature"), idx);
                } else if (part.has("text")) {
                    yieldedDelta = true;
                    out.add(new StreamDeltaEvent(new TextDelta(GeminiJson.strOrEmpty(part.get("text")), idx, chunkLogprobs)));
                    chunkLogprobs = List.of();
                    addSignature(out, part.get("thoughtSignature"), idx); // the same replay state on an answer-text part (3.x)
                } else if (part.get("functionCall") instanceof JsonObject fc) {
                    sawTool = true;
                    yieldedDelta = true;
                    JsonValue args = fc.has("args") ? fc.get("args") : JsonObject.EMPTY;
                    String id = GeminiJson.strOrEmpty(fc.get("id"));
                    String name = GeminiJson.strOrEmpty(fc.get("name"));
                    out.add(new StreamDeltaEvent(new ToolCallDelta(GeminiJson.dumpsCompact(args), idx, id.isEmpty() ? null : id, name.isEmpty() ? null : name)));
                    addSignature(out, GeminiResponses.functionCallSignature(part, fc), idx);
                } else if (part.get("inlineData") instanceof JsonObject inline) {
                    String mime = GeminiJson.strOr(inline, "mimeType", "application/octet-stream");
                    String data = GeminiJson.strOrEmpty(inline.get("data"));
                    if (mime.startsWith("audio/")) {
                        yieldedDelta = true;
                        out.add(new StreamDeltaEvent(AudioDelta.ofData(data, idx, mime)));
                    } else if (mime.startsWith("image/")) {
                        yieldedDelta = true;
                        out.add(new StreamDeltaEvent(ImageDelta.ofData(data, idx, mime)));
                    }
                }
            }
            finish = GeminiJson.strOrEmpty(candidate.get("finishReason"));
        }
        Usage usage = GeminiResponses.usage(payload.get("usageMetadata"), GeminiResponses.GENERATE_OUTPUT_KEYS);
        if (finish != null && !finish.isEmpty()) {
            out.add(new StreamEndEvent(GeminiResponses.finishReason(finish, sawTool), usage, payload));
        } else if (!yieldedDelta && payload.has("usageMetadata")) {
            out.add(new StreamEndEvent(FinishReason.STOP, usage, payload));
        }
        return out;
    }

    private static void addSignature(List<StreamEvent> out, JsonValue signature, int idx) {
        if (signature == null || signature.isNull()) return;
        out.add(new StreamDeltaEvent(ContinuationDelta.of(GeminiResponses.signatureState(signature), idx)));
    }

    @Override public LM15Error normalizeError(BuildContext cx, int status, String body) {
        return GeminiErrors.normalize(cx, status, body);
    }

    // ─── models ───

    @Override public WireRequest modelsRequest(BuildContext cx) {
        // pageSize=1000 covers the catalog in one page (53 models observed live 2026-08-31).
        return get("/models", 30).param("pageSize", "1000");
    }

    @Override public List<ModelInfo> parseModels(BuildContext cx, String body) {
        JsonValue data = Json.parse(body);
        JsonValue entries = data instanceof JsonObject o ? o.get("models") : null;
        return Common.modelInfosFromEntries(entries, cx.provider(), DialectId.GEMINI.apiFamily(), entry -> {
            // The wire name is "models/<id>"; the usable Request.model string is the bare id.
            String name = entry.optString("name");
            if (name == null) return null;
            return name.startsWith("models/") ? name.substring("models/".length()) : name;
        });
    }

    // ─── files ───

    /** The reference's {@code upload_base_url}: the base URL with {@code /upload} in front of its path. */
    static String uploadBaseUrl(String baseUrl) {
        String base = baseUrl;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.equals(PresetAddresses.dialectDefault(DialectId.GEMINI))) return DEFAULT_UPLOAD_BASE_URL;
        int scheme = base.indexOf("://");
        int afterScheme = scheme < 0 ? 0 : scheme + 3;
        int pathStart = base.indexOf('/', afterScheme);
        if (pathStart < 0) return base + "/upload";
        return base.substring(0, pathStart) + "/upload" + base.substring(pathStart);
    }

    /** {@code files/<id>} resource path from a canonical id (URI or name). */
    static String fileResource(String fileId) {
        if (fileId.contains("://")) {
            String trimmed = fileId;
            while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
            int at = trimmed.lastIndexOf("/files/");
            if (at >= 0) {
                String tail = trimmed.substring(at + "/files/".length());
                if (!tail.isEmpty()) return "files/" + tail;
            }
        }
        if (fileId.startsWith("files/")) return fileId;
        return "files/" + fileId;
    }

    @Override public WireRequest fileUploadRequest(BuildContext cx, FileUploadRequest request) {
        Map.Entry<String, byte[]> body = Common.multipartRelatedBody(
            Json.obj("file", Json.obj("display_name", request.filename())), request.mediaType(), request.bytes());
        WireRequest wire = timeout(WireRequest.raw("POST", null, body.getKey(), body.getValue())
            .header("X-Goog-Upload-Protocol", "multipart")
            .absolute(uploadBaseUrl(cx.baseUrl()) + "/files"), 300);
        if (request.extensions() != null) {
            for (Map.Entry<String, JsonValue> e : request.extensions().members().entrySet()) {
                if (e.getValue().isNull()) continue;
                wire.param(e.getKey(), GeminiJson.str(e.getValue()));
            }
        }
        return wire;
    }

    @Override public FileInfo fileInfo(BuildContext cx, String body) {
        JsonObject data = Json.parse(body).asObject();
        JsonObject file = GeminiJson.objectAt(data, "file"); // upload wraps; get/list do not
        return fileInfo(cx, file != null ? file : data);
    }

    private static FileInfo fileInfo(BuildContext cx, JsonObject data) {
        String uri = data.optString("uri");
        String fileId = uri != null && !uri.isEmpty() ? uri : (data.get("name") instanceof dev.lm15.json.JsonString n ? n.value() : null);
        if (fileId == null || fileId.isEmpty()) throw providerError(cx, "gemini: file object carries no uri or name");
        String state = GeminiJson.strOrEmpty(data.get("state"));
        FileReadiness readiness = state.endsWith("PROCESSING") ? FileReadiness.PENDING
            : state.endsWith("FAILED") ? FileReadiness.FAILED : FileReadiness.READY; // ACTIVE, absent, or unknown
        Boolean downloadable;
        if (data.get("downloadUri") instanceof dev.lm15.json.JsonString d && !d.value().isEmpty()) downloadable = Boolean.TRUE;
        else if ("UPLOADED".equals(data.optString("source"))) downloadable = Boolean.FALSE; // only GENERATED files download
        else downloadable = null;
        String displayName = data.get("displayName") instanceof dev.lm15.json.JsonString s && !s.value().isEmpty() ? s.value() : null;
        String mime = data.get("mimeType") instanceof dev.lm15.json.JsonString s && !s.value().isEmpty() ? s.value() : null;
        Long size = null;
        JsonValue sizeRaw = data.get("sizeBytes"); // the wire carries int64 as a string
        try {
            if (sizeRaw instanceof dev.lm15.json.JsonInt i) size = i.value().longValueExact();
            else if (sizeRaw instanceof dev.lm15.json.JsonString s) size = Long.parseLong(s.value().strip());
        } catch (RuntimeException e) {
            size = null;
        }
        return new FileInfo(fileId, displayName, mime, size, Common.isoUtc(data.get("createTime")), Common.isoUtc(data.get("expirationTime")),
            readiness, downloadable, data);
    }

    @Override public WireRequest fileGetRequest(BuildContext cx, String fileId) {
        return get(resourcePath(fileResource(fileId)), 60);
    }

    @Override public WireRequest fileListRequest(BuildContext cx, int limit, String cursor) {
        WireRequest wire = get("/files", 60).param("pageSize", Integer.toString(limit));
        if (cursor != null) wire.param("pageToken", cursor);
        return wire;
    }

    @Override public FilePage filePage(BuildContext cx, String body) {
        JsonObject data = Json.parse(body).asObject();
        List<FileInfo> items = new ArrayList<>();
        JsonArray entries = GeminiJson.arrayAt(data, "files");
        if (entries != null) for (JsonValue e : entries) if (e instanceof JsonObject o) items.add(fileInfo(cx, o));
        String cursor = data.get("nextPageToken") instanceof dev.lm15.json.JsonString s && !s.value().isEmpty() ? s.value() : null;
        return new FilePage(items, cursor);
    }

    @Override public WireRequest fileDeleteRequest(BuildContext cx, String fileId) {
        return timeout(WireRequest.delete(resourcePath(fileResource(fileId))), 60);
    }

    @Override public WireRequest fileDownloadRequest(BuildContext cx, String fileId) {
        return get(resourcePath(fileResource(fileId)) + ":download", 300).param("alt", "media");
    }

    // ─── stored caches (cachedContents) ───

    @Override public WireRequest cacheCreateRequest(BuildContext cx, Request prefix, Integer ttlSeconds, String label) {
        JsonBuilder body = new JsonBuilder()
            .put("model", GeminiContents.modelPath(cx.model()))
            .put("contents", GeminiContents.contents(prefix.messages(), GeminiContents.callNames(prefix.messages()), cx.provider()));
        if (prefix.system() != null) body.put("systemInstruction", GeminiContents.systemInstruction(prefix.system()));
        if (!prefix.tools().isEmpty()) body.put("tools", GeminiContents.toolsWire(prefix.tools()));
        if (ttlSeconds != null) body.put("ttl", ttlSeconds + "s");
        if (label != null) body.put("displayName", label);
        return jsonPost("/cachedContents", body.build(), 120);
    }

    @Override public CacheInfo cacheInfo(BuildContext cx, String body) {
        return cacheInfo(cx, Json.parse(body).asObject());
    }

    private static CacheInfo cacheInfo(BuildContext cx, JsonObject data) {
        String name = data.get("name") instanceof dev.lm15.json.JsonString s ? s.value() : null;
        if (name == null || name.isEmpty()) throw providerError(cx, "gemini: cache object carries no name");
        String model = GeminiJson.strOrEmpty(data.get("model"));
        if (model.startsWith("models/")) model = model.substring("models/".length());
        if (model.isEmpty()) throw providerError(cx, "gemini: cache object carries no model");
        JsonObject usage = GeminiJson.objectAt(data, "usageMetadata");
        Integer tokens = null;
        JsonValue total = usage == null ? null : usage.get("totalTokenCount");
        if (total instanceof dev.lm15.json.JsonInt || (total instanceof dev.lm15.json.JsonString s && s.value().matches("\\d+"))) tokens = GeminiJson.intOrNull(total);
        String label = data.get("displayName") instanceof dev.lm15.json.JsonString s && !s.value().isEmpty() ? s.value() : null;
        return new CacheInfo(name, model, tokens, Common.isoUtc(data.get("createTime")), Common.isoUtc(data.get("expireTime")), label, data);
    }

    @Override public WireRequest cacheGetRequest(BuildContext cx, String cacheId) {
        return get(resourcePath(GeminiContents.cacheResource(cacheId)), 60);
    }

    @Override public WireRequest cacheListRequest(BuildContext cx, int limit, String cursor) {
        WireRequest wire = get("/cachedContents", 60).param("pageSize", Integer.toString(limit));
        if (cursor != null) wire.param("pageToken", cursor);
        return wire;
    }

    @Override public CachePage cachePage(BuildContext cx, String body) {
        JsonObject data = Json.parse(body).asObject();
        List<CacheInfo> items = new ArrayList<>();
        JsonArray entries = GeminiJson.arrayAt(data, "cachedContents");
        if (entries != null) for (JsonValue e : entries) if (e instanceof JsonObject o) items.add(cacheInfo(cx, o));
        String cursor = data.get("nextPageToken") instanceof dev.lm15.json.JsonString s && !s.value().isEmpty() ? s.value() : null;
        return new CachePage(items, cursor);
    }

    @Override public WireRequest cacheDeleteRequest(BuildContext cx, String cacheId) {
        return timeout(WireRequest.delete(resourcePath(GeminiContents.cacheResource(cacheId))), 60);
    }

    @Override public WireRequest cacheUpdateRequest(BuildContext cx, String cacheId, int ttlSeconds) {
        return timeout(WireRequest.json("PATCH", resourcePath(GeminiContents.cacheResource(cacheId)), Json.obj("ttl", ttlSeconds + "s"))
            .header("Content-Type", "application/json"), 60);
    }

    // ─── batch (Batch Mode, inline requests) ───

    @Override public WireRequest batchUploadRequest(BuildContext cx, BatchRequest request) {
        return null; // single-step wire: the requests ride inline in the submit body
    }

    @Override public WireRequest batchSubmitRequest(BuildContext cx, BatchRequest request, JsonObject uploadBody) {
        String model = request.model() != null ? request.model() : request.requests().get(0).model();
        List<JsonValue> entries = new ArrayList<>();
        for (int i = 0; i < request.requests().size(); i++) {
            Request nested = request.requests().get(i);
            entries.add(Json.obj("request", GeminiContents.payload(nested, nested.model(), cx.provider()), "metadata", Json.obj("key", Integer.toString(i))));
        }
        JsonBuilder batch = new JsonBuilder().put("inputConfig", Json.obj("requests", Json.obj("requests", new JsonArray(entries))));
        if (request.label() != null) batch.put("displayName", request.label());
        JsonBuilder payload = new JsonBuilder().put("batch", batch.build());
        if (request.extensions() != null) payload.putAll(request.extensions());
        return jsonPost("/" + GeminiContents.modelPath(model) + ":batchGenerateContent", payload.build(), 120);
    }

    @Override public BatchJobInfo batchJob(BuildContext cx, String body) {
        return batchJobInfo(cx, Json.parse(body).asObject());
    }

    /** BATCH_STATE_* → canonical (wire fact 2026-08-31; the docs' JOB_STATE_* naming is wrong for this endpoint). */
    private static final Map<String, BatchStatus> BATCH_STATES = Map.of(
        "BATCH_STATE_PENDING", BatchStatus.QUEUED,
        "BATCH_STATE_RUNNING", BatchStatus.RUNNING,
        "BATCH_STATE_CANCELLING", BatchStatus.CANCELLING,
        "BATCH_STATE_SUCCEEDED", BatchStatus.COMPLETED,
        "BATCH_STATE_FAILED", BatchStatus.FAILED,
        "BATCH_STATE_CANCELLED", BatchStatus.CANCELLED,
        "BATCH_STATE_EXPIRED", BatchStatus.EXPIRED);

    private static BatchJobInfo batchJobInfo(BuildContext cx, JsonObject data) {
        String name = data.get("name") instanceof dev.lm15.json.JsonString s ? s.value() : null;
        if (name == null || name.isEmpty()) throw providerError(cx, "gemini: batch operation carries no name");
        JsonObject metadata = GeminiJson.objectAt(data, "metadata");
        if (metadata == null) metadata = JsonObject.EMPTY;
        String state = GeminiJson.strOrEmpty(metadata.get("state")).toUpperCase();
        BatchStatus status = BATCH_STATES.get(state);
        if (status == null) status = GeminiJson.truthy(data.get("done")) ? BatchStatus.COMPLETED : BatchStatus.QUEUED;
        String label = metadata.get("displayName") instanceof dev.lm15.json.JsonString s && !s.value().isEmpty() ? s.value() : null;
        return new BatchJobInfo(name, status, label, Common.isoUtc(metadata.get("createTime")), data);
    }

    @Override public WireRequest batchStatusRequest(BuildContext cx, String batchId) {
        return get(resourcePath(batchId), 60);
    }

    @Override public WireRequest batchCancelRequest(BuildContext cx, String batchId) {
        return jsonPost(resourcePath(batchId) + ":cancel", JsonObject.EMPTY, 60);
    }

    @Override public List<WireRequest> batchResultFetches(BuildContext cx, JsonObject statusBody) {
        return List.of(); // inline submissions carry their results in the operation body
    }

    @Override public List<BatchEntry> batchEntries(BuildContext cx, JsonObject statusBody, List<String> fetched) {
        JsonObject response = GeminiJson.objectAt(statusBody, "response");
        JsonValue inlined = response == null ? null : response.get("inlinedResponses");
        if (inlined instanceof JsonObject o) inlined = o.get("inlinedResponses");
        List<BatchEntry> entries = new ArrayList<>();
        if (inlined instanceof JsonArray items) {
            for (int position = 0; position < items.size(); position++) {
                if (!(items.get(position) instanceof JsonObject item)) continue;
                JsonObject metadata = GeminiJson.objectAt(item, "metadata");
                int index = position;
                try {
                    index = Integer.parseInt(GeminiJson.str(metadata == null ? null : metadata.get("key")));
                } catch (NumberFormatException e) {
                    index = position;
                }
                if (item.get("response") instanceof JsonObject body) {
                    String modelVersion = body.get("modelVersion") instanceof dev.lm15.json.JsonString s && !s.value().isEmpty() ? s.value() : "batch";
                    Request synthetic = new Request(modelVersion, List.of(Message.user("-")));
                    Response parsed = parseResponse(synthetic, cx, HttpResponse.json(200, Json.writeBytes(body)));
                    entries.add(new BatchEntry(index, BatchOutcome.SUCCEEDED, parsed, null));
                } else {
                    // Per-entry failures arrive as a google.rpc.Status, not the HTTP error envelope; map directly.
                    JsonObject err = GeminiJson.objectAt(item, "error");
                    if (err == null) err = JsonObject.EMPTY;
                    JsonValue code = GeminiJson.truthy(err.get("status")) ? err.get("status") : err.get("code");
                    String providerCode = code == null || code.isNull() ? null : GeminiJson.str(code);
                    String message = GeminiJson.truthy(err.get("message")) ? GeminiJson.str(err.get("message")) : "batch entry errored";
                    entries.add(new BatchEntry(index, BatchOutcome.ERRORED, null, new ErrorDetail(ErrorCode.PROVIDER, message, providerCode)));
                }
            }
        }
        entries.sort(Comparator.comparingInt(BatchEntry::index));
        return entries;
    }

    @Override public WireRequest batchListRequest(BuildContext cx, int limit) {
        return get("/batches", 60).param("pageSize", Integer.toString(limit));
    }

    @Override public List<BatchJobInfo> batchJobs(BuildContext cx, String body) {
        JsonValue data = Json.parse(body);
        List<BatchJobInfo> out = new ArrayList<>();
        JsonArray items = data instanceof JsonObject o ? GeminiJson.arrayAt(o, "operations") : null;
        if (items != null) for (JsonValue item : items) if (item instanceof JsonObject j) out.add(batchJobInfo(cx, j));
        return out;
    }

    // ─── generation (image and TTS models answer the ordinary generateContent call) ───

    private static Request imageGenerationLmRequest(ImageGenerationRequest request) {
        JsonBuilder extensions = request.extensions() == null ? new JsonBuilder() : request.extensions().toBuilder();
        if (request.size() != null) {
            JsonObject generation = extensions.get("generationConfig") instanceof JsonObject g ? g : JsonObject.EMPTY;
            JsonObject imageConfig = generation.get("imageConfig") instanceof JsonObject ic ? ic : JsonObject.EMPTY;
            if (!imageConfig.has("aspectRatio")) imageConfig = imageConfig.with("aspectRatio", Json.of(request.size()));
            extensions.put("generationConfig", generation.with("imageConfig", imageConfig));
        }
        List<Part> parts = new ArrayList<>();
        parts.add(new TextPart(request.prompt()));
        parts.addAll(request.images());
        JsonObject ext = extensions.build();
        Config config = ext.isEmpty() ? Config.DEFAULT : Config.builder().extensions(ext).build();
        return new Request(request.model(), List.of(new Message(Role.USER, parts)), null, List.of(), config);
    }

    @Override public WireRequest imageGenerateRequest(BuildContext cx, ImageGenerationRequest request) {
        return build(imageGenerationLmRequest(request), false, cx);
    }

    @Override public ImageGenerationResponse imageGeneration(BuildContext cx, ImageGenerationRequest request, HttpResponse response) {
        Response chat = parseResponse(imageGenerationLmRequest(request), cx, response);
        List<ImagePart> images = chat.message().partsOf(ImagePart.class);
        if (images.isEmpty()) throw providerError(cx, "gemini: model returned no image parts");
        StringBuilder text = new StringBuilder();
        for (Part p : chat.message().parts()) if (p instanceof TextPart t && !t.text().isEmpty()) text.append(t.text());
        return new ImageGenerationResponse(images, text.length() == 0 ? null : text.toString(), chat.id(), chat.model(), chat.usage(), chat.providerData());
    }

    private static Request speechGenerationLmRequest(BuildContext cx, SpeechGenerationRequest request) {
        if (request.format() != null) {
            // No wire slot: Gemini TTS always answers PCM (captured audio/L16;codec=pcm;rate=24000).
            throw unsupported(cx, "gemini: speech format cannot be chosen; the wire always returns PCM");
        }
        JsonBuilder generation = new JsonBuilder().put("responseModalities", List.of("AUDIO"));
        if (request.voice() != null) {
            generation.put("speechConfig", Json.obj("voiceConfig", Json.obj("prebuiltVoiceConfig", Json.obj("voiceName", request.voice()))));
        }
        JsonBuilder extensions = new JsonBuilder().put("generationConfig", generation.build());
        if (request.extensions() != null) extensions.putAll(request.extensions());
        return new Request(request.model(), List.of(Message.user(request.prompt())), null, List.of(), Config.builder().extensions(extensions.build()).build());
    }

    @Override public WireRequest speechGenerateRequest(BuildContext cx, SpeechGenerationRequest request) {
        return build(speechGenerationLmRequest(cx, request), false, cx);
    }

    @Override public SpeechGenerationResponse speechGeneration(BuildContext cx, SpeechGenerationRequest request, HttpResponse response) {
        Response chat = parseResponse(speechGenerationLmRequest(cx, request), cx, response);
        AudioPart audio = chat.message().first(AudioPart.class);
        if (audio == null) throw providerError(cx, "gemini: model returned no audio part");
        return new SpeechGenerationResponse(audio, chat.id(), chat.model(), chat.usage(), chat.providerData());
    }

    // ─── video (Veo, predictLongRunning operations) ───

    @Override public WireRequest videoSubmitRequest(BuildContext cx, VideoGenerationRequest request) {
        if (!request.images().isEmpty()) {
            // Veo's instances[].image input is documented but not live-receipted; unverified media mappings are a named hazard.
            throw unsupported(cx, "gemini: video input images are not mapped yet; use extensions until the mapping is live-receipted");
        }
        JsonBuilder parameters = new JsonBuilder();
        if (request.seconds() != null) parameters.put("durationSeconds", request.seconds());
        JsonBuilder payload = new JsonBuilder().put("instances", Json.arr(Json.obj("prompt", request.prompt())));
        if (request.extensions() != null) payload.putAll(request.extensions());
        if (!parameters.isEmpty() && !payload.has("parameters")) payload.put("parameters", parameters.build());
        return jsonPost("/" + GeminiContents.modelPath(cx.model()) + ":predictLongRunning", payload.build(), 120);
    }

    private static VideoJobInfo videoJobInfo(BuildContext cx, JsonObject data) {
        String name = data.get("name") instanceof dev.lm15.json.JsonString s ? s.value() : null;
        if (name == null || name.isEmpty()) throw providerError(cx, "gemini: video operation carries no name");
        VideoStatus status;
        if (data.get("done") instanceof dev.lm15.json.JsonBool b && b.value()) {
            status = data.get("error") instanceof JsonObject ? VideoStatus.FAILED : VideoStatus.COMPLETED;
        } else {
            status = VideoStatus.RUNNING; // operations expose no queued/running distinction before done
        }
        return new VideoJobInfo(name, status, null, null, null, data);
    }

    @Override public VideoJobInfo videoJob(BuildContext cx, String body, String videoId) {
        return videoJobInfo(cx, Json.parse(body).asObject());
    }

    @Override public WireRequest videoStatusRequest(BuildContext cx, String videoId) {
        return get(resourcePath(videoId), 60);
    }

    private static String videoResultUri(BuildContext cx, JsonObject statusBody) {
        JsonObject response = GeminiJson.objectAt(statusBody, "response");
        JsonObject gvr = GeminiJson.objectAt(response, "generateVideoResponse");
        JsonArray samples = GeminiJson.arrayAt(gvr, "generatedSamples");
        if (samples != null && !samples.isEmpty() && samples.get(0) instanceof JsonObject first) {
            JsonObject video = GeminiJson.objectAt(first, "video");
            if (video != null && video.get("uri") instanceof dev.lm15.json.JsonString s && !s.value().isEmpty()) return s.value();
        }
        throw providerError(cx, "gemini: terminal video operation carries no video uri");
    }

    /** The file URI is KEY-BOUND (403 without the header, verified live): the fetch rides the emit path with auth. */
    @Override public WireRequest videoResultFetch(BuildContext cx, JsonObject statusBody) {
        return absoluteGet(videoResultUri(cx, statusBody), 600);
    }

    /** A server-provided URL as a request: the query string split into decoded params (the vet's URL shape). */
    private static WireRequest absoluteGet(String url, long timeoutSeconds) {
        String base = url;
        String query = null;
        int q = url.indexOf('?');
        if (q >= 0) {
            base = url.substring(0, q);
            query = url.substring(q + 1);
            int hash = query.indexOf('#');
            if (hash >= 0) query = query.substring(0, hash);
        }
        WireRequest wire = timeout(new WireRequest("GET", null).absolute(base), timeoutSeconds);
        if (query != null && !query.isEmpty()) {
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                wire.param(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
            }
        }
        return wire;
    }

    @Override public VideoPart videoPart(BuildContext cx, JsonObject statusBody, HttpResponse fetched) {
        if (fetched == null) throw providerError(cx, "gemini: video content fetch is required");
        String contentType = fetched.header("content-type");
        if (contentType == null) contentType = "";
        int semi = contentType.indexOf(';');
        if (semi >= 0) contentType = contentType.substring(0, semi);
        contentType = contentType.strip();
        if (contentType.isEmpty()) throw providerError(cx, "gemini: video download carries no content-type");
        return new VideoPart(contentType, MediaPart.Media.encode(fetched.body()), null, null, null, List.of());
    }

    @Override public WireRequest videoListRequest(BuildContext cx, int limit, String model) {
        if (model == null || model.isEmpty()) {
            throw unsupported(cx, "gemini: video jobs list per model — pass model= (operations live under models/<model>/operations)");
        }
        return get("/" + GeminiContents.modelPath(model) + "/operations", 60).param("pageSize", Integer.toString(limit));
    }

    @Override public List<VideoJobInfo> videoJobs(BuildContext cx, String body) {
        JsonValue data = Json.parse(body);
        List<VideoJobInfo> out = new ArrayList<>();
        JsonArray ops = data instanceof JsonObject o ? GeminiJson.arrayAt(o, "operations") : null;
        if (ops != null) for (JsonValue op : ops) if (op instanceof JsonObject j) out.add(videoJobInfo(cx, j));
        return out;
    }

    // ─── live ───

    @Override public LiveCodec liveCodec(BuildContext cx, LiveConfig config) {
        return new GeminiLiveCodec(cx, config);
    }
}
