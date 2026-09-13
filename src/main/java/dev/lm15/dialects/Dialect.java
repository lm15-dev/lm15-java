package dev.lm15.dialects;

import dev.lm15.compat.Compat;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.LM15Error;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.json.JsonObject;
import dev.lm15.live.LiveCodec;
import dev.lm15.registry.DialectId;
import dev.lm15.sse.SseEvent;
import dev.lm15.transport.HttpResponse;
import dev.lm15.types.*;
import dev.lm15.wire.BuildContext;
import dev.lm15.wire.WireRequest;

import java.util.List;

/**
 * A wire codec (one of four). Implementations are stateless values;
 * everything that varies per binding arrives in the {@link BuildContext}.
 * A dialect builds from a validated Request and never re-validates it.
 * Every surface hook defaults to a typed refusal; a dialect implements the
 * ones its wire has.
 */
public interface Dialect {
    DialectId id();

    /** The compat value for a preset name (data tables copied from the reference); a ValueError for an unknown preset. */
    Compat compat(String preset);

    /** The compat a provider gets when none is named (its own preset, or the dialect's default). */
    Compat defaultCompat(String provider);

    /** The header an ApiKey travels under when the policy selects x-api-key (Gemini spells it x-goog-api-key). */
    default String apiKeyHeader() { return "x-api-key"; }

    // ─── chat core ───

    /** The request before auth and host work; refusals (MAP-5..8, MAP-10) raise here, before any wire. */
    WireRequest build(Request request, boolean stream, BuildContext cx);

    /** A complete 2xx body as the canonical Response (MAP-1, MAP-2); unmapped content under provider_data._lm15_unmapped. */
    Response parseResponse(Request request, BuildContext cx, HttpResponse response);

    /** One SSE frame as zero or more PRE-coalesce canonical events (per-frame end events allowed; the Coalescer merges). */
    List<StreamEvent> parseStreamEvent(Request request, BuildContext cx, SseEvent event);

    /** An error body as the typed provider error (class + code + provider_code); status mapping per spec/vocabularies.md. */
    LM15Error normalizeError(BuildContext cx, int status, String body);

    // ─── ingest (MAP-12; the chat dialect only) ───

    default Request requestFromOpenAIChat(BuildContext cx, JsonObject body) {
        throw ValidationException.value("provider '" + cx.provider() + "' does not speak the Chat Completions wire; nothing to ingest");
    }

    default Response responseFromOpenAIChat(BuildContext cx, JsonObject body, String model, Integer choice) {
        throw ValidationException.value("provider '" + cx.provider() + "' does not speak the Chat Completions wire");
    }

    // ─── models ───

    default WireRequest modelsRequest(BuildContext cx) { throw unsupported(cx, "model listing"); }
    default List<ModelInfo> parseModels(BuildContext cx, String body) { throw unsupported(cx, "model listing"); }

    // ─── files ───

    default WireRequest fileUploadRequest(BuildContext cx, FileUploadRequest request) { throw unsupported(cx, "files"); }
    default FileInfo fileInfo(BuildContext cx, String body) { throw unsupported(cx, "files"); }
    default WireRequest fileGetRequest(BuildContext cx, String fileId) { throw unsupported(cx, "files"); }
    default WireRequest fileListRequest(BuildContext cx, int limit, String cursor) { throw unsupported(cx, "files"); }
    default FilePage filePage(BuildContext cx, String body) { throw unsupported(cx, "files"); }
    default WireRequest fileDeleteRequest(BuildContext cx, String fileId) { throw unsupported(cx, "files"); }
    default WireRequest fileDownloadRequest(BuildContext cx, String fileId) { throw unsupported(cx, "files"); }

    // ─── batch ───

