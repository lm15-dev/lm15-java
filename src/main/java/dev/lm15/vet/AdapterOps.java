package dev.lm15.vet;

import dev.lm15.ProviderLM;
import dev.lm15.auth.Credential;
import dev.lm15.auth.Rfc3339;
import dev.lm15.errors.StreamAssemblyError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.live.LiveCodec;
import dev.lm15.registry.ProviderDefinition;
import dev.lm15.registry.Registry;
import dev.lm15.serde.Canonical;
import dev.lm15.stream.Streams;
import dev.lm15.transport.HttpResponse;
import dev.lm15.types.*;
import dev.lm15.wire.Clock;
import dev.lm15.wire.TransportRequest;
import dev.lm15.wire.Wire;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The adapter-driven ops: each constructs the adapter a case names and calls the same pure hooks users call. */
final class AdapterOps {
    private AdapterOps() {}

    /** Parse-only ops construct an adapter but never build auth headers; the key must never come from the environment. */
    static final String PARSE_ONLY_KEY = "vet-parse-only";

    static void register(Map<String, Main.Op> h) {
        h.put("build_request", AdapterOps::buildRequest);
        h.put("ingest_openai_chat", AdapterOps::ingestOpenAIChat);
        h.put("parse_response", AdapterOps::parseResponse);
        h.put("replay_stream", AdapterOps::replayStream);
        h.put("normalize_error", AdapterOps::normalizeError);
        h.put("build_models_request", AdapterOps::buildModelsRequest);
        h.put("parse_models_response", AdapterOps::parseModelsResponse);
        h.put("generation_build", AdapterOps::generationBuild);
        h.put("generation_parse", AdapterOps::generationParse);
        h.put("file_op_build", AdapterOps::fileOpBuild);
        h.put("file_op_parse", AdapterOps::fileOpParse);
        h.put("cache_op_build", AdapterOps::cacheOpBuild);
        h.put("cache_op_parse", AdapterOps::cacheOpParse);
        h.put("video_op_build", AdapterOps::videoOpBuild);
        h.put("video_op_parse", AdapterOps::videoOpParse);
        h.put("batch_op_build", AdapterOps::batchOpBuild);
        h.put("batch_op_parse", AdapterOps::batchOpParse);
        h.put("replay_live", AdapterOps::replayLive);
    }

    // ─── adapters ───

    static Credential credentialOf(JsonObject msg) {
        JsonValue cred = msg.get("credential");
        if (cred instanceof JsonObject o) return Canonical.credentialFromJson(o);
        return new Credential.ApiKey(msg.get("api_key").asString());
    }

    static Clock clockOf(JsonObject msg) {
        JsonValue now = msg.opt("now");
        return now == null ? Clock.SYSTEM : Clock.fixed(Rfc3339.parse(now.asString()));
    }

