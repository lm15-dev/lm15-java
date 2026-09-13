package dev.lm15.router;

import dev.lm15.ProviderLM;
import dev.lm15.auth.Access;
import dev.lm15.auth.CredentialProvider;
import dev.lm15.compat.PresetAddresses;
import dev.lm15.dialects.Dialect;
import dev.lm15.dialects.Dialects;
import dev.lm15.errors.AmbiguousModelError;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.NotConfiguredError;
import dev.lm15.errors.UnknownModelError;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.registry.DialectId;
import dev.lm15.registry.ProviderDefinition;
import dev.lm15.registry.Registry;
import dev.lm15.stream.ResponseStream;
import dev.lm15.types.ModelInfo;
import dev.lm15.types.Request;
import dev.lm15.types.Response;
import dev.lm15.types.ValidationException;
import dev.lm15.wire.BuildContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimalist model-string router (lm15-python {@code lm15.router}). A lookup
 * table you can read, not a framework. Three resolution rungs, in fixed
 * order, with no configuration of the order itself:
 *
 * <ol>
 * <li>explicit prefix — {@code "openai:gpt-4.1-mini"} → source {@code prefix}
 *     (split on the FIRST colon; the head, in either spelling, must name a
 *     routable provider and the rest must be non-empty, else the whole string
 *     falls through as a bare id);</li>
 * <li>catalog — a match by id or alias against the explicit catalog → source
 *     {@code catalog} (exact-id matches beat alias matches; more than one
 *     provider, or more than one entry of one provider, is
 *     {@link AmbiguousModelError});</li>
 * <li>built-in rules — {@link #DEFAULT_RULES} prefix match → source {@code rule}.</li>
 * </ol>
 * Nothing else: {@link UnknownModelError}. No plugins, no callbacks, no
 * fallback chains. A provider is routable when {@link Registry} declares it.
 *
 * <p>{@link #resolve} touches no network and invokes no credential provider.
 * {@link #lm} builds (and caches, one per provider) the ordinary
 * {@link ProviderLM} with the credential the AUTH-1 chain resolves — the
 * direct constructors ({@code OpenAILM}, …) remain first-class; the router is
 * the recommended front door and {@code lm()} is the escape hatch.
 */
public final class LMRouter {

    // ─── rules (data; lm15-python lm15/router.py DEFAULT_RULES) ───

    /** The complete built-in knowledge of the router. First match wins; replace with {@code RouterConfig.rules}. */
    public static final List<RouteRule> DEFAULT_RULES = List.of(
        new RouteRule("claude-", "anthropic", "Anthropic Claude family"),
        new RouteRule("gpt-", "openai", "OpenAI GPT family (Responses API; use openai-chat: for Chat Completions)"),
        new RouteRule("o1", "openai", "OpenAI o1 reasoning family"),
        new RouteRule("o3", "openai", "OpenAI o3 reasoning family"),
        new RouteRule("o4", "openai", "OpenAI o4 reasoning family"),
        new RouteRule("gemini-", "gemini", "Google Gemini family"),
        new RouteRule("gemma-", "gemini", "Google Gemma open models, served by the Gemini API (live /models listing 2026-09-01)"),
        new RouteRule("nano-banana", "gemini", "Google image models on the Gemini API (live /models listing 2026-09-01)"),
        new RouteRule("grok-", "xai", "xAI Grok family (XAI_API_KEY or subscription OAuth)"),
        new RouteRule("sora-", "openai", "OpenAI Sora video generation"),
        new RouteRule("veo-", "gemini", "Google Veo video generation"),
        new RouteRule("chat-latest", "openai", "OpenAI rolling chat alias (live /models listing 2026-09-01)"));

    /**
     * litellm's routing prefixes ({@code <provider>/<model>}) that name a door
     * lm15 has, copied as data. A prefix absent here is refused by name — never
     * routed by rule. Where litellm's name covers two lm15 doors ({@code bedrock},
     * {@code vertex_ai}: Anthropic or not, by model) it is left out: choosing
     * would be a guess.
     */
    public static final Map<String, String> LITELLM_PROVIDER_PREFIXES;

    /** Keyword arguments of {@code create()} / {@code completion()} that configure the CLIENT, not the request: refused with the lm15 place they belong. */
    static final Map<String, String> CLIENT_KEYWORDS;

    static {
        LinkedHashMap<String, String> p = new LinkedHashMap<>();
        p.put("openai", "openai-chat");
        p.put("anthropic", "anthropic");
        p.put("gemini", "gemini");
        p.put("groq", "groq");
        p.put("openrouter", "openrouter");
        p.put("deepseek", "deepseek");
        p.put("xai", "xai");
        p.put("ollama", "ollama");
        p.put("ollama_chat", "ollama");
        p.put("hosted_vllm", "vllm");
        p.put("moonshot", "moonshotai");
        p.put("azure", "azure-chat");
        LITELLM_PROVIDER_PREFIXES = Collections.unmodifiableMap(p);

        LinkedHashMap<String, String> k = new LinkedHashMap<>();
        k.put("api_key", "RouterConfig.builder().apiKey(provider, key) or the environment");
        k.put("api_base", "RouterConfig.builder().baseUrl(provider, url)");
        k.put("base_url", "RouterConfig.builder().baseUrl(provider, url)");
        k.put("timeout", "RouterConfig.builder().transport(...)");
        k.put("num_retries", "your own retry loop over LM15Error.isRetryable() (lm15 never retries)");
        k.put("max_retries", "your own retry loop over LM15Error.isRetryable() (lm15 never retries)");
        k.put("headers", "RouterConfig.builder().transport(...)");
        k.put("extra_headers", "RouterConfig.builder().transport(...)");
        k.put("extra_body", "config.extensions on the Request (build it with requestFromOpenAIChat and edit)");
        k.put("extra_query", "RouterConfig.builder().transport(...)");
        k.put("cache", "your own cache keyed on the Request (lm15 has no response cache)");
        k.put("caching", "your own cache keyed on the Request (lm15 has no response cache)");
        k.put("mock_response", "a scripted Transport");
        k.put("drop_params", "nothing: lm15 refuses what it cannot carry instead of dropping it");
        k.put("custom_llm_provider", "the model string's prefix");
        CLIENT_KEYWORDS = Collections.unmodifiableMap(k);
    }

    // ─── state ───

    private final RouterConfig config;
    private final Map<String, ProviderLM> lms = new ConcurrentHashMap<>();

    public LMRouter() { this(RouterConfig.DEFAULT); }

    public LMRouter(RouterConfig config) {
        this.config = config == null ? RouterConfig.DEFAULT : config;
        checkProviderKeyed(this.config);
    }

    public RouterConfig config() { return config; }

    // ─── resolution ───

    /**
     * Offline lookup; invokes no credential providers and returns no secrets.
     * Raises {@link UnknownModelError} / {@link AmbiguousModelError}.
     */
    public Resolution resolve(String model) {
        if (model == null || model.isEmpty()) {
            throw new UnknownModelError("model must be a non-empty string", model == null ? "" : model);
        }
        String requested = model;

        // Rung 1: explicit provider prefix (split on FIRST colon).
        int colon = model.indexOf(':');
        if (colon >= 0) {
            String head = Registry.canonicalProvider(model.substring(0, colon));
            String rest = model.substring(colon + 1);
            if (routable(head) && !rest.isEmpty()) {
                return resolution(requested, rest, head, Resolution.Source.PREFIX, null, null);
            }
        }

        // Rung 2: catalog (only when one was explicitly supplied).
        ModelRegistry catalog = config.catalog();
        if (catalog != null) {
            List<ModelInfo> matches = new ArrayList<>();
            for (ModelInfo info : catalog.list()) {
                if (info.id().equals(model) || info.aliases().contains(model)) matches.add(info);
            }
            LinkedHashSet<String> providerSet = new LinkedHashSet<>();
            for (ModelInfo info : matches) providerSet.add(info.provider());
            List<String> providers = List.copyOf(providerSet);
            if (providers.size() > 1) {
                List<String> options = providers.stream().map(p -> "\"" + p + ":" + model + "\"").toList();
                throw new AmbiguousModelError("model '" + model + "' is offered by multiple providers: " + String.join(", ", providers)
                    + ". Fix: use the explicit form, e.g. Request(model=\"" + providers.get(0) + ":" + model + "\") — options: "
                    + String.join(" or ", options) + ".", model, providers);
            }
            if (!matches.isEmpty()) {
                // Exact-id matches beat alias matches; an alias must never shadow an entry whose canonical id IS the requested string.
                List<ModelInfo> exact = matches.stream().filter(i -> i.id().equals(model)).toList();
                List<ModelInfo> narrowed = exact.isEmpty() ? matches : exact;
                if (narrowed.size() > 1) {
                    // Same provider (multi-provider was caught above), multiple entries: never pick one by insertion order.
                    String ids = String.join(", ", narrowed.stream().map(ModelInfo::id).toList());
                    throw new AmbiguousModelError("model '" + model + "' matches multiple catalog entries (" + ids + ") under provider '"
                        + narrowed.get(0).provider() + "'. Fix: request a canonical id directly.", model, providers);
                }
                ModelInfo info = narrowed.get(0);
                String catalogProvider = Registry.canonicalProvider(info.provider());
                if (!routable(catalogProvider)) {
                    throw new UnknownModelError("model '" + model + "' resolved in the catalog to provider '" + info.provider()
                        + "', but lm15 has no adapter or compat preset for it. Known providers: " + knownProviders()
                        + ". Construct a provider LM directly (e.g. OpenAIChatLM with a custom base_url) for OpenAI-compatible servers.", model);
                }
                String wire = info.aliases().contains(model) ? info.id() : model;
                return resolution(requested, wire, catalogProvider, Resolution.Source.CATALOG, null, info);
            }
        }

        // Rung 3: built-in prefix rules, first match wins.
        for (RouteRule rule : config.rules()) {
            if (model.startsWith(rule.prefix())) {
                String ruleProvider = Registry.canonicalProvider(rule.provider());
                if (!routable(ruleProvider)) {
                    throw new UnknownModelError("rule " + rule + " names provider '" + rule.provider() + "', which has no adapter. Known providers: "
                        + knownProviders() + ".", model);
                }
                return resolution(requested, model, ruleProvider, Resolution.Source.RULE, rule, null);
            }
        }

        List<String> hints = new ArrayList<>();
        if (colon >= 0) {
            String head = Registry.canonicalProvider(model.substring(0, colon));
            String close = closest(head, Registry.PROVIDERS.keySet(), 0.75);
            if (close != null) hints.add("Did you mean \"" + close + ":" + model.substring(colon + 1) + "\"?");
        }
        hints.add("Use an explicit provider prefix — \"provider:" + model + "\" with provider one of: " + knownProviders() + ".");
        if (catalog == null) hints.add("Or pass a model catalog: RouterConfig.builder().catalog(...).");
        throw new UnknownModelError("could not route model '" + model + "': no provider prefix, "
            + (catalog != null ? "no catalog match" : "no catalog supplied") + ", and none of the " + config.rules().size()
            + " built-in rules matched. " + String.join(" ", hints), model);
    }

    /** {@link #resolve} rendered as one paragraph ({@link Resolution#describe()}). */
    public String explain(String model) { return resolve(model).describe(); }

    private Resolution resolution(String requested, String wire, String provider, Resolution.Source source, RouteRule rule, ModelInfo info) {
        ProviderDefinition definition = Registry.require(provider);
        // A bound entry (a dialect class with this provider's access policy) carries its compat preset; an owned adapter has none.
        return new Resolution(requested, wire, provider, adapterName(definition), source, rule, envKeyFor(definition), info, definition.compat());
    }

    /** The family LM class name for a definition. */
    static String adapterName(ProviderDefinition definition) {
        return switch (definition.id()) {
            case "openai" -> "OpenAILM";
            case "openai-chat" -> "OpenAIChatLM";
            case "anthropic" -> "AnthropicLM";
            case "gemini" -> "GeminiLM";
            case "xai" -> "XaiLM";
            case "claude-code" -> "ClaudeCodeLM";
            case "openai-codex" -> "OpenAICodexLM";
            default -> switch (definition.dialect()) {
                case OPENAI_RESPONSES -> "OpenAILM";
                case OPENAI_CHAT -> "OpenAIChatLM";
                case ANTHROPIC -> "AnthropicLM";
                case GEMINI -> "GeminiLM";
            };
        };
    }

    /** WHICH env var {@link #lm} would read for this provider (never the value); null when explicit keys override or none is declared. */
    private String envKeyFor(ProviderDefinition definition) {
        if (CredentialResolution.apiKeysSource(config.apiKeys(), definition.id()) != null) return null;
        List<String> envKeys = definition.envKeys();
        if (envKeys.isEmpty()) return null;
        for (String key : envKeys) if (config.envValue(key) != null) return key;
        return envKeys.get(0);
    }

    private static boolean routable(String provider) { return Registry.lookup(provider) != null; }

    private static String knownProviders() { return String.join(", ", new TreeSet<>(Registry.PROVIDERS.keySet())); }

    // ─── adapters ───

    /** {@link #resolve}, then construct-or-reuse the provider LM (one per provider). */
    public ProviderLM lm(String model) { return adapterFor(resolve(model)); }

    /** The provider LM a resolution routes to, built with the AUTH-1 credential and cached per provider. */
    public ProviderLM adapterFor(Resolution resolution) {
        return lms.computeIfAbsent(resolution.provider(), this::buildLm);
    }

    private ProviderLM buildLm(String provider) {
        ProviderDefinition definition = Registry.require(provider);
        ProviderLM.Builder builder = ProviderLM.builder(definition)
            .env(config.environment())
            .clock(config.clock())
            .credentialsPath(config.credentialsPath())
            .settings(config.settingsFor(provider));
        if (config.transport() != null) builder.transport(config.transport());
        String baseUrl = config.baseUrlFor(provider);
        if (baseUrl != null) {
            if (definition.hosted()) {
                throw new NotConfiguredError("RouterConfig(base_urls={'" + provider + "': ...}): a cloud door's URL is built from its host settings "
                    + "(resource, region), not given whole; set them in RouterConfig.builder().settings(\"" + provider + "\", {...}) instead.",
                    ErrorMeta.of(provider));
            }
            builder.baseUrl(baseUrl);
        }
        CredentialResolution.Resolved resolved = CredentialResolution.resolve(
            definition.access(), config.apiKeys(), config.environment(), config.credentialsPath());
        if (resolved.credential() != null) builder.credentials(resolved.credential());
        return builder.build();
    }

    private ProviderLM lmForProvider(String provider) {
        String canonical = Registry.canonicalProvider(provider == null ? "" : provider);
        if (!routable(canonical)) {
            throw new NotConfiguredError("'" + provider + "' is not a provider lm15 routes to; known: " + knownProviders(), ErrorMeta.NONE);
        }
        return lms.computeIfAbsent(canonical, this::buildLm);
    }

    // ─── the core loop ───

    public Response complete(Request request) {
        Resolution resolution = resolve(request.model());
        return adapterFor(resolution).complete(routed(request, resolution));
    }

    /** The coalesced canonical event stream; closing it releases the connection. */
    public ProviderLM.EventStream stream(Request request) {
        Resolution resolution = resolve(request.model());
        return adapterFor(resolution).stream(routed(request, resolution));
    }

    /** {@link #stream} assembled: iterate for text, {@code response()} for the Response. */
    public ResponseStream responseStream(Request request) {
        Resolution resolution = resolve(request.model());
        return adapterFor(resolution).responseStream(routed(request, resolution));
    }

    /** The provider's model listing, through the LM the router builds for it. */
    public List<ModelInfo> listModels(String provider) { return lmForProvider(provider).listModels(); }

    private static Request routed(Request request, Resolution resolution) {
        return request.model().equals(resolution.model()) ? request : request.withModel(resolution.model());
    }

    // ─── the OpenAI-shaped door (api-family § Ingest) ───

    /** The Request behind {@link #completeFromOpenAIChat} and the LM it routes to. */
    public record RoutedRequest(Request request, ProviderLM lm) {}

    /**
     * The lm15 model string for a model string written for the OpenAI SDK or
     * litellm: an lm15 string ({@code provider:model}) is left alone; litellm's
     * {@code provider/model} maps its prefix through
     * {@link #LITELLM_PROVIDER_PREFIXES} (only the first segment; a model id
     * may contain slashes itself: {@code groq/openai/gpt-oss-20b}); a bare name
     * routes by lm15's rules, except that OpenAI's models go to the Chat
     * Completions door ({@link #resolveOpenAIChat}).
     */
    public static String openaiChatModelString(String model) {
        if (model == null) throw new UnknownModelError("model must be a non-empty string", "");
        if (model.contains(":")) return model;
        int slash = model.indexOf('/');
        if (slash < 0) return model;
        String head = model.substring(0, slash);
        String rest = model.substring(slash + 1);
        if (rest.isEmpty()) return model;
        String provider = LITELLM_PROVIDER_PREFIXES.get(head);
        if (provider == null) {
            throw new UnknownModelError("could not read '" + model + "' as a litellm model string: '" + head + "' is not a provider prefix lm15 has a door for (known: "
                + String.join(", ", new TreeSet<>(LITELLM_PROVIDER_PREFIXES.keySet())) + "); write it as lm15's provider:model instead", model);
        }
        return provider + ":" + rest;
    }

    /**
     * {@link #resolve} for the OpenAI-shaped door: {@code model} is read by
     * {@link #openaiChatModelString}, and a bare OpenAI name goes to Chat
     * Completions ({@code openai-chat}), the endpoint the OpenAI SDK and litellm
     * were using. Pure, like {@code resolve}.
     */
    public Resolution resolveOpenAIChat(String model) {
        Resolution resolution = resolve(openaiChatModelString(model));
        if (resolution.source() == Resolution.Source.RULE && resolution.provider().equals("openai")) {
            return resolve("openai-chat:" + resolution.model());
        }
        return resolution;
    }

    /**
     * {@code client.chat.completions.create(model=, messages=, ...)} taken
     * apart: the canonical Request and the LM it routes to. The body is read
     * with the destination door's own spellings when it speaks the Chat
     * Completions wire, else with OpenAI's (MAP-12). Client keywords are
     * refused with the {@code RouterConfig} place named.
     */
    public RoutedRequest requestFromOpenAIChat(String model, JsonArray messages, JsonObject kwargs) {
        Resolution resolution = resolveOpenAIChat(model);
        JsonObject body = splitOpenAIChatCall(resolution.requested(), messages, kwargs);
        ProviderLM lm = adapterFor(resolution);
        Request request = lm.dialect().id() == DialectId.OPENAI_CHAT ? lm.requestFromOpenAIChat(body) : readWithOpenAIChat(body);
        return new RoutedRequest(routed(request, resolution), lm);
    }

    /** {@code client.chat.completions.create(...)} or {@code litellm.completion(...)} — the same call, answered by lm15 as a canonical Response. */
    public Response completeFromOpenAIChat(String model, JsonArray messages, JsonObject kwargs) {
        RoutedRequest r = requestFromOpenAIChat(model, messages, kwargs);
        return r.lm().complete(r.request());
    }

    /** The streaming twin of {@link #completeFromOpenAIChat}: typed lm15 stream events, not OpenAI-shaped chunks. */
    public ProviderLM.EventStream streamFromOpenAIChat(String model, JsonArray messages, JsonObject kwargs) {
        RoutedRequest r = requestFromOpenAIChat(model, messages, kwargs);
        return r.lm().stream(r.request());
    }

    /** {@code (model, messages, kwargs)} → the Chat Completions body, after refusing the client keywords by name. */
    static JsonObject splitOpenAIChatCall(String model, JsonArray messages, JsonObject kwargs) {
        JsonObject extra = kwargs == null ? JsonObject.EMPTY : kwargs;
        for (Map.Entry<String, String> e : CLIENT_KEYWORDS.entrySet()) {
            if (extra.has(e.getKey())) {
                throw new NotConfiguredError("'" + e.getKey() + "' configures the client, not the request; in lm15 it lives in " + e.getValue());
            }
        }
        if (messages == null) throw ValidationException.type("messages must be a list");
        JsonBuilder body = new JsonBuilder().put("model", model).put("messages", (JsonValue) messages);
        for (String key : extra.keys()) body.put(key, extra.get(key));
        return body.build();
    }

    /** OpenAI's own spellings, for a destination that does not speak the Chat Completions wire. */
    private static Request readWithOpenAIChat(JsonObject body) {
        Dialect chat = Dialects.lookup(DialectId.OPENAI_CHAT.wire());
        BuildContext cx = new BuildContext("openai-chat", Access.OPENAI_CHAT_API, Map.of(), chat.defaultCompat("openai-chat"),
            PresetAddresses.dialectDefault(DialectId.OPENAI_CHAT), null, null);
        return chat.requestFromOpenAIChat(cx, body);
    }

    // ─── configuration checks ───

    /**
     * Every provider string a RouterConfig is keyed by (api_keys, base_urls,
     * settings) must name a routable provider: an entry that matches nothing is
     * otherwise silently ignored, and the request goes out on whatever the
     * environment holds — the wrong account, with nothing said (AUTH-1).
     * Duplicate spellings of one provider under api_keys are refused, not
     * resolved by order.
     */
    private static void checkProviderKeyed(RouterConfig config) {
        checkKeys("api_keys", config.apiKeys().keySet(), true);
        checkKeys("base_urls", config.baseUrls().keySet(), false);
        checkKeys("settings", config.settings().keySet(), false);
    }

    private static void checkKeys(String field, Iterable<String> keys, boolean refuseDuplicates) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String key : keys) {
            String provider = Registry.canonicalProvider(key);
            if (refuseDuplicates && !seen.add(provider)) {
                throw new NotConfiguredError("RouterConfig(" + field + "=...): duplicate spellings for '" + provider + "'; use one entry");
            }
            if (routable(provider)) continue;
            String close = closest(provider, Registry.PROVIDERS.keySet(), 0.6);
            String hint = close == null ? "" : " Did you mean '" + close + "'?";
            throw new NotConfiguredError("RouterConfig(" + field + "=...): '" + key + "' is not a provider lm15 routes to." + hint
                + " router.resolve(model).provider (or resolveOpenAIChat) names the one a model string uses; known: " + knownProviders());
        }
    }

    /** The closest known name at or above {@code cutoff} (difflib-style ratio: 2·LCS / (|a| + |b|)), or null. */
    private static String closest(String word, Iterable<String> known, double cutoff) {
        String best = null;
        double bestScore = cutoff;
        TreeSet<String> sorted = new TreeSet<>();
        known.forEach(sorted::add);
        for (String candidate : sorted) {
            double score = similarity(candidate, word);
            if (score > bestScore || best == null && score >= cutoff) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static double similarity(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1) ? dp[i - 1][j - 1] + 1 : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        return 2.0 * dp[a.length()][b.length()] / (a.length() + b.length());
    }

    /** Explicit keys never render (AUTH-5). */
    @Override public String toString() { return "LMRouter(config=" + config + ", built=" + new TreeSet<>(lms.keySet()) + ")"; }
}
