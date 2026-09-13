package dev.lm15;

import dev.lm15.auth.Access;
import dev.lm15.auth.AccessPolicy;
import dev.lm15.auth.Credential;
import dev.lm15.auth.CredentialPolicy;
import dev.lm15.auth.CredentialProvider;
import dev.lm15.auth.AuthChain;
import dev.lm15.auth.CodexStore;
import dev.lm15.cloud.ChainContext;
import dev.lm15.cloud.Hosts;
import dev.lm15.compat.Compat;
import dev.lm15.dialects.Dialect;
import dev.lm15.dialects.Dialects;
import dev.lm15.errors.AuthError;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.Errors;
import dev.lm15.errors.LM15Error;
import dev.lm15.errors.NotConfiguredError;
import dev.lm15.errors.TransportError;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.jobs.BatchJob;
import dev.lm15.jobs.VideoJob;
import dev.lm15.json.Json;
import dev.lm15.json.JsonObject;
import dev.lm15.live.LiveCodec;
import dev.lm15.live.LiveSession;
import dev.lm15.registry.ProviderDefinition;
import dev.lm15.registry.Registry;
import dev.lm15.sse.Sse;
import dev.lm15.sse.SseEvent;
import dev.lm15.stream.Coalescer;
import dev.lm15.stream.ResponseStream;
import dev.lm15.stream.Streams;
import dev.lm15.transport.HttpResponse;
import dev.lm15.transport.HttpTransport;
import dev.lm15.transport.Transport;
import dev.lm15.types.*;
import dev.lm15.wire.BuildContext;
import dev.lm15.wire.Clock;
import dev.lm15.wire.TransportRequest;
import dev.lm15.wire.Wire;
import dev.lm15.wire.WireRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * A provider adapter: a dialect bound to an access policy, a compat value,
 * a credential provider, a base URL, host settings and a clock (AUTH-10).
 * The named constructors ({@code OpenAILM}, {@code AnthropicLM}, …) are
 * builders over this one class; the router builds the same thing.
 *
 * <p>The pure hooks ({@link #buildRequest}, {@link #parseResponse},
 * {@link #replayStream}, the surface {@code *Request} / {@code parse*}
 * pairs) are what the contract pins; the drivers ({@link #complete},
 * {@link #stream}, {@link #fileUpload}, …) send them.
 */
public final class ProviderLM implements AutoCloseable {
    private final ProviderDefinition definition;
    private final Dialect dialect;
    private final AccessPolicy policy;
    private final Compat compat;
    private final String baseUrl;
    private final Map<String, String> settings;
    private final CredentialProvider credentials;
    private final String credentialSource;
    private final String accountId;
    private final Clock clock;
    private final Transport transport;

    private ProviderLM(Builder b) {
        this.definition = b.definition;
        this.dialect = b.dialect;
        this.policy = b.policy;
        this.compat = b.compat;
        this.baseUrl = b.baseUrl;
        this.settings = Map.copyOf(b.settings);
        this.credentials = b.credentials;
        this.credentialSource = b.credentialSource;
        this.accountId = b.accountId;
        this.clock = b.clock;
        this.transport = b.transport;
    }

    public static Builder builder(ProviderDefinition definition) { return new Builder(definition); }

    public static Builder builder(String provider) { return new Builder(Registry.require(provider)); }

    // ─── identity ───

    public String provider() { return policy.provider(); }
    public ProviderDefinition definition() { return definition; }
    public Dialect dialect() { return dialect; }
    public AccessPolicy policy() { return policy; }
    public Compat compat() { return compat; }
    public String baseUrl() { return baseUrl; }
    public Map<String, String> settings() { return settings; }
    public Clock clock() { return clock; }
    public Transport transport() { return transport; }
    public String accountId() { return accountId; }

    /** The model string the dialect sends: a {@code provider:} prefix naming this binding is removed. */
    public String wireModel(String model) {
        int i = model.indexOf(':');
        if (i > 0 && i < model.length() - 1 && Registry.canonicalProvider(model.substring(0, i)).equals(provider())) return model.substring(i + 1);
        return model;
    }

    public BuildContext context(Request request) {
        return new BuildContext(provider(), policy, settings, compat, baseUrl, wireModel(request.model()), accountId);
    }

    public BuildContext context() {
        return new BuildContext(provider(), policy, settings, compat, baseUrl, "", accountId);
    }

    // ─── the emit path ───

    private Credential credential() {
        Credential value = credentials == null ? null : credentials.get();
        if (value == null) throw new NotConfiguredError(provider() + ": credential source returned no credential; no fallback", ErrorMeta.of(provider()));
        return value;
    }

    /** Finish a dialect-built request through the bound host and sign it (the reference's {@code _emit}). */
    public TransportRequest emit(WireRequest wire, boolean stream) {
        return Wire.emit(wire, context(), credential(), clock, dialect.apiKeyHeader(), stream);
    }

    // ─── chat core: pure hooks ───

    public TransportRequest buildRequest(Request request, boolean stream) {
        BuildContext cx = context(request);
        WireRequest wire = dialect.build(request, stream, cx);
        return Wire.emit(wire, cx, credential(), clock, dialect.apiKeyHeader(), stream);
    }

    public Response parseResponse(Request request, HttpResponse response) {
        return dialect.parseResponse(request, context(request), response);
    }

    /** One SSE frame as PRE-coalesce events. */
    public List<StreamEvent> parseStreamEvents(Request request, SseEvent event) {
        return dialect.parseStreamEvent(request, context(request), event);
    }

    /** A whole recorded SSE body as the POST-coalesce canonical event trace (MAP-3/4). */
    public List<StreamEvent> replayStream(Request request, byte[] body) {
        List<StreamEvent> raw = new ArrayList<>();
        BuildContext cx = context(request);
        for (SseEvent e : Sse.parse(body)) raw.addAll(dialect.parseStreamEvent(request, cx, e));
        return Coalescer.coalesce(raw, request.model());
    }

    public LM15Error normalizeError(int status, String body) {
        return withLoginHint(dialect.normalizeError(context(), status, body));
    }

    /** Normalize an HTTP failure without losing its request id and retry hint. */
    public LM15Error httpError(HttpResponse response) {
        LM15Error error = normalizeError(response.status(), response.text());
        Errors.attachMetadata(error, response.headers());
        return error;
    }

    private LM15Error withLoginHint(LM15Error error) {
        String hint = policy.loginHint();
        if (hint != null && error instanceof AuthError auth && (policy.credentialPolicy() == CredentialPolicy.OAUTH || "stored".equals(credentialSource))) {
            return auth.withCredentialHint(hint);
        }
        return error;
    }

    public Request requestFromOpenAIChat(JsonObject body) { return dialect.requestFromOpenAIChat(context(), body); }

    public Response responseFromOpenAIChat(JsonObject body, String model, Integer choice) { return dialect.responseFromOpenAIChat(context(), body, model, choice); }

    // ─── chat core: drivers ───

    private HttpResponse send(TransportRequest request) {
        return transport.send(request);
    }

    private HttpResponse sendOk(TransportRequest request) {
        HttpResponse resp = send(request);
        if (resp.status() >= 400) throw httpError(resp);
        return resp;
    }

    public Response complete(Request request) {
        require("complete");
        if ("chatgpt-codex".equals(policy.backend())) {
            try (ResponseStream response = responseStream(request)) { return response.response(); }
        }
        return parseResponse(request, sendOk(buildRequest(request, false)));
    }

    /** The coalesced canonical event stream (MAP-3/4); closing the iterator releases the connection. */
    public EventStream stream(Request request) {
        require("stream");
        TransportRequest req = buildRequest(request, true);
        Transport.Streaming open = transport.stream(req);
        if (open.status() >= 400) {
            try (open) {
                byte[] body = open.body().readNBytes(1024 * 1024);
                LM15Error error = normalizeError(open.status(), new String(body, java.nio.charset.StandardCharsets.UTF_8));
                Errors.attachMetadata(error, open.headers());
                throw error;
            } catch (java.net.SocketTimeoutException e) {
                throw new dev.lm15.errors.TimeoutError("response body read timed out");
            } catch (java.io.IOException e) {
                throw new TransportError("response body read failed");
            }
        }
        return new EventStream(request, context(request), open);
    }

    /** {@code stream} assembled: iterate for text, {@code response()} for the Response. */
    public ResponseStream responseStream(Request request) {
        EventStream events = stream(request);
        return new ResponseStream(events, request, events);
    }

    /** A live coalesced stream over an open connection. */
    public final class EventStream implements Iterator<StreamEvent>, AutoCloseable {
        private final Request request;
        private final BuildContext cx;
        private final Transport.Streaming open;
        private final Iterator<SseEvent> frames;
        private final Coalescer coalescer;
        private final java.util.ArrayDeque<StreamEvent> pending = new java.util.ArrayDeque<>();
        private boolean exhausted;
        private boolean closed;
        private RuntimeException failure;

        EventStream(Request request, BuildContext cx, Transport.Streaming open) {
            this.request = request;
            this.cx = cx;
            this.open = open;
            this.frames = Sse.iterate(open.body());
            this.coalescer = new Coalescer(request.model());
        }

        private void fill() {
            if (failure != null) throw failure;
            if (closed) return;
            try {
                while (pending.isEmpty() && !exhausted) {
                    if (frames.hasNext()) {
                        for (StreamEvent e : dialect.parseStreamEvent(request, cx, frames.next())) pending.addAll(coalescer.push(e));
                    } else {
                        exhausted = true;
                        pending.addAll(coalescer.finish());
                    }
                }
                // Deliver the coalesced end before cleanup. A close failure after
                // that boundary is a warning, not a lost completed response (MAP-3).
                if (exhausted && pending.isEmpty()) close();
            } catch (RuntimeException error) {
                failure = error;
                try { close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
                throw error;
            } catch (Error error) {
                try { close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
        }

        @Override public boolean hasNext() { fill(); return !pending.isEmpty(); }

        @Override public StreamEvent next() {
            fill();
            if (pending.isEmpty()) throw new NoSuchElementException();
            return pending.poll();
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            exhausted = true;
            pending.clear();
            open.close();
        }
    }

    // ─── surfaces ───

    private void require(String surface) {
        if (!policy.supports().supports(surface)) {
            String word = switch (surface) {
                case "batches" -> "batch";
                case "images" -> "image generation";
                case "speech" -> "speech generation";
                case "video" -> "video generation";
                case "models" -> "model listing";
                default -> surface;
            };
            throw new UnsupportedFeatureError(provider() + ": " + word + " not supported", ErrorMeta.of(provider()));
        }
    }

    public TransportRequest modelsRequest() { return emit(dialect.modelsRequest(context()), false); }
    public List<ModelInfo> parseModels(String body) { return dialect.parseModels(context(), body); }

    public List<ModelInfo> listModels() {
        require("models");
        return parseModels(sendOk(modelsRequest()).text());
    }

    // files
    public TransportRequest fileUploadRequest(FileUploadRequest r) { return emit(dialect.fileUploadRequest(context(), r), false); }
    public TransportRequest fileGetRequest(String id) { return emit(dialect.fileGetRequest(context(), id), false); }
    public TransportRequest fileListRequest(int limit, String cursor) { return emit(dialect.fileListRequest(context(), limit, cursor), false); }
    public TransportRequest fileDeleteRequest(String id) { return emit(dialect.fileDeleteRequest(context(), id), false); }
    public TransportRequest fileDownloadRequest(String id) { return emit(dialect.fileDownloadRequest(context(), id), false); }
    public FileInfo parseFileInfo(String body) { return dialect.fileInfo(context(), body); }
    public FilePage parseFilePage(String body) { return dialect.filePage(context(), body); }

    public FileInfo fileUpload(FileUploadRequest r) { require("files"); return parseFileInfo(sendOk(fileUploadRequest(r)).text()); }
    public FileInfo fileGet(String id) { require("files"); return parseFileInfo(sendOk(fileGetRequest(id)).text()); }
    public FilePage fileList(int limit, String cursor) { require("files"); return parseFilePage(sendOk(fileListRequest(limit, cursor)).text()); }
    public FilePage fileList() { return fileList(20, null); }
    public void fileDelete(String id) { require("files"); sendOk(fileDeleteRequest(id)); }
    public byte[] fileDownload(String id) { require("files"); return sendOk(fileDownloadRequest(id)).body(); }

    /** Poll until the file leaves {@code pending}; returns the terminal snapshot (check readiness). */
    public FileInfo fileWaitReady(String id, long pollEveryMs, Long timeoutMs) {
        require("files");
        long deadline = timeoutMs == null ? Long.MAX_VALUE : System.nanoTime() + timeoutMs * 1_000_000L;
        FileInfo info = fileGet(id);
        while (info.readiness() == FileReadiness.PENDING) {
            if (System.nanoTime() >= deadline) throw new dev.lm15.errors.TimeoutError("file " + id + " still pending after " + timeoutMs + "ms");
            try {
                Thread.sleep(pollEveryMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TransportError("interrupted");
            }
            info = fileGet(id);
        }
        return info;
    }

    // batch
    public TransportRequest batchUploadRequest(BatchRequest r) { WireRequest w = dialect.batchUploadRequest(context(), r); return w == null ? null : emit(w, false); }
    public TransportRequest batchSubmitRequest(BatchRequest r, JsonObject uploadBody) { return emit(dialect.batchSubmitRequest(context(), r, uploadBody), false); }
    public TransportRequest batchStatusRequest(String id) { return emit(dialect.batchStatusRequest(context(), id), false); }
    public TransportRequest batchCancelRequest(String id) { return emit(dialect.batchCancelRequest(context(), id), false); }
    public TransportRequest batchListRequest(int limit) { return emit(dialect.batchListRequest(context(), limit), false); }
    public List<TransportRequest> batchResultFetches(JsonObject statusBody) {
        List<TransportRequest> out = new ArrayList<>();
        for (WireRequest w : dialect.batchResultFetches(context(), statusBody)) out.add(emit(w, false));
        return out;
    }
    public BatchJobInfo parseBatchJob(String body) { return dialect.batchJob(context(), body); }
    public List<BatchJobInfo> parseBatchJobs(String body) { return dialect.batchJobs(context(), body); }
    public List<BatchEntry> parseBatchEntries(JsonObject statusBody, List<String> fetched) { return dialect.batchEntries(context(), statusBody, fetched); }

    public BatchJobInfo batchSubmit(BatchRequest r) {
        require("batches");
        JsonObject uploadBody = null;
        TransportRequest upload = batchUploadRequest(r);
        if (upload != null) uploadBody = sendOk(upload).json().asObject();
        return parseBatchJob(sendOk(batchSubmitRequest(r, uploadBody)).text());
    }
    public BatchJobInfo batchStatus(String id) { require("batches"); return parseBatchJob(sendOk(batchStatusRequest(id)).text()); }
    public BatchJobInfo batchCancel(String id) { require("batches"); return parseBatchJob(sendOk(batchCancelRequest(id)).text()); }
    public List<BatchJobInfo> batchList(int limit) { require("batches"); return parseBatchJobs(sendOk(batchListRequest(limit)).text()); }

    /** Entries in submission order; a ValueError while the job runs. */
    public List<BatchEntry> batchResults(String id) {
        require("batches");
        HttpResponse resp = sendOk(batchStatusRequest(id));
        BatchJobInfo job = parseBatchJob(resp.text());
        if (!job.done()) throw ValidationException.value("batch " + id + " is not finished (status=" + job.status() + "); wait() or poll batchStatus() until done");
        JsonObject statusBody = resp.json().asObject();
        List<String> texts = new ArrayList<>();
        for (TransportRequest fetch : batchResultFetches(statusBody)) texts.add(sendOk(fetch).text());
        return parseBatchEntries(statusBody, texts);
    }

    public BatchJob batch(BatchRequest r) { return new BatchJob(this, batchSubmit(r)); }
    public BatchJob batch(List<Request> requests) { return batch(new BatchRequest(requests)); }
    public BatchJob batchJob(String id) { return new BatchJob(this, batchStatus(id)); }
    public List<BatchJob> batches(int limit) {
        List<BatchJob> out = new ArrayList<>();
        for (BatchJobInfo info : batchList(limit)) out.add(new BatchJob(this, info));
        return out;
    }
    public List<BatchJob> batches() { return batches(20); }

    // caches
    static void checkCachePrefix(Request prefix, Integer ttlSeconds) {
        if (!prefix.config().isDefault()) throw ValidationException.value("cache_create: the prefix Request must carry a default Config (a stored cache has no generation settings)");
        if (ttlSeconds != null && ttlSeconds <= 0) throw ValidationException.value("ttl_seconds must be a positive int");
    }

    public TransportRequest cacheCreateRequest(Request prefix, Integer ttlSeconds, String label) {
        checkCachePrefix(prefix, ttlSeconds);
        return emit(dialect.cacheCreateRequest(context(prefix), prefix, ttlSeconds, label), false);
    }
    public TransportRequest cacheGetRequest(String id) { return emit(dialect.cacheGetRequest(context(), id), false); }
    public TransportRequest cacheListRequest(int limit, String cursor) { return emit(dialect.cacheListRequest(context(), limit, cursor), false); }
    public TransportRequest cacheDeleteRequest(String id) { return emit(dialect.cacheDeleteRequest(context(), id), false); }
    public TransportRequest cacheUpdateRequest(String id, int ttlSeconds) {
        if (ttlSeconds <= 0) throw ValidationException.value("ttl_seconds must be a positive int");
        return emit(dialect.cacheUpdateRequest(context(), id, ttlSeconds), false);
    }
    public CacheInfo parseCacheInfo(String body) { return dialect.cacheInfo(context(), body); }
    public CachePage parseCachePage(String body) { return dialect.cachePage(context(), body); }

    public CacheInfo cacheCreate(Request prefix, Integer ttlSeconds, String label) { require("caches"); return parseCacheInfo(sendOk(cacheCreateRequest(prefix, ttlSeconds, label)).text()); }
    public CacheInfo cacheGet(String id) { require("caches"); return parseCacheInfo(sendOk(cacheGetRequest(id)).text()); }
    public CachePage cacheList(int limit, String cursor) { require("caches"); return parseCachePage(sendOk(cacheListRequest(limit, cursor)).text()); }
    public void cacheDelete(String id) { require("caches"); sendOk(cacheDeleteRequest(id)); }
    public CacheInfo cacheUpdate(String id, int ttlSeconds) { require("caches"); return parseCacheInfo(sendOk(cacheUpdateRequest(id, ttlSeconds)).text()); }

    /** A reusable prompt beginning: the stored object on the resource tier (one billed call), pure elsewhere. */
    public CachedPrefix cache(Request prefix, Integer ttlSeconds, String label) {
        if (policy.supports().caches()) return new CachedPrefix(prefix, cacheCreate(prefix, ttlSeconds, label));
        checkCachePrefix(prefix, ttlSeconds);
        return new CachedPrefix(prefix, null);
    }
    public CachedPrefix cache(Request prefix) { return cache(prefix, null, null); }

    // generation
    public TransportRequest imageGenerateRequest(ImageGenerationRequest r) { return emit(dialect.imageGenerateRequest(context().withModel(wireModel(r.model())), r), false); }
    public TransportRequest speechGenerateRequest(SpeechGenerationRequest r) { return emit(dialect.speechGenerateRequest(context().withModel(wireModel(r.model())), r), false); }
    public ImageGenerationResponse parseImageGeneration(ImageGenerationRequest r, HttpResponse resp) { return dialect.imageGeneration(context(), r, resp); }
    public SpeechGenerationResponse parseSpeechGeneration(SpeechGenerationRequest r, HttpResponse resp) { return dialect.speechGeneration(context(), r, resp); }

    public ImageGenerationResponse imageGenerate(ImageGenerationRequest r) { require("images"); return parseImageGeneration(r, sendOk(imageGenerateRequest(r))); }
    public SpeechGenerationResponse speechGenerate(SpeechGenerationRequest r) { require("speech"); return parseSpeechGeneration(r, sendOk(speechGenerateRequest(r))); }

    // video
    public TransportRequest videoSubmitRequest(VideoGenerationRequest r) { return emit(dialect.videoSubmitRequest(context().withModel(wireModel(r.model())), r), false); }
    public TransportRequest videoStatusRequest(String id) { return emit(dialect.videoStatusRequest(context(), id), false); }
    public TransportRequest videoResultFetch(JsonObject statusBody) { WireRequest w = dialect.videoResultFetch(context(), statusBody); return w == null ? null : emit(w, false); }
    public TransportRequest videoListRequest(int limit, String model) { return emit(dialect.videoListRequest(context(), limit, model), false); }
    public VideoJobInfo parseVideoJob(String body, String videoId) { return dialect.videoJob(context(), body, videoId); }
    public List<VideoJobInfo> parseVideoJobs(String body) { return dialect.videoJobs(context(), body); }
    public VideoPart parseVideoPart(JsonObject statusBody, HttpResponse fetched) { return dialect.videoPart(context(), statusBody, fetched); }

    public VideoJobInfo videoSubmit(VideoGenerationRequest r) { require("video"); return parseVideoJob(sendOk(videoSubmitRequest(r)).text(), null); }
    public VideoJobInfo videoStatus(String id) { require("video"); return parseVideoJob(sendOk(videoStatusRequest(id)).text(), id); }
    public List<VideoJobInfo> videoList(int limit, String model) { require("video"); return parseVideoJobs(sendOk(videoListRequest(limit, model)).text()); }

    /** The finished video in the provider's own delivery mode; a ValueError while the job runs. */
    public VideoPart videoResult(String id) {
        require("video");
        HttpResponse resp = sendOk(videoStatusRequest(id));
        VideoJobInfo job = parseVideoJob(resp.text(), id);
        if (!job.done()) throw ValidationException.value("video " + id + " is not finished (status=" + job.status() + "); wait() or poll videoStatus() until done");
        JsonObject statusBody = resp.json().asObject();
        TransportRequest fetch = videoResultFetch(statusBody);
        HttpResponse fetched = fetch == null ? null : sendOk(fetch);
        return parseVideoPart(statusBody, fetched);
    }

    public VideoJob videoGenerate(VideoGenerationRequest r) { return new VideoJob(this, videoSubmit(r)); }
    public VideoJob videoJob(String id) { return new VideoJob(this, videoStatus(id)); }
    public List<VideoJob> videoJobs(int limit, String model) {
        List<VideoJob> out = new ArrayList<>();
        for (VideoJobInfo info : videoList(limit, model)) out.add(new VideoJob(this, info));
        return out;
    }

    // live
    public LiveCodec liveCodec(LiveConfig config) { return dialect.liveCodec(context().withModel(wireModel(config.model())), config); }

    public LiveSession live(LiveConfig config) {
        require("live");
        return LiveSession.open(this, liveCodec(config), credential());
    }

    @Override public void close() { transport.close(); }

    @Override public String toString() {
        return "ProviderLM(provider=" + provider() + ", dialect=" + dialect.id() + ", base_url=" + baseUrl + ")";
    }

    // ─── builder ───

    public static final class Builder {
        private final ProviderDefinition definition;
        private Dialect dialect;
        private AccessPolicy policy;
        private Compat compat;
        private String compatPreset;
        private String baseUrl;
        private Map<String, String> settings = Map.of();
        private Map<String, String> env;
        private CredentialProvider credentials;
        private String credentialSource = "explicit";
        private Path credentialsPath;
        private String accountId;
        private Clock clock = Clock.SYSTEM;
        private Transport transport;

        Builder(ProviderDefinition definition) {
            this.definition = definition;
            this.policy = definition.access();
        }

        public Builder apiKey(String key) { this.credentials = key == null ? null : CredentialProvider.of(key); return this; }
        public Builder credential(Credential c) { this.credentials = c == null ? null : CredentialProvider.of(c); return this; }
        public Builder credentials(CredentialProvider p) { this.credentials = p; return this; }
        public Builder credentialsPath(Path p) { this.credentialsPath = p; return this; }
        public Builder baseUrl(String url) { this.baseUrl = url; return this; }
        public Builder settings(Map<String, String> s) { this.settings = s == null ? Map.of() : s; return this; }
        public Builder setting(String name, String value) { var m = new java.util.LinkedHashMap<>(settings); m.put(name, value); settings = m; return this; }
        /** Complete environment for credentials and host settings; defaults to the process environment. */
        public Builder env(Map<String, String> env) { this.env = env; return this; }
        public Builder compat(Compat c) { this.compat = c; return this; }
        public Builder preset(String name) { this.compatPreset = name; return this; }
        public Builder policy(AccessPolicy p) { this.policy = p; return this; }
        public Builder accountId(String id) { this.accountId = id; return this; }
        public Builder clock(Clock c) { this.clock = c; return this; }
        public Builder transport(Transport t) { this.transport = t; return this; }

        private Builder(Builder source) {
            definition = source.definition;
            policy = source.policy;
            compat = source.compat;
            compatPreset = source.compatPreset;
            baseUrl = source.baseUrl;
            settings = source.settings;
            env = source.env;
            credentials = source.credentials;
            credentialsPath = source.credentialsPath;
            accountId = source.accountId;
            clock = source.clock;
            transport = source.transport;
        }

        public ProviderLM build() {
            Builder bound = new Builder(this);
            boolean ownsTransport = bound.transport == null;
            if (ownsTransport) bound.transport = new HttpTransport();
            try {
                return bound.buildBound();
            } catch (RuntimeException | Error error) {
                if (ownsTransport) {
                    try { bound.transport.close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
                }
                throw error;
            }
        }

        private ProviderLM buildBound() {
            dialect = Dialects.lookup(definition.dialectImpl());
            if (compat == null) {
                String preset = compatPreset != null ? compatPreset : definition.compat();
                compat = preset != null ? dialect.compat(preset) : dialect.defaultCompat(policy.provider());
            }
            ChainContext authContext = ChainContext.online(env, null, clock, transport);
            // Direct subscription adapters allow a supplied credential (the shim uses this
            // path too). Router subscription policies remain local-store-only (AUTH-1).
            if (credentials == null || policy.credentialPolicy() != CredentialPolicy.OAUTH) {
                Map<String, CredentialProvider> explicit = credentials == null ? Map.of() : Map.of(policy.provider(), credentials);
                AuthChain.Resolved resolved = AuthChain.resolve(policy, explicit, credentialsPath, authContext, settings);
                credentials = resolved.require(policy);
                credentialSource = "oauth-file".equals(resolved.source()) ? "stored" : resolved.source();
                if (policy.host() != null) settings = resolved.settings();
                if (accountId == null && "stored".equals(credentialSource) && policy.provider().equals("openai-codex")) {
                    var stored = CodexStore.read(AuthChain.oauthPath(policy.provider(), credentialsPath, authContext.home()));
                    if (stored != null) accountId = stored.accountId();
                }
            }
            // A named preset's server address, never the cloud's (api-family 2026-09-11).
            if (baseUrl == null) {
                if (policy.host() != null) {
                    baseUrl = Hosts.renderBaseUrl(policy.host(), settings);
                } else if (policy.baseUrl() != null) {
                    baseUrl = policy.baseUrl();
                } else if (compatPreset != null && !compatPreset.equals(policy.provider())) {
                    baseUrl = dev.lm15.compat.PresetAddresses.forPreset(dialect.id(), compatPreset, policy.provider());
                } else {
                    baseUrl = dev.lm15.compat.PresetAddresses.dialectDefault(dialect.id());
                }
            }
            // Check known values now without ever executing a callback at construction.
            if (credentials instanceof CredentialProvider.Fixed fixed) Access.selectScheme(policy, fixed.value());
            return new ProviderLM(this);
        }
    }
}
