package dev.lm15.dialects.anthropic;

import dev.lm15.compat.AnthropicCompat;
import dev.lm15.compat.Compat;
import dev.lm15.dialects.Common;
import dev.lm15.dialects.Dialect;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.LM15Error;
import dev.lm15.errors.ProviderError;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBool;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonInt;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.registry.DialectId;
import dev.lm15.sse.SseEvent;
import dev.lm15.transport.HttpResponse;
import dev.lm15.types.*;
import dev.lm15.wire.BuildContext;
import dev.lm15.wire.Wire;
import dev.lm15.wire.WireRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The Anthropic Messages dialect: {@code POST /messages} for every door
 * that speaks the wire — api.anthropic.com, the Claude Code login, DeepSeek
 * / Meta / Moonshot over their Anthropic endpoints (compat presets), and
 * the cloud hosts ({@code Wire.emit} rewrites the URL, the model placement
 * and the version field). Stateless: everything per binding arrives in
 * the {@link BuildContext}; the policy is consulted at exactly the
 * reference's points — static headers ({@code anthropic-beta} joined with
 * the dialect's own betas) and the system prefix.
 *
 * <p>Surfaces: model listing ({@code GET /models}), the Files API and the
 * Message Batches API (single-step submit; results re-sorted to submission
 * order; the {@code ended} status split by request counts).
 */
public final class AnthropicDialect implements Dialect {
    public static final String ENDPOINT = "messages";
    public static final String API_FAMILY = "anthropic_messages";

    public AnthropicDialect() {}

    @Override public DialectId id() { return DialectId.ANTHROPIC; }

    @Override public Compat compat(String preset) { return AnthropicCompat.preset(preset); }

    @Override public Compat defaultCompat(String provider) { return AnthropicCompat.DEFAULT; }

    /** The binding's compat, resolved; a binding without one (or with another dialect's) gets the dialect defaults. */
    static AnthropicCompat compatOf(BuildContext cx) {
        return cx.compat() instanceof AnthropicCompat c ? c : AnthropicCompat.DEFAULT;
    }

    private static Map.Entry<String, String> h(String k, String v) { return Map.entry(k, v); }

    // ─── chat core ───

    @Override public WireRequest build(Request request, boolean stream, BuildContext cx) {
        JsonObject body = AnthropicBody.payload(request, stream, cx, compatOf(cx));
        WireRequest wire = WireRequest.post("/" + ENDPOINT, body).endpoint(ENDPOINT).model(cx.model());
        wire.headers.addAll(AnthropicBody.headers(request, cx, true));
        wire.readTimeout = Duration.ofSeconds(stream ? 120 : 60);
        return wire;
    }

    @Override public Response parseResponse(Request request, BuildContext cx, HttpResponse response) {
        return AnthropicResponses.parseResponse(cx.provider(), request, response);
    }

    @Override public List<StreamEvent> parseStreamEvent(Request request, BuildContext cx, SseEvent event) {
        return AnthropicResponses.parseStreamEvent(cx.provider(), request, event);
    }

    @Override public LM15Error normalizeError(BuildContext cx, int status, String body) {
        return AnthropicErrors.normalize(cx, status, body);
    }

    // ─── models (provisional endpoint) ───

    @Override public WireRequest modelsRequest(BuildContext cx) {
        // limit=1000 is the endpoint maximum; the catalog fits in one page
        // today (has_more=false observed live 2026-08-31).
        WireRequest wire = WireRequest.get("/models").param("limit", "1000");
        wire.headers.addAll(AnthropicBody.headers(null, cx, true));
        wire.readTimeout = Duration.ofSeconds(30);
        return wire;
    }

    @Override public List<ModelInfo> parseModels(BuildContext cx, String body) {
        JsonValue data = Json.parse(body);
        JsonValue entries = data instanceof JsonObject o ? o.opt("data") : null;
        return Common.modelInfosFromEntries(entries, cx.provider(), API_FAMILY, entry -> entry.opt("id") instanceof JsonString s ? s.value() : null);
    }

