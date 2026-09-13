package dev.lm15.dialects.openai;

import dev.lm15.compat.Compat;
import dev.lm15.compat.OpenAIResponsesCompat;
import dev.lm15.dialects.Dialect;
import dev.lm15.errors.LM15Error;
import dev.lm15.json.Json;
import dev.lm15.json.JsonObject;
import dev.lm15.live.LiveCodec;
import dev.lm15.registry.DialectId;
import dev.lm15.sse.SseEvent;
import dev.lm15.transport.HttpResponse;
import dev.lm15.types.*;
import dev.lm15.wire.BuildContext;
import dev.lm15.wire.WireRequest;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The OpenAI Responses dialect (the reference's {@code OpenAILM}): OpenAI's
 * public API, the ChatGPT Codex backend, Azure OpenAI v1, Meta and Moonshot
 * over the Responses wire. Policy consult points: the static headers, the
 * {@code chatgpt-account-id} header when an account is bound, and the
 * {@code backend} switch at four stated places — payload defaults
 * (instructions prefix, {@code store: false}, streaming-only, no max-token
 * knob), the {@code {"detail": ...}} error envelope, and the {@code /models}
 * endpoint shape.
 */
public final class OpenAIResponsesDialect implements Dialect {
    public OpenAIResponsesDialect() {}

    @Override public DialectId id() { return DialectId.OPENAI_RESPONSES; }

    @Override public Compat compat(String preset) { return OpenAIResponsesCompat.preset(preset); }

    @Override public Compat defaultCompat(String provider) { return OpenAIResponsesCompat.preset("openai"); }

    /** The dialect's own headers (never the credential): the Codex account id and the policy's static headers. */
    static void addHeaders(WireRequest w, BuildContext cx) {
        // The reference sends Content-Type on every request, GETs included (a multipart body brings its own).
        if (w.headers.stream().noneMatch(h -> h.getKey().equalsIgnoreCase("content-type"))) w.header("content-type", "application/json");
        if (ResponsesRequest.isCodex(cx) && cx.accountId() != null && !cx.accountId().isEmpty()) {
            w.header("chatgpt-account-id", cx.accountId());
        }
        for (Map.Entry<String, String> h : cx.policy().headers()) w.header(h.getKey(), h.getValue());
    }

    // ─── chat core ───

    @Override public WireRequest build(Request request, boolean stream, BuildContext cx) {
        JsonObject payload = ResponsesRequest.payload(request, stream, cx);
        WireRequest w = WireRequest.post("/responses", payload).endpoint("responses").model(cx.model());
        addHeaders(w, cx);
        w.readTimeout = Duration.ofSeconds(stream ? 120 : 60);
        return w;
    }

    @Override public Response parseResponse(Request request, BuildContext cx, HttpResponse response) {
        return ResponsesResponse.parse(request, cx, response.json().asObject());
    }

    @Override public List<StreamEvent> parseStreamEvent(Request request, BuildContext cx, SseEvent event) {
        return ResponsesResponse.streamEvents(request, cx, event);
    }

    @Override public LM15Error normalizeError(BuildContext cx, int status, String body) {
        return OpenAIErrors.normalize(cx, status, body);
    }

    // ─── models ───

    @Override public WireRequest modelsRequest(BuildContext cx) { return OpenAISurfaces.modelsRequest(cx); }
    @Override public List<ModelInfo> parseModels(BuildContext cx, String body) { return OpenAISurfaces.parseModels(cx, body); }

    // ─── files ───

    @Override public WireRequest fileUploadRequest(BuildContext cx, FileUploadRequest request) { return OpenAISurfaces.fileUpload(cx, request); }
    @Override public FileInfo fileInfo(BuildContext cx, String body) { return OpenAISurfaces.fileInfo(cx, Json.parseObject(body)); }
    @Override public WireRequest fileGetRequest(BuildContext cx, String fileId) { return OpenAISurfaces.fileGet(cx, fileId); }
    @Override public WireRequest fileListRequest(BuildContext cx, int limit, String cursor) { return OpenAISurfaces.fileList(cx, limit, cursor); }
    @Override public FilePage filePage(BuildContext cx, String body) { return OpenAISurfaces.filePage(cx, Json.parseObject(body)); }
    @Override public WireRequest fileDeleteRequest(BuildContext cx, String fileId) { return OpenAISurfaces.fileDelete(cx, fileId); }
    @Override public WireRequest fileDownloadRequest(BuildContext cx, String fileId) { return OpenAISurfaces.fileDownload(cx, fileId); }

    // ─── batch ───

    @Override public WireRequest batchUploadRequest(BuildContext cx, BatchRequest request) { return OpenAISurfaces.batchUpload(cx, request); }
    @Override public WireRequest batchSubmitRequest(BuildContext cx, BatchRequest request, JsonObject uploadBody) { return OpenAISurfaces.batchSubmit(cx, request, uploadBody); }
    @Override public BatchJobInfo batchJob(BuildContext cx, String body) { return OpenAISurfaces.batchJobInfo(cx, Json.parseObject(body)); }
    @Override public WireRequest batchStatusRequest(BuildContext cx, String batchId) { return OpenAISurfaces.batchStatusRequest(cx, batchId); }
    @Override public WireRequest batchCancelRequest(BuildContext cx, String batchId) { return OpenAISurfaces.batchCancel(cx, batchId); }
    @Override public List<WireRequest> batchResultFetches(BuildContext cx, JsonObject statusBody) { return OpenAISurfaces.batchResultFetches(cx, statusBody); }
    @Override public List<BatchEntry> batchEntries(BuildContext cx, JsonObject statusBody, List<String> fetched) { return OpenAISurfaces.batchEntries(cx, statusBody, fetched); }
    @Override public WireRequest batchListRequest(BuildContext cx, int limit) { return OpenAISurfaces.batchList(cx, limit); }
    @Override public List<BatchJobInfo> batchJobs(BuildContext cx, String body) { return OpenAISurfaces.batchJobs(cx, body); }

    // ─── generation ───

    @Override public WireRequest imageGenerateRequest(BuildContext cx, ImageGenerationRequest request) { return OpenAISurfaces.imageGenerate(cx, request); }
    @Override public ImageGenerationResponse imageGeneration(BuildContext cx, ImageGenerationRequest request, HttpResponse response) { return OpenAISurfaces.imageGeneration(cx, request, response); }
    @Override public WireRequest speechGenerateRequest(BuildContext cx, SpeechGenerationRequest request) { return OpenAISurfaces.speechGenerate(cx, request); }
    @Override public SpeechGenerationResponse speechGeneration(BuildContext cx, SpeechGenerationRequest request, HttpResponse response) { return OpenAISurfaces.speechGeneration(cx, request, response); }

    // ─── video ───

    @Override public WireRequest videoSubmitRequest(BuildContext cx, VideoGenerationRequest request) { return OpenAISurfaces.videoSubmit(cx, request); }
    @Override public VideoJobInfo videoJob(BuildContext cx, String body, String videoId) { return OpenAISurfaces.videoJobInfo(cx, Json.parseObject(body)); }
    @Override public WireRequest videoStatusRequest(BuildContext cx, String videoId) { return OpenAISurfaces.videoStatusRequest(cx, videoId); }
    @Override public WireRequest videoResultFetch(BuildContext cx, JsonObject statusBody) { return OpenAISurfaces.videoResultFetch(cx, statusBody); }
    @Override public VideoPart videoPart(BuildContext cx, JsonObject statusBody, HttpResponse fetched) { return OpenAISurfaces.videoPart(cx, statusBody, fetched); }
    @Override public WireRequest videoListRequest(BuildContext cx, int limit, String model) { return OpenAISurfaces.videoList(cx, limit, model); }
    @Override public List<VideoJobInfo> videoJobs(BuildContext cx, String body) { return OpenAISurfaces.videoJobs(cx, body); }

    // ─── live ───

    @Override public LiveCodec liveCodec(BuildContext cx, LiveConfig config) { return new OpenAILiveCodec(cx, config); }
}