    /** The optional pre-submit upload (OpenAI's JSONL file); null on a single-step wire. */
    default WireRequest batchUploadRequest(BuildContext cx, BatchRequest request) { throw unsupported(cx, "batch"); }
    default WireRequest batchSubmitRequest(BuildContext cx, BatchRequest request, JsonObject uploadBody) { throw unsupported(cx, "batch"); }
    default BatchJobInfo batchJob(BuildContext cx, String body) { throw unsupported(cx, "batch"); }
    default WireRequest batchStatusRequest(BuildContext cx, String batchId) { throw unsupported(cx, "batch"); }
    default WireRequest batchCancelRequest(BuildContext cx, String batchId) { throw unsupported(cx, "batch"); }
    default List<WireRequest> batchResultFetches(BuildContext cx, JsonObject statusBody) { throw unsupported(cx, "batch"); }
    default List<BatchEntry> batchEntries(BuildContext cx, JsonObject statusBody, List<String> fetched) { throw unsupported(cx, "batch"); }
    default WireRequest batchListRequest(BuildContext cx, int limit) { throw unsupported(cx, "batch"); }
    default List<BatchJobInfo> batchJobs(BuildContext cx, String body) { throw unsupported(cx, "batch"); }

    // ─── stored caches (MAP-6 resource tier) ───

    default WireRequest cacheCreateRequest(BuildContext cx, Request prefix, Integer ttlSeconds, String label) { throw unsupported(cx, "stored caches"); }
    default CacheInfo cacheInfo(BuildContext cx, String body) { throw unsupported(cx, "stored caches"); }
    default WireRequest cacheGetRequest(BuildContext cx, String cacheId) { throw unsupported(cx, "stored caches"); }
    default WireRequest cacheListRequest(BuildContext cx, int limit, String cursor) { throw unsupported(cx, "stored caches"); }
    default CachePage cachePage(BuildContext cx, String body) { throw unsupported(cx, "stored caches"); }
    default WireRequest cacheDeleteRequest(BuildContext cx, String cacheId) { throw unsupported(cx, "stored caches"); }
    default WireRequest cacheUpdateRequest(BuildContext cx, String cacheId, int ttlSeconds) { throw unsupported(cx, "stored caches"); }

    // ─── generation ───

    default WireRequest imageGenerateRequest(BuildContext cx, ImageGenerationRequest request) { throw unsupported(cx, "image generation"); }
    default ImageGenerationResponse imageGeneration(BuildContext cx, ImageGenerationRequest request, HttpResponse response) { throw unsupported(cx, "image generation"); }
    default WireRequest speechGenerateRequest(BuildContext cx, SpeechGenerationRequest request) { throw unsupported(cx, "speech generation"); }
    default SpeechGenerationResponse speechGeneration(BuildContext cx, SpeechGenerationRequest request, HttpResponse response) { throw unsupported(cx, "speech generation"); }

    // ─── video ───

    default WireRequest videoSubmitRequest(BuildContext cx, VideoGenerationRequest request) { throw unsupported(cx, "video generation"); }
    /** Parse a submit or status body; {@code videoId} is the known ticket for wires whose bodies do not echo it (xAI). */
    default VideoJobInfo videoJob(BuildContext cx, String body, String videoId) { throw unsupported(cx, "video generation"); }
    default WireRequest videoStatusRequest(BuildContext cx, String videoId) { throw unsupported(cx, "video generation"); }
    /** The optional download step; null when the terminal body carries the URL. */
    default WireRequest videoResultFetch(BuildContext cx, JsonObject statusBody) { throw unsupported(cx, "video generation"); }
    default VideoPart videoPart(BuildContext cx, JsonObject statusBody, HttpResponse fetched) { throw unsupported(cx, "video generation"); }
    default WireRequest videoListRequest(BuildContext cx, int limit, String model) { throw unsupported(cx, "video generation"); }
    default List<VideoJobInfo> videoJobs(BuildContext cx, String body) { throw unsupported(cx, "video generation"); }

    // ─── live ───

    default LiveCodec liveCodec(BuildContext cx, LiveConfig config) { throw unsupported(cx, "live"); }

    static UnsupportedFeatureError unsupported(BuildContext cx, String surface) {
        return new UnsupportedFeatureError(cx.provider() + ": " + surface + " not supported", ErrorMeta.of(cx.provider()));
    }
}