    // ─── files (Files API, GA) ───
    //
    // Wire shapes verified live 2026-08-31: multipart/form-data upload with NO
    // beta header (GA), file objects carry mime_type / size_bytes /
    // downloadable verbatim, list paginates with an opaque `next_page` token
    // passed back as `?page=`, download is refused for non-tool-generated files.

    private static WireRequest surfaceGet(String path, BuildContext cx, long timeoutSeconds) {
        WireRequest wire = WireRequest.get(path);
        wire.headers.addAll(AnthropicBody.headers(null, cx, true));
        wire.readTimeout = Duration.ofSeconds(timeoutSeconds);
        return wire;
    }

    @Override public WireRequest fileUploadRequest(BuildContext cx, FileUploadRequest request) {
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        if (request.extensions() != null) {
            for (Map.Entry<String, JsonValue> e : request.extensions().members().entrySet()) {
                JsonValue v = e.getValue();
                fields.add(h(e.getKey(), v instanceof JsonString s ? s.value() : v.toJson()));
            }
        }
        Map.Entry<String, byte[]> body = Common.multipartFormBody(fields,
            List.of(new Common.FilePart("file", request.filename(), request.mediaType(), request.bytes())));
        WireRequest wire = WireRequest.raw("POST", "/files", body.getKey(), body.getValue());
        wire.headers.addAll(AnthropicBody.headers(null, cx, false));
        wire.readTimeout = Duration.ofSeconds(300);
        return wire;
    }

    private static FileInfo fileInfo(BuildContext cx, JsonObject data) {
        String id = data.opt("id") instanceof JsonString s && !s.value().isEmpty() ? s.value() : null;
        if (id == null) throw new ProviderError("anthropic: file object carries no id", ErrorMeta.of(cx.provider()));
        String filename = data.opt("filename") instanceof JsonString s && !s.value().isEmpty() ? s.value() : null;
        String mime = data.opt("mime_type") instanceof JsonString s && !s.value().isEmpty() ? s.value() : null;
        Long size = data.opt("size_bytes") instanceof JsonInt i ? i.value().longValueExact() : null;
        Boolean downloadable = data.opt("downloadable") instanceof JsonBool b ? b.value() : null;
        return new FileInfo(id, filename, mime, size, Common.isoUtc(data.opt("created_at")), Common.isoUtc(data.opt("expires_at")),
            FileReadiness.READY, // Anthropic files have no processing state
            downloadable, data);
    }

    @Override public FileInfo fileInfo(BuildContext cx, String body) { return fileInfo(cx, Json.parse(body).asObject()); }

    @Override public WireRequest fileGetRequest(BuildContext cx, String fileId) {
        return surfaceGet("/files/" + Wire.pathId(fileId, false), cx, 60);
    }

    @Override public WireRequest fileListRequest(BuildContext cx, int limit, String cursor) {
        WireRequest wire = surfaceGet("/files", cx, 60).param("limit", Integer.toString(limit));
        if (cursor != null) wire.param("page", cursor);
        return wire;
    }

    @Override public FilePage filePage(BuildContext cx, String body) {
        JsonObject data = Json.parse(body).asObject();
        List<FileInfo> items = new ArrayList<>();
        if (data.opt("data") instanceof JsonArray entries) {
            for (JsonValue entry : entries) if (entry instanceof JsonObject o) items.add(fileInfo(cx, o));
        }
        String cursor = data.opt("next_page") instanceof JsonString s && !s.value().isEmpty() ? s.value() : null;
        return new FilePage(items, cursor);
    }

    @Override public WireRequest fileDeleteRequest(BuildContext cx, String fileId) {
        WireRequest wire = WireRequest.delete("/files/" + Wire.pathId(fileId, false));
        wire.headers.addAll(AnthropicBody.headers(null, cx, true));
        wire.readTimeout = Duration.ofSeconds(60);
        return wire;
    }