    static Map<String, String> settingsOf(JsonObject msg) {
        JsonValue s = msg.opt("settings");
        if (!(s instanceof JsonObject o)) return null;
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> e : o.members().entrySet()) out.put(e.getKey(), e.getValue().isString() ? e.getValue().asString() : e.getValue().toJson());
        return out;
    }

    static String baseUrlOf(JsonObject msg) {
        JsonValue b = msg.opt("base_url");
        return b == null ? null : b.asString();
    }

    /** The LM a case's provider string names, from the registry; base_url overrides the provider's default. */
    static ProviderLM adapterFor(String provider, Credential credential, String baseUrl, Map<String, String> settings, Clock clock) {
        ProviderDefinition definition = Registry.lookup(provider);
        if (definition == null) throw ValidationException.value("unknown provider: " + provider);
        ProviderLM.Builder b = ProviderLM.builder(definition).credential(credential).clock(clock).transport(NoNetwork.INSTANCE);
        if (baseUrl != null) b.baseUrl(baseUrl);
        if (definition.hosted() && settings != null) b.settings(settings);
        if (definition.id().equals("openai-codex")) b.accountId("test-account");
        return b.build();
    }

    static ProviderLM adapter(JsonObject msg) {
        return adapterFor(msg.get("provider").asString(), credentialOf(msg), baseUrlOf(msg), settingsOf(msg), clockOf(msg));
    }

    static ProviderLM parseAdapter(JsonObject msg) {
        return adapterFor(msg.get("provider").asString(), new Credential.ApiKey(PARSE_ONLY_KEY), baseUrlOf(msg), settingsOf(msg), clockOf(msg));
    }

    static byte[] bodyBytes(JsonObject msg) { return Base64.getDecoder().decode(msg.get("body_b64").asString()); }
    static String bodyText(JsonObject msg) { return new String(bodyBytes(msg), StandardCharsets.UTF_8); }
    static int status(JsonObject msg) { return msg.get("status").asInt(); }

    static List<Map.Entry<String, String>> headersOf(JsonObject msg) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        JsonValue h = msg.opt("headers");
        if (h instanceof JsonObject o) for (Map.Entry<String, JsonValue> e : o.members().entrySet()) out.add(Map.entry(e.getKey(), e.getValue().asString()));
        return out;
    }

    static JsonObject responseResult(Response response) {
        JsonBuilder out = new JsonBuilder().put("canonical_response", Canonical.toJson(response));
        if (response.providerData() != null) {
            JsonValue unmapped = response.providerData().get("_lm15_unmapped");
            if (unmapped != null) out.put("unmapped", unmapped);
        }
        return out.build();
    }

    // ─── ops ───

    static JsonObject buildRequest(JsonObject msg) {
        ProviderLM lm = adapter(msg);
        Request request = Canonical.requestFromJson(msg.get("canonical_request").asObject());
        boolean stream = msg.opt("stream") != null && msg.get("stream").asBool();
        return Wire.normalize(lm.buildRequest(request, stream));
    }

    static JsonObject ingestOpenAIChat(JsonObject msg) {
        ProviderLM lm = parseAdapter(msg);
        return Json.obj("canonical_request", Canonical.toJson(lm.requestFromOpenAIChat(msg.get("body").asObject())));
    }

    static JsonObject parseResponse(JsonObject msg) {
        ProviderLM lm = parseAdapter(msg);
        Request request = Canonical.requestFromJson(msg.get("canonical_request").asObject());
        return responseResult(lm.parseResponse(request, HttpResponse.json(status(msg), bodyBytes(msg))));
    }

    static JsonObject replayStream(JsonObject msg) {
        ProviderLM lm = parseAdapter(msg);
        Request request = Canonical.requestFromJson(msg.get("canonical_request").asObject());
        List<StreamEvent> events = lm.replayStream(request, bodyBytes(msg));
        List<JsonValue> eventJson = new ArrayList<>();
        for (StreamEvent e : events) eventJson.add(Canonical.toJson(e));
        Response response;
        try {
            response = Streams.materialize(events, request);
        } catch (StreamAssemblyError exc) {
            throw new Main.OpFailure(exc, Json.obj("events", new JsonArray(eventJson)));
        }
        return new JsonBuilder().put("events", new JsonArray(eventJson)).putAll(responseResult(response)).build();
    }

    static JsonObject normalizeError(JsonObject msg) {
        ProviderLM lm = parseAdapter(msg);
        var err = lm.normalizeError(status(msg), msg.get("body_text").asString());
        return new JsonBuilder().put("class", err.className()).put("code", err.code().wire()).put("provider_code", err.providerCode()).put("message", err.message()).build();
    }

    static JsonObject buildModelsRequest(JsonObject msg) {
        return Wire.normalize(adapter(msg).modelsRequest());
    }

    static JsonObject parseModelsResponse(JsonObject msg) {
        ProviderLM lm = parseAdapter(msg);
        int status = status(msg);
        String body = bodyText(msg);
        if (status >= 400) throw lm.normalizeError(status, body);
        List<JsonValue> models = new ArrayList<>();
        for (ModelInfo m : lm.parseModels(body)) models.add(Canonical.toJson(m));
        return Json.obj("models", new JsonArray(models));
    }

    static JsonObject generationBuild(JsonObject msg) {
        ProviderLM lm = adapter(msg);
        String kind = msg.get("kind").asString();
        JsonObject req = msg.get("generation_request").asObject();
        return switch (kind) {
            case "image" -> Wire.normalize(lm.imageGenerateRequest(Canonical.imageGenerationRequestFromJson(req)));
            case "speech" -> Wire.normalize(lm.speechGenerateRequest(Canonical.speechGenerationRequestFromJson(req)));
            default -> throw ValidationException.value("unknown generation kind: " + kind);
        };
    }

    static JsonObject generationParse(JsonObject msg) {
        ProviderLM lm = parseAdapter(msg);
        String kind = msg.get("kind").asString();
        int status = status(msg);
        byte[] body = bodyBytes(msg);
        if (status >= 400) throw lm.normalizeError(status, new String(body, StandardCharsets.UTF_8));
        HttpResponse resp = new HttpResponse(status, headersOf(msg), body);
        JsonObject req = msg.get("generation_request").asObject();
        return switch (kind) {
            case "image" -> Canonical.toJson(lm.parseImageGeneration(Canonical.imageGenerationRequestFromJson(req), resp));
            case "speech" -> Canonical.toJson(lm.parseSpeechGeneration(Canonical.speechGenerationRequestFromJson(req), resp));
            default -> throw ValidationException.value("unknown generation kind: " + kind);
        };
    }

    static JsonObject fileOpBuild(JsonObject msg) {
        ProviderLM lm = adapter(msg);
        String op = msg.get("file_op").asString();
        return switch (op) {
            case "upload" -> Wire.normalize(lm.fileUploadRequest(Canonical.fileUploadRequestFromJson(msg.get("upload_request").asObject())));
            case "get" -> Wire.normalize(lm.fileGetRequest(msg.get("file_id").asString()));
            case "list" -> Wire.normalize(lm.fileListRequest(msg.opt("limit") == null ? 20 : msg.get("limit").asInt(), msg.opt("cursor") == null ? null : msg.get("cursor").asString()));
            case "delete" -> Wire.normalize(lm.fileDeleteRequest(msg.get("file_id").asString()));
            case "download" -> Wire.normalize(lm.fileDownloadRequest(msg.get("file_id").asString()));
            default -> throw ValidationException.value("unknown file_op: " + op);
        };
    }

    static JsonObject fileOpParse(JsonObject msg) {
        ProviderLM lm = parseAdapter(msg);
        String kind = msg.get("kind").asString();
        int status = status(msg);
        String body = bodyText(msg);
        if (status >= 400) throw lm.normalizeError(status, body);
        return switch (kind) {
            case "info" -> Json.obj("file", Canonical.toJson(lm.parseFileInfo(body)));
            case "page" -> Json.obj("page", Canonical.toJson(lm.parseFilePage(body)));
            default -> throw ValidationException.value("unknown file parse kind: " + kind);
        };
    }

    static JsonObject cacheOpBuild(JsonObject msg) {
        ProviderLM lm = adapter(msg);
        String op = msg.get("cache_op").asString();
        Integer ttl = msg.opt("ttl_seconds") == null ? null : msg.get("ttl_seconds").asInt();
        return switch (op) {
            case "create" -> Wire.normalize(lm.cacheCreateRequest(Canonical.requestFromJson(msg.get("prefix_request").asObject()), ttl, msg.opt("label") == null ? null : msg.get("label").asString()));
            case "get" -> Wire.normalize(lm.cacheGetRequest(msg.get("cache_id").asString()));
            case "list" -> Wire.normalize(lm.cacheListRequest(msg.opt("limit") == null ? 20 : msg.get("limit").asInt(), msg.opt("cursor") == null ? null : msg.get("cursor").asString()));
            case "delete" -> Wire.normalize(lm.cacheDeleteRequest(msg.get("cache_id").asString()));
            case "update" -> Wire.normalize(lm.cacheUpdateRequest(msg.get("cache_id").asString(), msg.get("ttl_seconds").asInt()));
            default -> throw ValidationException.value("unknown cache_op: " + op);
        };
    }

    static JsonObject cacheOpParse(JsonObject msg) {
        ProviderLM lm = parseAdapter(msg);
        String kind = msg.get("kind").asString();
        int status = status(msg);
        String body = bodyText(msg);
        if (status >= 400) throw lm.normalizeError(status, body);
        return switch (kind) {
            case "info" -> Json.obj("cache", Canonical.toJson(lm.parseCacheInfo(body)));
            case "page" -> Json.obj("page", Canonical.toJson(lm.parseCachePage(body)));
            default -> throw ValidationException.value("unknown cache parse kind: " + kind);
        };
    }

    private static JsonObject requests(List<TransportRequest> reqs) {
        List<JsonValue> out = new ArrayList<>();
        for (TransportRequest r : reqs) out.add(Wire.normalize(r));
        return Json.obj("requests", new JsonArray(out));
    }

    static JsonObject videoOpBuild(JsonObject msg) {
        ProviderLM lm = adapter(msg);
        String action = msg.get("action").asString();
        return switch (action) {
            case "submit" -> requests(List.of(lm.videoSubmitRequest(Canonical.videoGenerationRequestFromJson(msg.get("video_request").asObject()))));
            case "status" -> requests(List.of(lm.videoStatusRequest(msg.get("video_id").asString())));
            case "result_fetch" -> {
                TransportRequest fetch = lm.videoResultFetch(msg.get("status_body").asObject());
                yield requests(fetch == null ? List.of() : List.of(fetch));
            }
            case "list" -> requests(List.of(lm.videoListRequest(msg.opt("limit") == null ? 20 : msg.get("limit").asInt(), msg.opt("model") == null ? null : msg.get("model").asString())));
            default -> throw ValidationException.value("unknown video action: " + action);
        };
    }

    static JsonObject videoOpParse(JsonObject msg) {
        ProviderLM lm = parseAdapter(msg);
        String kind = msg.get("kind").asString();
        switch (kind) {
            case "job": {
                int status = status(msg);
                String body = bodyText(msg);
                if (status >= 400) throw lm.normalizeError(status, body);
                return Json.obj("job", Canonical.toJson(lm.parseVideoJob(body, msg.opt("video_id") == null ? null : msg.get("video_id").asString())));
            }
            case "list": {
                List<JsonValue> jobs = new ArrayList<>();
                for (VideoJobInfo j : lm.parseVideoJobs(bodyText(msg))) jobs.add(Canonical.toJson(j));
                return Json.obj("jobs", new JsonArray(jobs));
            }
            case "part": {
                HttpResponse fetched = null;
                if (msg.opt("fetched_b64") != null) fetched = new HttpResponse(200, headersOf(msg), Base64.getDecoder().decode(msg.get("fetched_b64").asString()));
                return Json.obj("part", Canonical.toJson(lm.parseVideoPart(msg.get("status_body").asObject(), fetched)));
            }
            default: throw ValidationException.value("unknown video parse kind: " + kind);
        }
    }

    static JsonObject batchOpBuild(JsonObject msg) {
        ProviderLM lm = adapter(msg);
        String action = msg.get("action").asString();
        switch (action) {
            case "upload": {
                TransportRequest upload = lm.batchUploadRequest(Canonical.batchRequestFromJson(msg.get("batch_request").asObject()));
                return requests(upload == null ? List.of() : List.of(upload));
            }
            case "submit": {
                JsonValue uploadBody = msg.opt("upload_body");
                return requests(List.of(lm.batchSubmitRequest(Canonical.batchRequestFromJson(msg.get("batch_request").asObject()), uploadBody instanceof JsonObject o ? o : null)));
            }
            case "status": return requests(List.of(lm.batchStatusRequest(msg.get("batch_id").asString())));
            case "cancel": return requests(List.of(lm.batchCancelRequest(msg.get("batch_id").asString())));
            case "list": return requests(List.of(lm.batchListRequest(msg.opt("limit") == null ? 20 : msg.get("limit").asInt())));
            case "result_fetches": return requests(lm.batchResultFetches(msg.get("status_body").asObject()));
            default: throw ValidationException.value("unknown batch action: " + action);
        }
    }

    static JsonObject batchOpParse(JsonObject msg) {
        ProviderLM lm = parseAdapter(msg);
        String kind = msg.get("kind").asString();
        switch (kind) {
            case "job": {
                int status = status(msg);
                String body = bodyText(msg);
                if (status >= 400) throw lm.normalizeError(status, body);
                return Json.obj("job", Canonical.toJson(lm.parseBatchJob(body)));
            }
            case "list": {
                List<JsonValue> jobs = new ArrayList<>();
                for (BatchJobInfo j : lm.parseBatchJobs(bodyText(msg))) jobs.add(Canonical.toJson(j));
                return Json.obj("jobs", new JsonArray(jobs));
            }
            case "entries": {
                List<String> fetched = new ArrayList<>();
                JsonValue f = msg.opt("fetched_b64");
                if (f instanceof JsonArray a) for (JsonValue b : a) fetched.add(new String(Base64.getDecoder().decode(b.asString()), StandardCharsets.UTF_8));
                List<JsonValue> entries = new ArrayList<>();
                for (BatchEntry e : lm.parseBatchEntries(msg.get("status_body").asObject(), fetched)) entries.add(Canonical.toJson(e));
                return Json.obj("entries", new JsonArray(entries));
            }
            default: throw ValidationException.value("unknown batch parse kind: " + kind);
        }
    }

    static JsonObject replayLive(JsonObject msg) {
        ProviderLM lm = parseAdapter(msg);
        LiveConfig config = Canonical.liveConfigFromJson(msg.get("live_config").asObject());
        LiveCodec codec = lm.liveCodec(config);
        List<JsonValue> clientFrames = new ArrayList<>();
        JsonValue clientEvents = msg.opt("client_events");
        if (clientEvents instanceof JsonArray a) {
            for (JsonValue e : a) clientFrames.add(new JsonArray(codec.encode(Canonical.liveClientEventFromJson(e.asObject()))));
        }
        List<JsonValue> events = new ArrayList<>();
        JsonValue frames = msg.opt("server_frames_b64");
        if (frames instanceof JsonArray a) {
            for (JsonValue f : a) {
                List<JsonValue> decoded = new ArrayList<>();
                for (LiveServerEvent e : codec.decode(Base64.getDecoder().decode(f.asString()))) decoded.add(Canonical.toJson(e));
                events.add(new JsonArray(decoded));
            }
        }
        return Json.obj("setup_frames", new JsonArray(codec.setupFrames()), "client_frames", new JsonArray(clientFrames), "events", new JsonArray(events));
    }

    /** The shim never touches the network (PROTOCOL.md): any send is a hard failure. */
    static final class NoNetwork implements dev.lm15.transport.Transport {
        static final NoNetwork INSTANCE = new NoNetwork();

        @Override public HttpResponse send(TransportRequest request) { throw new IllegalStateException("the vet shim must not touch the network"); }

        @Override public Streaming stream(TransportRequest request) { throw new IllegalStateException("the vet shim must not touch the network"); }
    }
}
