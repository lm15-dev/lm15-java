package dev.lm15.dialects.openaichat;

import dev.lm15.dialects.Common;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.LM15Error;
import dev.lm15.errors.ProviderError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonFloat;
import dev.lm15.json.JsonInt;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.transport.HttpResponse;
import dev.lm15.types.*;
import dev.lm15.wire.BuildContext;
import dev.lm15.wire.Wire;
import dev.lm15.wire.WireRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * xAI Grok (the reference's {@code lm15/providers/xai.py}): the Chat
 * Completions dialect under the {@code xai} preset, plus xAI's own image
 * generation/edit and video job surfaces and its refusals (reasoning off,
 * logprobs, the MAP-8 cells). Only the credential path is composed by the
 * access policy.
 */
public class XaiDialect extends OpenAIChatDialect {

    private static final Map<String, VideoStatus> VIDEO_STATUS_MAP = Map.of(
        "pending", VideoStatus.RUNNING,
        "done", VideoStatus.COMPLETED,
        "failed", VideoStatus.FAILED);

    public XaiDialect() {}

    @Override protected JsonObject payload(Request request, boolean stream, BuildContext cx) {
        Config config = request.config();
        // Grok reasoning models have no off switch; api.x.ai accepts thinking={"type": "disabled"} and ignores it (live 2026-09-01: 158 reasoning tokens).
        if (config.reasoning() != null && config.reasoning().isOff()) {
            throw unsupported(cx, "reasoning cannot be disabled — Grok reasoning models have no off switch, and xAI silently ignores disable fields on the wire. "
                + "Omit the reasoning config, or pick a non-reasoning Grok model.");
        }
        // docs.x.ai: logprobs/top_logprobs are silently ignored by grok-4.20 and newer (verified live 2026-09-01).
        if (config.logprobs() != null) {
            throw unsupported(cx, "config.logprobs is not supported — grok-4.20 and newer silently ignore logprobs/top_logprobs on the wire "
                + "(docs.x.ai, verified live 2026-09-01). OpenAI and Gemini carry logprobs.");
        }
        ToolChoice tc = config.toolChoice();
        if (tc != null && !tc.allowed().isEmpty() && !(tc.allowed().size() == 1 && tc.mode() == ToolChoiceMode.REQUIRED)) {
            // MAP-8 rule 1 (live 2026-09-02): api.x.ai accepts allowed_tools and ignores it; the forced single-function form held.
            throw unsupported(cx, "tool_choice.allowed subsets are silently ignored by api.x.ai (verified live 2026-09-02); force a single tool with mode='required', "
                + "or send only the allowed tools in Request.tools");
        }
        if (tc != null && tc.mode() == ToolChoiceMode.REQUIRED && config.responseFormat() != null) {
            // MAP-8 rule 3 (live 2026-09-02): a forced tool next to a response_format returned JSON text and no call.
            throw unsupported(cx, "a forced tool (mode='required') cannot be combined with response_format — api.x.ai returns JSON text and drops the call (verified live 2026-09-02)");
        }
        return super.payload(request, stream, cx);
    }

    // ─── media generation (captured live 2026-09-01) ───
    //
    // /images/generations for text-to-image; /images/edits for image-to-image. `generations` silently IGNORES input
    // images (pixel-verified), so edits must never route there. The edit input is image:{url|file_id}; exactly one.

    private static JsonObject imageInput(ImagePart part, BuildContext cx) {
        if (part.url() != null) return Json.obj("url", part.url());
        if (part.fileId() != null) return Json.obj("file_id", part.fileId());
        if (part.data() != null || part.path() != null) return Json.obj("url", Common.mediaDataUri(part));
        throw unsupported(cx, "input image carries no content");
    }

    @Override public WireRequest imageGenerateRequest(BuildContext cx, ImageGenerationRequest request) {
        JsonBuilder payload = new JsonBuilder().put("model", request.model()).put("prompt", request.prompt());
        if (request.extensions() != null) payload.putAll(request.extensions());
        if (request.size() != null) {
            // No wire slot: xAI sizes through quality/resolution knobs with their own names (extensions). Raising beats guessing a mapping.
            throw unsupported(cx, "size has no wire slot; use extensions for xAI's quality/resolution fields");
        }
        if (request.images().isEmpty()) {
            WireRequest w = WireRequest.post("/images/generations", payload.build());
            headers(w, cx);
            w.readTimeout = Duration.ofSeconds(300);
            return w;
        }
        if (request.images().size() > 1) throw unsupported(cx, "image edits take exactly one input image; the wire has no slot for more");
        payload.put("image", imageInput(request.images().get(0), cx));
        WireRequest w = WireRequest.post("/images/edits", payload.build());
        headers(w, cx);
        w.readTimeout = Duration.ofSeconds(300);
        return w;
    }

    @Override public ImageGenerationResponse imageGeneration(BuildContext cx, ImageGenerationRequest request, HttpResponse response) {
        JsonObject data = response.json().asObject();
        List<ImagePart> images = new ArrayList<>();
        if (data.get("data") instanceof JsonArray items) {
            for (JsonValue itemV : items) {
                if (!(itemV instanceof JsonObject item)) continue;
                String mediaType = item.get("mime_type") instanceof JsonString m && !m.value().isEmpty() ? m.value() : "application/octet-stream";
                if (ChatErrors.truthy(item.get("b64_json"))) {
                    images.add(new ImagePart(mediaType, ChatErrors.pyStr(item.get("b64_json")), null, null, null, null, List.of()));
                } else if (ChatErrors.truthy(item.get("url"))) {
                    images.add(new ImagePart(mediaType, null, ChatErrors.pyStr(item.get("url")), null, null, null, List.of()));
                }
            }
        }
        if (images.isEmpty()) throw new ProviderError("xai: image response carries no images", ErrorMeta.of(cx.provider()));
        // usage reports cost_in_usd_ticks only — no token counts exist, so Usage stays empty and the figure lives in provider_data.
        return new ImageGenerationResponse(images, null, null, null, Usage.EMPTY, data);
    }

    // ─── video generation (grok-imagine; captured live 2026-09-01) ───
    //
    // POST /videos/generations -> {"request_id"}; GET /videos/{id} -> pending + progress %, then done + a PUBLIC MP4 URL,
    // so the result is URL-addressed with no fetch step. There is NO list endpoint (probed: 404).

    @Override public WireRequest videoSubmitRequest(BuildContext cx, VideoGenerationRequest request) {
        if (request.seconds() != null) throw unsupported(cx, "video duration has no wire slot");
        if (!request.images().isEmpty()) {
            // The image-generation wire silently IGNORES unknown fields (pixel-verified 2026-09-01); raise until the field is live-receipted.
            throw unsupported(cx, "video input images are not mapped yet; use extensions until the mapping is live-receipted");
        }
        JsonBuilder payload = new JsonBuilder().put("model", request.model()).put("prompt", request.prompt());
        if (request.extensions() != null) payload.putAll(request.extensions());
        WireRequest w = WireRequest.post("/videos/generations", payload.build());
        headers(w, cx);
        w.readTimeout = Duration.ofSeconds(120);
        return w;
    }

    @Override public VideoJobInfo videoJob(BuildContext cx, String body, String videoId) {
        JsonObject data = Json.parse(body).asObject();
        if (data.get("request_id") instanceof JsonString id && !id.value().isEmpty()) {
            // The submit acknowledgement: a bare ticket, not yet started.
            return new VideoJobInfo(id.value(), VideoStatus.QUEUED, null, null, null, data);
        }
        if (videoId == null) throw new ProviderError("xai: video body carries no request_id", ErrorMeta.of(cx.provider()));
        String wireStatus = ChatErrors.truthy(data.get("status")) ? ChatErrors.pyStr(data.get("status")) : "";
        VideoStatus status = VIDEO_STATUS_MAP.get(wireStatus);
        if (status == null) throw new ProviderError("xai: unknown video status '" + wireStatus + "'", ErrorMeta.of(cx.provider()));
        JsonValue progressV = data.get("progress");
        Integer progress = null;
        if (progressV instanceof JsonInt i) progress = i.value().intValueExact();
        else if (progressV instanceof JsonFloat f) progress = (int) f.value();
        String model = data.get("model") instanceof JsonString m ? m.value() : null;
        return new VideoJobInfo(videoId, status, progress, null, model, data);
    }

    @Override public WireRequest videoStatusRequest(BuildContext cx, String videoId) {
        WireRequest w = WireRequest.get("/videos/" + Wire.pathId(videoId, false));
        headers(w, cx);
        w.readTimeout = Duration.ofSeconds(60);
        return w;
    }

    @Override public WireRequest videoListRequest(BuildContext cx, int limit, String model) {
        throw unsupported(cx, "the wire has no video list endpoint (probed 2026-09-01: 404) — the ticket you stored is the only copy");
    }

    @Override public WireRequest videoResultFetch(BuildContext cx, JsonObject statusBody) {
        return null; // the terminal body carries a public URL
    }

    @Override public VideoPart videoPart(BuildContext cx, JsonObject statusBody, HttpResponse fetched) {
        JsonObject video = statusBody.get("video") instanceof JsonObject v ? v : JsonObject.EMPTY;
        if (!(video.get("url") instanceof JsonString url) || url.value().isEmpty()) {
            throw new ProviderError("xai: terminal video carries no url", ErrorMeta.of(cx.provider()));
        }
        return new VideoPart("video/mp4", null, url.value(), null, null, List.of());
    }

    // ─── errors ───

    @Override public LM15Error normalizeError(BuildContext cx, int status, String body) {
        // xAI's own envelope is {"code": str, "error": str} (captured 2026-09-01) — refold it into the OpenAI shape so the
        // shared mapping preserves the wire code as provider_code instead of dropping it.
        try {
            JsonValue data = Json.parse(body);
            if (data instanceof JsonObject o && o.get("error") instanceof JsonString err) {
                body = Json.write(Json.obj("error", Json.obj("message", err.value(), "code", o.has("code") ? o.get("code") : null)));
            }
        } catch (RuntimeException ignored) {
            // not JSON: the shared mapping handles the raw body
        }
        return super.normalizeError(cx, status, body);
    }
}