    @Override public WireRequest fileDownloadRequest(BuildContext cx, String fileId) {
        return surfaceGet("/files/" + Wire.pathId(fileId, false) + "/content", cx, 300);
    }

    // ─── batch (Message Batches API) ───

    /** Single-step wire: no upload before submit. */
    @Override public WireRequest batchUploadRequest(BuildContext cx, BatchRequest request) { return null; }

    @Override public WireRequest batchSubmitRequest(BuildContext cx, BatchRequest request, JsonObject uploadBody) {
        if (request.label() != null) {
            throw new UnsupportedFeatureError("anthropic: batch labels are not supported — the Message Batches "
                + "create body has no metadata field (verified live 2026-08-31); submit without a label and correlate by id",
                ErrorMeta.of(cx.provider()));
        }
        AnthropicCompat compat = compatOf(cx);
        List<JsonValue> requests = new ArrayList<>();
        int i = 0;
        for (Request nested : request.requests()) {
            requests.add(Json.obj("custom_id", Integer.toString(i), "params", AnthropicBody.payload(nested, false, cx.withModel(nested.model()), compat)));
            i++;
        }
        JsonBuilder payload = new JsonBuilder().put("requests", new JsonArray(requests));
        if (request.extensions() != null) payload.putAll(request.extensions());
        WireRequest wire = WireRequest.post("/messages/batches", payload.build());
        wire.headers.addAll(AnthropicBody.headers(null, cx, true));
        wire.readTimeout = Duration.ofSeconds(120);
        return wire;
    }

    /**
     * {@code ended} is Anthropic's single terminal processing_status; the
     * canonical terminal splits on request_counts — all-cancelled →
     * cancelled, all-expired → expired, anything else → completed.
     */
    static BatchStatus batchStatus(JsonObject data) {
        String status = data.opt("processing_status") instanceof JsonString s ? s.value().toLowerCase() : "";
        switch (status) {
            case "in_progress": return BatchStatus.RUNNING;
            case "canceling": return BatchStatus.CANCELLING;
            case "ended": {
                JsonObject counts = data.opt("request_counts") instanceof JsonObject c ? c : JsonObject.EMPTY;
                long canceled = count(counts, "canceled"), succeeded = count(counts, "succeeded"), errored = count(counts, "errored"), expired = count(counts, "expired");
                if (canceled != 0 && succeeded == 0 && errored == 0 && expired == 0) return BatchStatus.CANCELLED;
                if (expired != 0 && succeeded == 0 && errored == 0 && canceled == 0) return BatchStatus.EXPIRED;
                return BatchStatus.COMPLETED;
            }
            default: return BatchStatus.QUEUED;
        }
    }

