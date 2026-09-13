package dev.lm15.dialects.openai;

import dev.lm15.compat.OpenAIResponsesCompat;
import dev.lm15.dialects.Common;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.LM15Error;
import dev.lm15.errors.ProviderError;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonFloat;
import dev.lm15.json.JsonInt;
import dev.lm15.json.JsonNull;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.transport.HttpResponse;
import dev.lm15.types.*;
import dev.lm15.wire.BuildContext;
import dev.lm15.wire.Wire;
import dev.lm15.wire.WireRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The OpenAI endpoint surfaces: Files, Batch (over /v1/responses), image/speech generation, Sora video jobs. */
final class OpenAISurfaces {
    private OpenAISurfaces() {}

    static String pathId(String id) { return Wire.pathId(id, false); }

    private static WireRequest timed(WireRequest w, double seconds) {
        w.readTimeout = Duration.ofMillis((long) (seconds * 1000));
        return w;
    }

    // ─── models ───

    static WireRequest modelsRequest(BuildContext cx) {
        WireRequest w = WireRequest.get("/models");
        if (ResponsesRequest.isCodex(cx)) {
            // The Codex backend's /models requires a client_version query parameter (a Codex CLI release).
            w.param("client_version", cx.policy().backendOptions().getOrDefault("client_version", ""));
        }
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 30.0);
    }

    static List<ModelInfo> parseModels(BuildContext cx, String body) {
        JsonValue data = Json.parse(body);
        if (ResponsesRequest.isCodex(cx)) {
            JsonValue entries = data instanceof JsonObject o ? o.get("models") : null;
            return Common.modelInfosFromEntries(entries, cx.provider(), "openai_responses", e -> Js.strOrNull(e.get("slug")));
        }
        JsonValue entries = data instanceof JsonObject o ? o.get("data") : null;
        return Common.modelInfosFromEntries(entries, cx.provider(), "openai_responses", e -> Js.strOrNull(e.get("id")));
    }

    // ─── files ───

    static WireRequest fileUpload(BuildContext cx, FileUploadRequest request) {
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        String purpose = "user_data";
        List<Map.Entry<String, String>> extra = new ArrayList<>();
        if (request.extensions() != null) {
            for (Map.Entry<String, JsonValue> e : request.extensions().members().entrySet()) {
                if (e.getKey().equals("purpose")) purpose = Js.pyStr(e.getValue());
                else extra.add(Map.entry(e.getKey(), Js.pyStr(e.getValue())));
            }
        }
        fields.add(Map.entry("purpose", purpose));
        fields.addAll(extra);
        Map.Entry<String, byte[]> body = Common.multipartFormBody(fields,
            List.of(new Common.FilePart("file", request.filename(), request.mediaType(), request.bytes())));
        WireRequest w = WireRequest.raw("POST", "/files", body.getKey(), body.getValue());
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 300.0);
    }

    static FileInfo fileInfo(BuildContext cx, JsonObject data) {
        JsonValue idv = data.get("id");
        if (!(idv instanceof JsonString ids) || ids.value().isEmpty()) throw new ProviderError("openai: file object carries no id", ErrorMeta.of(cx.provider()));
        JsonValue fn = data.get("filename");
        JsonValue bytes = data.get("bytes");
        return new FileInfo(ids.value(), fn instanceof JsonString f && !f.value().isEmpty() ? f.value() : null, null,
            bytes instanceof JsonInt b ? b.value().longValueExact() : null, Common.isoUtc(data.get("created_at")), Common.isoUtc(data.get("expires_at")),
            Common.openaiFileReadiness(data.get("status")), null, data);
    }

    static WireRequest fileGet(BuildContext cx, String fileId) {
        WireRequest w = WireRequest.get("/files/" + pathId(fileId));
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 60.0);
    }

    static WireRequest fileList(BuildContext cx, int limit, String cursor) {
        WireRequest w = WireRequest.get("/files").param("limit", Integer.toString(limit));
        if (cursor != null) w.param("after", cursor);
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 60.0);
    }

    static FilePage filePage(BuildContext cx, JsonObject data) {
        List<FileInfo> items = new ArrayList<>();
        for (JsonValue entry : Js.arr(data, "data")) if (entry instanceof JsonObject o) items.add(fileInfo(cx, o));
        String cursor = null;
        if (Js.truthy(data.get("has_more")) && !items.isEmpty() && data.get("last_id") instanceof JsonString s && !s.value().isEmpty()) cursor = s.value();
        return new FilePage(items, cursor);
    }

    static WireRequest fileDelete(BuildContext cx, String fileId) {
        WireRequest w = WireRequest.delete("/files/" + pathId(fileId));
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 60.0);
    }

    static WireRequest fileDownload(BuildContext cx, String fileId) {
        WireRequest w = WireRequest.get("/files/" + pathId(fileId) + "/content");
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 300.0);
    }

    // ─── batch ───

    static BatchStatus batchStatus(String status) {
        return switch (status.toLowerCase()) {
            case "completed" -> BatchStatus.COMPLETED;
            case "failed" -> BatchStatus.FAILED;
            case "cancelled" -> BatchStatus.CANCELLED;
            case "expired" -> BatchStatus.EXPIRED;
            case "cancelling", "canceling" -> BatchStatus.CANCELLING;
            case "in_progress", "finalizing" -> BatchStatus.RUNNING;
            default -> BatchStatus.QUEUED; // validating / queued / anything pre-run
        };
    }

    static WireRequest batchUpload(BuildContext cx, BatchRequest request) {
        StringBuilder lines = new StringBuilder();
        List<Request> nested = request.requests();
        for (int i = 0; i < nested.size(); i++) {
            Request r = nested.get(i);
            JsonObject line = Json.obj("custom_id", Integer.toString(i), "method", "POST", "url", "/v1/responses",
                "body", ResponsesRequest.payload(r, false, cx.withModel(r.model())));
            lines.append(Json.write(line)).append('\n');
        }
        byte[] data = lines.toString().getBytes(StandardCharsets.UTF_8);
        Map.Entry<String, byte[]> body = Common.multipartFormBody(List.of(Map.entry("purpose", "batch")),
            List.of(new Common.FilePart("file", "lm15-batch.jsonl", "application/jsonl", data)));
        WireRequest w = WireRequest.raw("POST", "/files", body.getKey(), body.getValue());
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 300.0);
    }

    static WireRequest batchSubmit(BuildContext cx, BatchRequest request, JsonObject uploadBody) {
        JsonValue idv = uploadBody == null ? null : uploadBody.get("id");
        if (!(idv instanceof JsonString ids) || ids.value().isEmpty()) {
            throw new ProviderError("openai: batch input file upload returned no id", ErrorMeta.of(cx.provider()));
        }
        JsonObject ext = request.extensions() == null ? JsonObject.EMPTY : request.extensions();
        JsonBuilder payload = new JsonBuilder().put("input_file_id", ids.value())
            .put("endpoint", ext.has("endpoint") ? ext.get("endpoint") : new JsonString("/v1/responses"))
            .put("completion_window", ext.has("completion_window") ? ext.get("completion_window") : new JsonString("24h"));
        if (request.label() != null) payload.put("metadata", Json.obj("label", request.label()));
        for (Map.Entry<String, JsonValue> e : ext.members().entrySet()) {
            if (e.getKey().equals("endpoint") || e.getKey().equals("completion_window")) continue;
            payload.put(e.getKey(), e.getValue());
        }
        WireRequest w = WireRequest.post("/batches", payload.build());
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 120.0);
    }

    static BatchJobInfo batchJobInfo(BuildContext cx, JsonObject data) {
        JsonValue idv = data.get("id");
        if (!(idv instanceof JsonString ids) || ids.value().isEmpty()) throw new ProviderError("openai: batch object carries no id", ErrorMeta.of(cx.provider()));
        JsonValue label = Js.obj(data, "metadata").get("label");
        return new BatchJobInfo(ids.value(), batchStatus(Js.str(data, "status")),
            label instanceof JsonString l && !l.value().isEmpty() ? l.value() : null, Common.isoUtc(data.get("created_at")), data);
    }

    static WireRequest batchStatusRequest(BuildContext cx, String batchId) {
        WireRequest w = WireRequest.get("/batches/" + pathId(batchId));
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 60.0);
    }

    static WireRequest batchCancel(BuildContext cx, String batchId) {
        WireRequest w = new WireRequest("POST", "/batches/" + pathId(batchId) + "/cancel");
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 60.0);
    }

    static List<WireRequest> batchResultFetches(BuildContext cx, JsonObject statusBody) {
        List<WireRequest> out = new ArrayList<>();
        for (String key : new String[] {"output_file_id", "error_file_id"}) {
            JsonValue v = statusBody.get(key);
            if (v instanceof JsonString s && !s.value().isEmpty()) {
                WireRequest w = WireRequest.get("/files/" + pathId(s.value()) + "/content");
                OpenAIResponsesDialect.addHeaders(w, cx);
                out.add(timed(w, 300.0));
            }
        }
        return out;
    }

    static List<BatchEntry> batchEntries(BuildContext cx, JsonObject statusBody, List<String> fetched) {
        BatchStatus jobStatus = batchStatus(Js.str(statusBody, "status"));
        TreeMap<Integer, BatchEntry> found = new TreeMap<>();
        for (String text : fetched) {
            for (String line : text.split("\\R")) {
                if (line.isBlank()) continue;
                JsonObject item = Json.parse(line).asObject();
                int index = Integer.parseInt(Js.pyStr(item.get("custom_id")).strip());
                JsonObject responseObj = Js.obj(item, "response");
                Integer sc = Js.intOrNull(responseObj.get("status_code"));
                int statusCode = sc == null ? 0 : sc;
                JsonObject bodyObj = Js.obj(responseObj, "body");
                if (statusCode == 200 && !bodyObj.isEmpty()) {
                    String model = bodyObj.get("model") instanceof JsonString m && !m.value().isEmpty() ? m.value() : "batch";
                    Request req = new Request(model, List.of(Message.user("-")));
                    Response response = ResponsesResponse.parse(req, cx, bodyObj);
                    found.put(index, new BatchEntry(index, BatchOutcome.SUCCEEDED, response, null));
                } else {
                    JsonValue errSource = !bodyObj.isEmpty() ? bodyObj : Js.truthy(item.get("error")) ? item.get("error") : JsonObject.EMPTY;
                    LM15Error err = OpenAIErrors.normalize(cx, statusCode != 0 ? statusCode : 400, Json.writePythonStyle(errSource));
                    String message = err.message() == null || err.message().isEmpty() ? "batch entry errored" : err.message();
                    found.put(index, new BatchEntry(index, BatchOutcome.ERRORED, null, new ErrorDetail(err.code(), message, err.providerCode())));
                }
            }
        }
        Integer total = Js.intOrNull(Js.obj(statusBody, "request_counts").get("total"));
        int count = total != null && total != 0 ? total : found.isEmpty() ? 0 : found.lastKey() + 1;
        BatchOutcome fill = jobStatus == BatchStatus.EXPIRED ? BatchOutcome.EXPIRED : jobStatus == BatchStatus.CANCELLED ? BatchOutcome.CANCELLED : BatchOutcome.ERRORED;
        List<BatchEntry> entries = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            BatchEntry e = found.get(index);
            if (e != null) entries.add(e);
            else if (fill == BatchOutcome.ERRORED) {
                entries.add(new BatchEntry(index, BatchOutcome.ERRORED, null, new ErrorDetail(ErrorCode.PROVIDER, "entry missing from batch output files", null)));
            } else {
                entries.add(new BatchEntry(index, fill, null, null));
            }
        }
        return entries;
    }

    static WireRequest batchList(BuildContext cx, int limit) {
        WireRequest w = WireRequest.get("/batches").param("limit", Integer.toString(limit));
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 60.0);
    }

    static List<BatchJobInfo> batchJobs(BuildContext cx, String body) {
        JsonValue data = Json.parse(body);
        List<BatchJobInfo> out = new ArrayList<>();
        if (data instanceof JsonObject o) for (JsonValue item : Js.arr(o, "data")) if (item instanceof JsonObject j) out.add(batchJobInfo(cx, j));
        return out;
    }

    // ─── video (Sora) ───

    static VideoStatus videoStatus(String wire) {
        return switch (wire) {
            case "queued" -> VideoStatus.QUEUED;
            case "in_progress" -> VideoStatus.RUNNING;
            case "completed" -> VideoStatus.COMPLETED;
            case "failed" -> VideoStatus.FAILED;
            case "cancelled" -> VideoStatus.CANCELLED;
            default -> null;
        };
    }

    static WireRequest videoSubmit(BuildContext cx, VideoGenerationRequest request) {
        if (!request.images().isEmpty()) {
            throw new UnsupportedFeatureError("openai: video input images (input_reference) are not mapped yet; "
                + "use the provider door until the mapping is live-receipted", ErrorMeta.of(cx.provider()));
        }
        JsonBuilder payload = new JsonBuilder().put("model", cx.model()).put("prompt", request.prompt());
        if (request.extensions() != null) payload.putAll(request.extensions());
        if (request.seconds() != null) payload.put("seconds", Integer.toString(request.seconds()));
        WireRequest w = WireRequest.post("/videos", payload.build());
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 120.0);
    }

    static VideoJobInfo videoJobInfo(BuildContext cx, JsonObject data) {
        JsonValue idv = data.get("id");
        if (!(idv instanceof JsonString ids) || ids.value().isEmpty()) throw new ProviderError("openai: video object carries no id", ErrorMeta.of(cx.provider()));
        String wire = Js.str(data, "status");
        VideoStatus status = videoStatus(wire);
        if (status == null) throw new ProviderError("openai: unknown video status '" + wire + "'", ErrorMeta.of(cx.provider()));
        JsonValue p = data.get("progress");
        Integer progress = p instanceof JsonInt i ? i.value().intValueExact() : p instanceof JsonFloat f ? (int) f.value() : null;
        JsonValue m = data.get("model");
        return new VideoJobInfo(ids.value(), status, progress, Common.isoUtc(data.get("created_at")), m instanceof JsonString ms ? ms.value() : null, data);
    }

    static WireRequest videoStatusRequest(BuildContext cx, String videoId) {
        WireRequest w = WireRequest.get("/videos/" + pathId(videoId));
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 60.0);
    }

    static WireRequest videoResultFetch(BuildContext cx, JsonObject statusBody) {
        WireRequest w = WireRequest.get("/videos/" + pathId(Js.pyStr(statusBody.get("id"))) + "/content");
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 600.0);
    }

    static VideoPart videoPart(BuildContext cx, JsonObject statusBody, HttpResponse fetched) {
        if (fetched == null) throw new ProviderError("openai: video content fetch is required", ErrorMeta.of(cx.provider()));
        String contentType = mediaType(fetched.header("content-type"));
        if (contentType.isEmpty()) throw new ProviderError("openai: video content carries no content-type", ErrorMeta.of(cx.provider()));
        return new VideoPart(contentType, Base64.getEncoder().encodeToString(fetched.body()), null, null, null, List.of());
    }

    static WireRequest videoList(BuildContext cx, int limit, String model) {
        WireRequest w = WireRequest.get("/videos").param("limit", Integer.toString(limit));
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 60.0);
    }

    static List<VideoJobInfo> videoJobs(BuildContext cx, String body) {
        JsonValue data = Json.parse(body);
        List<VideoJobInfo> out = new ArrayList<>();
        if (data instanceof JsonObject o) for (JsonValue item : Js.arr(o, "data")) if (item instanceof JsonObject j) out.add(videoJobInfo(cx, j));
        return out;
    }

    private static String mediaType(String contentType) {
        if (contentType == null) return "";
        int semi = contentType.indexOf(';');
        return (semi >= 0 ? contentType.substring(0, semi) : contentType).strip();
    }

    // ─── image / speech generation ───

    static WireRequest imageGenerate(BuildContext cx, ImageGenerationRequest request) {
        OpenAIResponsesCompat.Resolved compat = ResponsesRequest.compat(cx, null);
        if (request.images().isEmpty()) {
            JsonBuilder payload = new JsonBuilder().put("model", cx.model()).put("prompt", request.prompt());
            if (request.size() != null) payload.put("size", request.size());
            if (request.extensions() != null) {
                for (Map.Entry<String, JsonValue> e : request.extensions().members().entrySet()) {
                    if (!(e.getValue() instanceof JsonNull)) payload.put(e.getKey(), e.getValue());
                }
            }
            WireRequest w = WireRequest.post("/images/generations", payload.build());
            OpenAIResponsesDialect.addHeaders(w, cx);
            return timed(w, 300.0);
        }
        // Edits are multipart: the wire takes uploaded bytes only.
        for (ImagePart part : request.images()) {
            if (part.data() == null && part.path() == null) {
                throw new UnsupportedFeatureError("openai: image edits take inline data or a local path; url/file_id-addressed input images have no wire slot", ErrorMeta.of(cx.provider()));
            }
        }
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        fields.add(Map.entry("model", cx.model()));
        fields.add(Map.entry("prompt", request.prompt()));
        if (request.size() != null) fields.add(Map.entry("size", request.size()));
        if (request.extensions() != null) {
            for (Map.Entry<String, JsonValue> e : request.extensions().members().entrySet()) fields.add(Map.entry(e.getKey(), Js.pyStr(e.getValue())));
        }
        List<Common.FilePart> files = new ArrayList<>();
        for (int i = 0; i < request.images().size(); i++) {
            ImagePart part = request.images().get(i);
            String field = compat.editImageField().equals("indexed") ? "image[" + i + "]" : "image[]";
            files.add(new Common.FilePart(field, "image-" + i, part.mediaType(), part.bytes()));
        }
        Map.Entry<String, byte[]> body = Common.multipartFormBody(fields, files);
        WireRequest w = WireRequest.raw("POST", "/images/edits", body.getKey(), body.getValue());
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 300.0);
    }

    static ImageGenerationResponse imageGeneration(BuildContext cx, ImageGenerationRequest request, HttpResponse resp) {
        JsonObject data = resp.json().asObject();
        JsonValue fmt = data.get("output_format");
        String mediaType = fmt instanceof JsonString f && !f.value().isEmpty() ? "image/" + f.value() : "application/octet-stream";
        List<ImagePart> images = new ArrayList<>();
        for (JsonValue item : Js.arr(data, "data")) {
            if (!(item instanceof JsonObject o)) continue;
            if (Js.truthy(o.get("b64_json"))) images.add(new ImagePart(mediaType, Js.pyStr(o.get("b64_json")), null, null, null, null, List.of()));
            else if (Js.truthy(o.get("url"))) images.add(new ImagePart(mediaType, null, Js.pyStr(o.get("url")), null, null, null, List.of()));
        }
        JsonObject u = Js.obj(data, "usage");
        Usage usage = new Usage(Js.intOrNull(u, "input_tokens"), Js.intOrNull(u, "output_tokens"), Js.intOrNull(u, "total_tokens"), null, null, null, null, null);
        return new ImageGenerationResponse(images, null, null, null, usage, data);
    }

    static WireRequest speechGenerate(BuildContext cx, SpeechGenerationRequest request) {
        JsonBuilder payload = new JsonBuilder().put("model", cx.model()).put("input", request.prompt());
        if (request.extensions() != null) payload.putAll(request.extensions());
        if (request.voice() != null) payload.put("voice", request.voice());
        if (request.format() != null) payload.put("response_format", request.format());
        WireRequest w = WireRequest.post("/audio/speech", payload.build());
        OpenAIResponsesDialect.addHeaders(w, cx);
        return timed(w, 300.0);
    }

    static SpeechGenerationResponse speechGeneration(BuildContext cx, SpeechGenerationRequest request, HttpResponse resp) {
        String contentType = mediaType(resp.header("content-type"));
        if (contentType.isEmpty()) throw new ProviderError("openai: speech response carries no content-type", ErrorMeta.of(cx.provider()));
        AudioPart audio = new AudioPart(contentType, Base64.getEncoder().encodeToString(resp.body()), null, null, null, List.of());
        return new SpeechGenerationResponse(audio, null, null, null, Json.obj("content_type", contentType));
    }

}