    private static long count(JsonObject counts, String key) {
        JsonValue v = counts.opt(key);
        try {
            if (v instanceof JsonInt i) return i.value().longValueExact();
            if (v instanceof dev.lm15.json.JsonFloat f) return (long) f.value();
            if (v instanceof JsonString s) return Long.parseLong(s.value().strip());
            if (v instanceof JsonBool b) return b.value() ? 1 : 0;
            return 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static BatchJobInfo batchJob(BuildContext cx, JsonObject data) {
        String id = data.opt("id") instanceof JsonString s && !s.value().isEmpty() ? s.value() : null;
        if (id == null) throw new ProviderError("anthropic: batch object carries no id", ErrorMeta.of(cx.provider()));
        return new BatchJobInfo(id, batchStatus(data), null, Common.isoUtc(data.opt("created_at")), data);
    }

    @Override public BatchJobInfo batchJob(BuildContext cx, String body) { return batchJob(cx, Json.parse(body).asObject()); }

    @Override public WireRequest batchStatusRequest(BuildContext cx, String batchId) {
        return surfaceGet("/messages/batches/" + Wire.pathId(batchId, false), cx, 60);
    }

    @Override public WireRequest batchCancelRequest(BuildContext cx, String batchId) {
        WireRequest wire = new WireRequest("POST", "/messages/batches/" + Wire.pathId(batchId, false) + "/cancel");
        wire.headers.addAll(AnthropicBody.headers(null, cx, true));
        wire.readTimeout = Duration.ofSeconds(60);
        return wire;
    }

    @Override public List<WireRequest> batchResultFetches(BuildContext cx, JsonObject statusBody) {
        String url = statusBody.opt("results_url") instanceof JsonString s && !s.value().isEmpty() ? s.value() : null;
        if (url == null) throw new ProviderError("anthropic: ended batch carries no results_url", ErrorMeta.of(cx.provider()));
        // A server-provided URL is used verbatim (MAP-11 rule 4).
        WireRequest wire = surfaceGet("", cx, 300).absolute(url);
        return List.of(wire);
    }

    @Override public List<BatchEntry> batchEntries(BuildContext cx, JsonObject statusBody, List<String> fetched) {
        List<BatchEntry> entries = new ArrayList<>();
        String text = fetched.isEmpty() ? "" : fetched.get(0);
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            JsonObject item = Json.parse(line).asObject();
            JsonValue customId = item.opt("custom_id");
            int index = Integer.parseInt(customId instanceof JsonString s ? s.value() : customId == null ? "None" : customId.toJson());
            JsonObject result = item.opt("result") instanceof JsonObject r ? r : JsonObject.EMPTY;
            String rtype = AnthropicResponses.textOr(result.opt("type"));
            switch (rtype) {
                case "succeeded" -> {
                    JsonObject message = result.opt("message") instanceof JsonObject m ? m : JsonObject.EMPTY;
                    // Batch results outlive the submitting process, so the original
                    // Request is not available; parse_response only reads its model
                    // as a fallback when the body lacks one.
                    String model = message.opt("model") instanceof JsonString s && !s.value().isEmpty() ? s.value() : "batch";
                    Request synthetic = new Request(model, List.of(Message.user("-")));
                    Response response = AnthropicResponses.parseResponse(cx.provider(), synthetic,
                        HttpResponse.json(200, Json.write(message).getBytes(StandardCharsets.UTF_8)));
                    entries.add(new BatchEntry(index, BatchOutcome.SUCCEEDED, response, null));
                }
                case "errored" -> {
                    JsonValue raw = result.opt("error");
                    JsonValue envelope = raw instanceof JsonObject o && o.has("error") ? o : Json.obj("error", raw == null ? JsonObject.EMPTY : raw);
                    LM15Error err = AnthropicErrors.normalize(cx, 400, Json.write(envelope));
                    String message = err.message().isEmpty() ? "batch entry errored" : err.message();
                    entries.add(new BatchEntry(index, BatchOutcome.ERRORED, null, new ErrorDetail(err.code(), message, err.providerCode())));
                }
                case "canceled" -> entries.add(new BatchEntry(index, BatchOutcome.CANCELLED, null, null));
                case "expired" -> entries.add(new BatchEntry(index, BatchOutcome.EXPIRED, null, null));
                default -> entries.add(new BatchEntry(index, BatchOutcome.ERRORED, null,
                    new ErrorDetail(ErrorCode.PROVIDER, "unrecognized batch result type '" + rtype + "'")));
            }
        }
        entries.sort(Comparator.comparingInt(BatchEntry::index));
        return entries;
    }

    @Override public WireRequest batchListRequest(BuildContext cx, int limit) {
        return surfaceGet("/messages/batches", cx, 60).param("limit", Integer.toString(limit));
    }

    @Override public List<BatchJobInfo> batchJobs(BuildContext cx, String body) {
        JsonValue data = Json.parse(body);
        List<BatchJobInfo> out = new ArrayList<>();
        if (data instanceof JsonObject o && o.opt("data") instanceof JsonArray items) {
            for (JsonValue item : items) if (item instanceof JsonObject j) out.add(batchJob(cx, j));
        }
        return out;
    }
}
