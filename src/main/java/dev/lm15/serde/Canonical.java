package dev.lm15.serde;

import dev.lm15.auth.Credential;
import dev.lm15.auth.Rfc3339;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBool;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonFloat;
import dev.lm15.json.JsonInt;
import dev.lm15.json.JsonNull;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.types.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Canonical JSON serde (docs/serde-rules.md): one {@code toJson} /
 * {@code fromJson} pair per type, the one omission rule applied by each typed
 * serializer at its own top level, opaque payloads embedded verbatim, and the
 * Number rule enforced at the read boundary (INV-007/008: an integral float
 * reads into an int field, an int into a float field, a bool into neither).
 */
public final class Canonical {
    private Canonical() {}

    // ─── read helpers (the constructor-coercion half of INV-046) ─────────

    private static JsonValue present(JsonObject o, String key) {
        JsonValue v = o.get(key);
        return v instanceof JsonNull ? null : v;
    }

    private static JsonValue require(JsonObject o, String key) {
        JsonValue v = o.get(key);
        if (v == null) throw ValidationException.type("missing required field: " + key);
        return v;
    }

    public static String readString(JsonObject o, String key) {
        JsonValue v = present(o, key);
        if (v == null) return null;
        if (v instanceof JsonString s) return s.value();
        throw ValidationException.type(key + " must be a string");
    }

    public static String readString(JsonObject o, String key, String fallback) {
        String s = readString(o, key);
        return s == null ? fallback : s;
    }

    public static String requireString(JsonObject o, String key) {
        JsonValue v = require(o, key);
        if (v instanceof JsonString s) return s.value();
        throw ValidationException.type(key + " must be a string");
    }

    public static Integer readInt(JsonObject o, String key) {
        return toInt(present(o, key), key);
    }

    public static Integer toInt(JsonValue v, String key) {
        if (v == null || v instanceof JsonNull) return null;
        if (v instanceof JsonInt i) return i.value().intValueExact();
        if (v instanceof JsonFloat f) {
            if (!f.isIntegral()) throw ValidationException.type(key + " must be an int");
            return f.toBigInteger().intValueExact();
        }
        throw ValidationException.type(key + " must be an int");
    }

    public static Long readLong(JsonObject o, String key) {
        JsonValue v = present(o, key);
        if (v == null) return null;
        if (v instanceof JsonInt i) return i.value().longValueExact();
        if (v instanceof JsonFloat f) {
            if (!f.isIntegral()) throw ValidationException.type(key + " must be an int");
            return f.toBigInteger().longValueExact();
        }
        throw ValidationException.type(key + " must be an int");
    }

    public static Double readDouble(JsonObject o, String key) {
        return toDouble(present(o, key), key);
    }

    public static Double toDouble(JsonValue v, String key) {
        if (v == null || v instanceof JsonNull) return null;
        if (v instanceof JsonInt i) return i.value().doubleValue();
        if (v instanceof JsonFloat f) return f.value();
        throw ValidationException.type(key + " must be numeric");
    }

    public static Boolean readBool(JsonObject o, String key) {
        JsonValue v = present(o, key);
        if (v == null) return null;
        if (v instanceof JsonBool b) return b.value();
        throw ValidationException.type(key + " must be a bool");
    }

    public static JsonObject readObject(JsonObject o, String key) {
        JsonValue v = present(o, key);
        if (v == null) return null;
        if (v instanceof JsonObject j) return j;
        throw ValidationException.type(key + " must be a JSON object");
    }

    public static JsonArray readArray(JsonObject o, String key) {
        JsonValue v = present(o, key);
        if (v == null) return null;
        if (v instanceof JsonArray a) return a;
        throw ValidationException.type(key + " must be an array");
    }

    /** INV-020: a bare string reads as one element; a list as-is. */
    public static List<String> readStrings(JsonObject o, String key) {
        JsonValue v = present(o, key);
        if (v == null) return List.of();
        if (v instanceof JsonString s) return List.of(s.value());
        if (v instanceof JsonArray a) {
            List<String> out = new ArrayList<>();
            for (JsonValue item : a) {
                if (!(item instanceof JsonString s)) throw ValidationException.value(key + " must contain strings");
                out.add(s.value());
            }
            return out;
        }
        throw ValidationException.type(key + " must be a list of strings");
    }

    private static <E extends Enum<E>> E readEnum(JsonObject o, String key, Function<String, E> parse) {
        String s = readString(o, key);
        return s == null ? null : parse.apply(s);
    }

    private static <E extends Enum<E>> E requireEnum(JsonObject o, String key, Function<String, E> parse) {
        return parse.apply(requireString(o, key));
    }

    // ─── write helpers ───────────────────────────────────────────────────

    private static JsonValue str(String s) { return s == null ? JsonNull.INSTANCE : new JsonString(s); }
    private static JsonValue integer(Integer i) { return i == null ? JsonNull.INSTANCE : JsonInt.of(i); }
    private static JsonValue integer(Long i) { return i == null ? JsonNull.INSTANCE : JsonInt.of(i); }
    private static JsonValue dbl(Double d) { return d == null ? JsonNull.INSTANCE : new JsonFloat(d); }
    private static JsonValue bool(Boolean b) { return b == null ? JsonNull.INSTANCE : JsonBool.of(b); }
    private static JsonValue enumWire(Enum<?> e) { return e == null ? JsonNull.INSTANCE : new JsonString(e.toString()); }
    private static JsonValue orNull(JsonValue v) { return v == null ? JsonNull.INSTANCE : v; }

    private static JsonArray strings(List<String> xs) {
        List<JsonValue> out = new ArrayList<>(xs.size());
        for (String s : xs) out.add(new JsonString(s));
        return new JsonArray(out);
    }

    // ─── continuation ────────────────────────────────────────────────────

    public static JsonObject toJson(ContinuationState s) {
        return Json.obj("provider", s.provider(), "kind", s.kind(), "data", s.data());
    }

    public static ContinuationState continuationStateFromJson(JsonObject d) {
        JsonObject data = readObject(d, "data");
        return new ContinuationState(requireString(d, "provider"), requireString(d, "kind"), data == null ? JsonObject.EMPTY : data);
    }

    private static JsonValue continuationToJson(List<ContinuationState> states) {
        if (states.isEmpty()) return JsonNull.INSTANCE;
        List<JsonValue> out = new ArrayList<>();
        for (ContinuationState s : states) out.add(toJson(s));
        return new JsonArray(out);
    }

    private static List<ContinuationState> continuationFromJson(JsonObject d) {
        JsonValue v = present(d, "continuation");
        if (v == null) return List.of();
        if (!(v instanceof JsonArray a)) throw ValidationException.type("continuation must be a list");
        List<ContinuationState> out = new ArrayList<>();
        for (JsonValue item : a) {
            if (!(item instanceof JsonObject o)) throw ValidationException.type("continuation entries must be objects");
            out.add(continuationStateFromJson(o));
        }
        return out;
    }

    // ─── parts ───────────────────────────────────────────────────────────

    public static JsonObject toJson(Part part) {
        JsonBuilder d = new JsonBuilder().put("type", part.type().wire());
        switch (part) {
            case TextPart p -> d.put("text", p.text());
            case ThinkingPart p -> d.put("text", p.text());
            case RefusalPart p -> d.put("text", p.text());
            case CitationPart p -> {
                d.putIfPresent("text", p.text());
                d.putIfPresent("url", p.url());
                d.putIfPresent("title", p.title());
            }
            case MediaPart p -> {
                d.put("media_type", p.mediaType());
                d.putIfPresent("data", p.data());
                d.putIfPresent("url", p.url());
                d.putIfPresent("file_id", p.fileId());
                if (p.path() != null) d.put("path", p.path().toString());
                if (p instanceof ImagePart img && img.detail() != null) d.put("detail", img.detail().wire());
            }
            case ToolCallPart p -> {
                d.put("id", p.id());
                d.put("name", p.name());
                d.put("input", p.input());
            }
            case ToolResultPart p -> {
                d.put("id", p.id());
                d.putIfPresent("name", p.name());
                List<JsonValue> content = new ArrayList<>();
                for (Part c : p.content()) content.add(toJson(c));
                d.put("content", new JsonArray(content));
                if (p.isError()) d.put("is_error", true);
            }
        }
        JsonValue continuation = continuationToJson(part.continuation());
        if (!(continuation instanceof JsonNull)) d.put("continuation", continuation);
        return d.build();
    }

    public static Part partFromJson(JsonObject d) {
        String t = requireString(d, "type");
        List<ContinuationState> continuation = continuationFromJson(d);
        switch (t) {
            case "text": return new TextPart(readString(d, "text", ""), continuation);
            case "thinking": return new ThinkingPart(readString(d, "text", ""), continuation);
            case "refusal": return new RefusalPart(readString(d, "text", ""), continuation);
            case "citation": return new CitationPart(readString(d, "url"), readString(d, "title"), readString(d, "text"), continuation);
            case "image": case "audio": case "video": case "document": case "binary": {
                String mediaType = readString(d, "media_type", "");
                String data = readString(d, "data");
                String url = readString(d, "url");
                String fileId = readString(d, "file_id");
                String pathText = readString(d, "path");
                Path path = pathText == null ? null : (pathText.isEmpty() ? emptyPath() : Path.of(pathText));
                return switch (t) {
                    case "image" -> new ImagePart(mediaType, data, url, fileId, path, readEnum(d, "detail", ImageDetail::fromWire), continuation);
                    case "audio" -> new AudioPart(mediaType, data, url, fileId, path, continuation);
                    case "video" -> new VideoPart(mediaType, data, url, fileId, path, continuation);
                    case "document" -> new DocumentPart(mediaType, data, url, fileId, path, continuation);
                    default -> new BinaryPart(mediaType, data, url, fileId, path, continuation);
                };
            }
            case "tool_call": {
                JsonObject input = readObject(d, "input");
                return new ToolCallPart(requireString(d, "id"), requireString(d, "name"), input == null ? JsonObject.EMPTY : input, continuation);
            }
            case "tool_result": {
                // INV-041: lenient content reading — a string is one TextPart (empty → empty → rejected);
                // a list may mix part objects and scalars; any other shape is empty → rejected.
                JsonValue raw = present(d, "content");
                List<Part> content = new ArrayList<>();
                if (raw instanceof JsonString s) {
                    if (!s.value().isEmpty()) content.add(new TextPart(s.value()));
                } else if (raw instanceof JsonArray a) {
                    for (JsonValue c : a) content.add(c instanceof JsonObject o ? partFromJson(o) : new TextPart(scalarText(c)));
                }
                Boolean isError = readBool(d, "is_error");
                return new ToolResultPart(requireString(d, "id"), content, readString(d, "name"), isError != null && isError, continuation);
            }
            default:
                throw ValidationException.value("unsupported part type: " + t);
        }
    }

    private static Path emptyPath() {
        throw ValidationException.value("path cannot be empty");
    }

    /** Python {@code str(x)} of a JSON scalar (INV-041, INV-047). */
    private static String scalarText(JsonValue v) {
        return switch (v) {
            case JsonString s -> s.value();
            case JsonBool b -> b.value() ? "True" : "False";
            case JsonNull n -> "None";
            case JsonInt i -> i.value().toString();
            case JsonFloat f -> f.toString();
            case JsonArray a -> Json.writePythonStyle(a);
            case JsonObject o -> Json.writePythonStyle(o);
        };
    }

    // ─── messages ────────────────────────────────────────────────────────

    public static JsonObject toJson(Message m) {
        List<JsonValue> parts = new ArrayList<>();
        for (Part p : m.parts()) parts.add(toJson(p));
        JsonBuilder d = new JsonBuilder().put("role", m.role().wire()).put("parts", new JsonArray(parts));
        JsonValue continuation = continuationToJson(m.continuation());
        if (!(continuation instanceof JsonNull)) d.put("continuation", continuation);
        return d.build();
    }

    public static Message messageFromJson(JsonObject d) {
        String role = requireString(d, "role");
        JsonArray raw = readArray(d, "parts");
        List<Part> parts = new ArrayList<>();
        if (raw != null) {
            for (JsonValue p : raw) parts.add(p instanceof JsonObject o ? partFromJson(o) : new TextPart(scalarText(p)));
        }
        if (parts.isEmpty()) throw ValidationException.value("message for role '" + role + "' has no parts");
        return new Message(Role.fromWire(role), parts, continuationFromJson(d));
    }

    // ─── tools ───────────────────────────────────────────────────────────

    public static JsonObject toJson(Tool t) {
        return switch (t) {
            case FunctionTool f -> {
                JsonBuilder d = new JsonBuilder().put("type", "function").put("name", f.name());
                d.putIfNonEmpty("description", f.description());
                // INV-033: parameters is required-with-shape, always emitted.
                d.put("parameters", f.parameters());
                yield d.build();
            }
            case BuiltinTool b -> new JsonBuilder().put("type", "builtin").put("name", b.name()).putIfNonEmpty("config", b.config()).build();
        };
    }

    public static Tool toolFromJson(JsonObject d) {
        if ("builtin".equals(readString(d, "type"))) {
            return new BuiltinTool(requireString(d, "name"), readObject(d, "config"));
        }
        JsonObject params = readObject(d, "parameters");
        return new FunctionTool(requireString(d, "name"), readString(d, "description"), params == null ? FunctionTool.DEFAULT_PARAMETERS : params);
    }

    // ─── config family ───────────────────────────────────────────────────

    public static JsonObject toJson(ToolChoice tc) {
        return new JsonBuilder().put("mode", tc.mode().wire()).putIfNonEmpty("allowed", strings(tc.allowed())).putIfNonEmpty("parallel", bool(tc.parallel())).build();
    }

    public static ToolChoice toolChoiceFromJson(JsonObject d) {
        ToolChoiceMode mode = readEnum(d, "mode", ToolChoiceMode::fromWire);
        return new ToolChoice(mode == null ? ToolChoiceMode.AUTO : mode, readStrings(d, "allowed"), readBool(d, "parallel"));
    }

    public static JsonObject toJson(Reasoning r) {
        return new JsonBuilder().put("effort", r.effort().wire()).putIfNonEmpty("thinking_budget", integer(r.thinkingBudget()))
            .putIfNonEmpty("summary", enumWire(r.summary())).build();
    }

    public static Reasoning reasoningFromJson(JsonObject d) {
        // INV-043: legacy keys — {"enabled": false} → off; "budget" is the fallback for thinking_budget;
        // "adaptive" reads as medium; with effort off, budgets and summary are discarded.
        Boolean enabled = readBool(d, "enabled");
        String effort = readString(d, "effort", Boolean.FALSE.equals(enabled) ? "off" : "medium");
        if (effort.equals("adaptive")) effort = "medium";
        if (effort.equals("off")) return Reasoning.OFF;
        Integer budget = readInt(d, "thinking_budget");
        if (budget == null) budget = readInt(d, "budget");
        return new Reasoning(ReasoningEffort.fromWire(effort), budget, readEnum(d, "summary", ReasoningSummary::fromWire));
    }

    public static JsonObject toJson(CacheConfig c) {
        return new JsonBuilder().put("mode", c.mode().wire()).putIfNonEmpty("retention", enumWire(c.retention()))
            .putIfNonEmpty("key", c.key()).putIfNonEmpty("prefix_until_index", integer(c.prefixUntilIndex()))
            .putIfNonEmpty("prefix", enumWire(c.prefix())).putIfNonEmpty("resource", c.resource()).build();
    }

    public static CacheConfig cacheConfigFromJson(JsonObject d) {
        CacheMode mode = readEnum(d, "mode", CacheMode::fromWire);
        return new CacheConfig(mode == null ? CacheMode.AUTO : mode, readEnum(d, "retention", CacheRetention::fromWire),
            readString(d, "key"), readInt(d, "prefix_until_index"), readEnum(d, "prefix", CachePrefix::fromWire), readString(d, "resource"));
    }

    public static JsonObject toJson(Config c) {
        JsonBuilder d = new JsonBuilder();
        d.putIfNonEmpty("max_tokens", integer(c.maxTokens()));
        d.putIfNonEmpty("temperature", dbl(c.temperature()));
        d.putIfNonEmpty("top_p", dbl(c.topP()));
        d.putIfNonEmpty("top_k", integer(c.topK()));
        d.putIfNonEmpty("stop", strings(c.stop()));
        d.putIfNonEmpty("response_format", c.responseFormat());
        if (c.toolChoice() != null) d.putIfNonEmpty("tool_choice", toJson(c.toolChoice()));
        if (c.reasoning() != null) d.putIfNonEmpty("reasoning", toJson(c.reasoning()));
        if (c.cache() != null) d.putIfNonEmpty("cache", toJson(c.cache()));
        d.putIfNonEmpty("service_tier", c.serviceTier());
        d.putIfNonEmpty("user_id", c.userId());
        d.putIfNonEmpty("store", bool(c.store()));       // false is data (opt-out), emitted
        d.putIfNonEmpty("logprobs", integer(c.logprobs())); // 0 is data (chosen-only), emitted
        d.putIfNonEmpty("extensions", c.extensions());
        return d.build();
    }

    /** INV-042: a present non-object config nest is malformed → TypeError; null reads as absent. */
    private static JsonObject configNest(JsonObject d, String key) {
        JsonValue v = present(d, key);
        if (v == null) return null;
        if (v instanceof JsonObject o) return o;
        throw ValidationException.type("config." + key + " must be a JSON object, got " + v.typeName());
    }

    public static Config configFromJson(JsonObject d) {
        JsonObject toolChoice = configNest(d, "tool_choice");
        JsonObject reasoning = configNest(d, "reasoning");
        JsonObject cache = configNest(d, "cache");
        return new Config(readInt(d, "max_tokens"), readDouble(d, "temperature"), readDouble(d, "top_p"), readInt(d, "top_k"),
            readStrings(d, "stop"), readObject(d, "response_format"),
            toolChoice == null ? null : toolChoiceFromJson(toolChoice),
            reasoning == null ? null : reasoningFromJson(reasoning),
            cache == null ? null : cacheConfigFromJson(cache),
            readString(d, "service_tier"), readString(d, "user_id"), readBool(d, "store"), readInt(d, "logprobs"), readObject(d, "extensions"));
    }

    // ─── logprobs ────────────────────────────────────────────────────────

    private static JsonObject topLogprobToJson(String token, double logprob, List<Integer> bytes, Integer tokenId) {
        JsonBuilder d = new JsonBuilder().put("token", token).put("logprob", new JsonFloat(logprob));
        if (bytes != null) {
            List<JsonValue> bs = new ArrayList<>();
            for (int b : bytes) bs.add(JsonInt.of(b));
            d.put("bytes", new JsonArray(bs));
        }
        if (tokenId != null) d.put("token_id", JsonInt.of(tokenId));
        return d.build();
    }

    public static JsonObject toJson(TopLogprob t) {
        return topLogprobToJson(t.token(), t.logprob(), t.bytes(), t.tokenId());
    }

    public static JsonObject toJson(TokenLogprob t) {
        JsonObject base = topLogprobToJson(t.token(), t.logprob(), t.bytes(), t.tokenId());
        if (t.top().isEmpty()) return base;
        List<JsonValue> top = new ArrayList<>();
        for (TopLogprob x : t.top()) top.add(toJson(x));
        return base.with("top", new JsonArray(top));
    }

    private static List<Integer> bytesFromJson(JsonObject d) {
        JsonArray a = readArray(d, "bytes");
        if (a == null) return null;
        List<Integer> out = new ArrayList<>();
        for (JsonValue v : a) {
            if (v instanceof JsonBool) throw ValidationException.type("bytes must contain non-negative ints");
            out.add(toInt(v, "bytes"));
        }
        return out;
    }

    private static double requireDouble(JsonObject d, String key) {
        JsonValue v = require(d, key);
        if (v instanceof JsonBool) throw ValidationException.type(key + " must be a float");
        Double x = toDouble(v, key);
        if (x == null) throw ValidationException.type(key + " must be a float");
        return x;
    }

    public static TopLogprob topLogprobFromJson(JsonObject d) {
        return new TopLogprob(requireString(d, "token"), requireDouble(d, "logprob"), bytesFromJson(d), readInt(d, "token_id"));
    }

    public static TokenLogprob tokenLogprobFromJson(JsonObject d) {
        List<TopLogprob> top = new ArrayList<>();
        JsonArray raw = readArray(d, "top");
        if (raw != null) for (JsonValue x : raw) top.add(topLogprobFromJson(x.asObject()));
        return new TokenLogprob(requireString(d, "token"), requireDouble(d, "logprob"), bytesFromJson(d), readInt(d, "token_id"), top);
    }

    private static JsonValue logprobsToJson(List<TokenLogprob> logprobs) {
        if (logprobs == null || logprobs.isEmpty()) return JsonNull.INSTANCE;
        List<JsonValue> out = new ArrayList<>();
        for (TokenLogprob t : logprobs) out.add(toJson(t));
        return new JsonArray(out);
    }

    private static List<TokenLogprob> logprobsFromJson(JsonObject d, String key) {
        JsonArray a = readArray(d, key);
        if (a == null) return null;
        List<TokenLogprob> out = new ArrayList<>();
        for (JsonValue x : a) out.add(tokenLogprobFromJson(x.asObject()));
        return out;
    }

    // ─── error detail ────────────────────────────────────────────────────

    public static JsonObject toJson(ErrorDetail e) {
        return new JsonBuilder().put("code", e.code().wire()).putIfNonEmpty("message", e.message()).putIfNonEmpty("provider_code", e.providerCode()).build();
    }

    public static ErrorDetail errorDetailFromJson(JsonObject d) {
        return new ErrorDetail(requireEnum(d, "code", ErrorCode::fromWire), readString(d, "message", ""), readString(d, "provider_code"));
    }

    // ─── deltas ──────────────────────────────────────────────────────────

    /** {@code delta_to_dict} drops only null fields: empty strings are emitted, part_index always (except a null ContinuationDelta index). */
    public static JsonObject toJson(Delta delta) {
        JsonBuilder d = new JsonBuilder().put("type", delta.type().wire());
        switch (delta) {
            case TextDelta x -> {
                d.put("part_index", JsonInt.of(x.partIndex())).put("text", x.text());
                if (!x.logprobs().isEmpty()) d.put("logprobs", logprobsToJson(x.logprobs()));
            }
            case ThinkingDelta x -> d.put("part_index", JsonInt.of(x.partIndex())).put("text", x.text());
            case AudioDelta x -> d.put("part_index", JsonInt.of(x.partIndex())).putIfPresent("data", x.data()).putIfPresent("url", x.url())
                .putIfPresent("file_id", x.fileId()).putIfPresent("media_type", x.mediaType());
            case ImageDelta x -> d.put("part_index", JsonInt.of(x.partIndex())).putIfPresent("data", x.data()).putIfPresent("url", x.url())
                .putIfPresent("file_id", x.fileId()).putIfPresent("media_type", x.mediaType());
            case ToolCallDelta x -> d.put("part_index", JsonInt.of(x.partIndex())).put("input", x.input()).putIfPresent("id", x.id()).putIfPresent("name", x.name());
            case CitationDelta x -> d.put("part_index", JsonInt.of(x.partIndex())).putIfPresent("text", x.text()).putIfPresent("url", x.url()).putIfPresent("title", x.title());
            case ContinuationDelta x -> {
                if (x.partIndex() != null) d.put("part_index", JsonInt.of(x.partIndex()));
                d.put("provider", x.provider()).put("kind", x.kind()).put("data", x.data());
            }
        }
        return d.build();
    }

    public static Delta deltaFromJson(JsonObject d) {
        String t = requireString(d, "type");
        Integer partIndex = readInt(d, "part_index");
        int idx = partIndex == null ? 0 : partIndex;
        switch (t) {
            case "text": {
                List<TokenLogprob> lp = logprobsFromJson(d, "logprobs");
                return new TextDelta(readString(d, "text", ""), idx, lp == null ? List.of() : lp);
            }
            case "thinking": return new ThinkingDelta(readString(d, "text", ""), idx);
            case "audio": return new AudioDelta(readString(d, "data"), readString(d, "url"), readString(d, "file_id"), idx, readString(d, "media_type"));
            case "image": return new ImageDelta(readString(d, "data"), readString(d, "url"), readString(d, "file_id"), idx, readString(d, "media_type"));
            case "tool_call": return new ToolCallDelta(readString(d, "input", ""), idx, readString(d, "id"), readString(d, "name"));
            case "citation": return new CitationDelta(readString(d, "text"), readString(d, "url"), readString(d, "title"), idx);
            case "continuation": {
                JsonObject data = readObject(d, "data");
                return new ContinuationDelta(requireString(d, "provider"), requireString(d, "kind"), data == null ? JsonObject.EMPTY : data, partIndex);
            }
            default: throw ValidationException.value("unsupported delta type: " + t);
        }
    }

    // ─── usage ───────────────────────────────────────────────────────────

    public static JsonObject toJson(Usage u) {
        return new JsonBuilder().putIfNonEmpty("input_tokens", integer(u.inputTokens())).putIfNonEmpty("output_tokens", integer(u.outputTokens()))
            .putIfNonEmpty("total_tokens", integer(u.totalTokens())).putIfNonEmpty("cache_read_tokens", integer(u.cacheReadTokens()))
            .putIfNonEmpty("cache_write_tokens", integer(u.cacheWriteTokens())).putIfNonEmpty("reasoning_tokens", integer(u.reasoningTokens()))
            .putIfNonEmpty("input_audio_tokens", integer(u.inputAudioTokens())).putIfNonEmpty("output_audio_tokens", integer(u.outputAudioTokens())).build();
    }

    public static Usage usageFromJson(JsonObject d) {
        return new Usage(readInt(d, "input_tokens"), readInt(d, "output_tokens"), readInt(d, "total_tokens"), readInt(d, "cache_read_tokens"),
            readInt(d, "cache_write_tokens"), readInt(d, "reasoning_tokens"), readInt(d, "input_audio_tokens"), readInt(d, "output_audio_tokens"));
    }

    // ─── stream events ───────────────────────────────────────────────────

    public static JsonObject toJson(StreamEvent e) {
        return switch (e) {
            case StreamStartEvent s -> new JsonBuilder().put("type", "start").putIfNonEmpty("id", s.id()).putIfNonEmpty("model", s.model()).build();
            case StreamDeltaEvent s -> Json.obj("type", "delta", "delta", toJson(s.delta()));
            case StreamEndEvent s -> new JsonBuilder().put("type", "end").putIfNonEmpty("finish_reason", enumWire(s.finishReason()))
                .putIfNonEmpty("usage", s.usage() == null ? null : toJson(s.usage())).putIfNonEmpty("provider_data", s.providerData()).build();
            case StreamErrorEvent s -> Json.obj("type", "error", "error", toJson(s.error()));
        };
    }

    public static StreamEvent streamEventFromJson(JsonObject d) {
        String t = requireString(d, "type");
        switch (t) {
            case "start": return new StreamStartEvent(readString(d, "id"), readString(d, "model"));
            case "delta": return new StreamDeltaEvent(deltaFromJson(require(d, "delta").asObject()));
            case "end": {
                JsonValue usage = present(d, "usage");
                return new StreamEndEvent(readEnum(d, "finish_reason", FinishReason::fromWire),
                    usage instanceof JsonObject u ? usageFromJson(u) : null, readObject(d, "provider_data"));
            }
            case "error": return new StreamErrorEvent(errorDetailFromJson(require(d, "error").asObject()));
            default: throw ValidationException.value("unsupported stream event type: " + t);
        }
    }

    // ─── request / response ──────────────────────────────────────────────

    private static JsonValue systemToJson(SystemPrompt s) {
        if (s == null) return JsonNull.INSTANCE;
        if (s.isText()) return new JsonString(s.text());
        List<JsonValue> parts = new ArrayList<>();
        for (Part p : s.parts()) parts.add(toJson(p));
        return new JsonArray(parts);
    }

    private static SystemPrompt systemFromJson(JsonObject d) {
        JsonValue raw = present(d, "system");
        if (raw == null) return null;
        if (raw instanceof JsonArray a) {
            List<Part> parts = new ArrayList<>();
            for (JsonValue x : a) parts.add(partFromJson(x.asObject()));
            return SystemPrompt.of(parts);
        }
        if (raw instanceof JsonString s) return SystemPrompt.of(s.value());
        throw ValidationException.type("system must be a string or a list of parts");
    }

    public static JsonObject toJson(Request r) {
        List<JsonValue> messages = new ArrayList<>();
        for (Message m : r.messages()) messages.add(toJson(m));
        List<JsonValue> tools = new ArrayList<>();
        for (Tool t : r.tools()) tools.add(toJson(t));
        return new JsonBuilder().put("model", r.model()).put("messages", new JsonArray(messages)).putIfNonEmpty("system", systemToJson(r.system()))
            .putIfNonEmpty("tools", new JsonArray(tools)).putIfNonEmpty("config", toJson(r.config())).build();
    }

    public static Request requestFromJson(JsonObject d) {
        List<Message> messages = new ArrayList<>();
        for (JsonValue m : require(d, "messages").asArray()) messages.add(messageFromJson(m.asObject()));
        List<Tool> tools = new ArrayList<>();
        JsonArray rawTools = readArray(d, "tools");
        if (rawTools != null) for (JsonValue t : rawTools) tools.add(toolFromJson(t.asObject()));
        JsonObject config = readObject(d, "config");
        return new Request(requireString(d, "model"), messages, systemFromJson(d), tools, config == null ? Config.DEFAULT : configFromJson(config));
    }

    public static JsonObject toJson(Response r) { return toJson(r, false); }

    public static JsonObject toJson(Response r, boolean includeProviderData) {
        JsonBuilder d = new JsonBuilder().putIfNonEmpty("id", r.id()).put("model", r.model()).put("message", toJson(r.message()))
            .put("finish_reason", r.finishReason().wire()).putIfNonEmpty("usage", toJson(r.usage())).putIfNonEmpty("logprobs", logprobsToJson(r.logprobs()));
        if (includeProviderData) d.putIfNonEmpty("provider_data", r.providerData());
        return d.build();
    }

    public static Response responseFromJson(JsonObject d) {
        JsonValue usage = present(d, "usage");
        return new Response(readString(d, "id"), requireString(d, "model"), messageFromJson(require(d, "message").asObject()),
            requireEnum(d, "finish_reason", FinishReason::fromWire), usage instanceof JsonObject u ? usageFromJson(u) : Usage.EMPTY,
            logprobsFromJson(d, "logprobs"), readObject(d, "provider_data"));
    }

    // ─── batch ───────────────────────────────────────────────────────────

    public static JsonObject toJson(BatchRequest b) {
        List<JsonValue> requests = new ArrayList<>();
        for (Request r : b.requests()) requests.add(toJson(r));
        return new JsonBuilder().put("model", b.model()).put("requests", new JsonArray(requests)).putIfNonEmpty("label", b.label()).putIfNonEmpty("extensions", b.extensions()).build();
    }

    public static BatchRequest batchRequestFromJson(JsonObject d) {
        List<Request> requests = new ArrayList<>();
        JsonArray raw = readArray(d, "requests");
        if (raw != null) for (JsonValue r : raw) requests.add(requestFromJson(r.asObject()));
        return new BatchRequest(readString(d, "model"), requests, readString(d, "label"), readObject(d, "extensions"));
    }

    public static JsonObject toJson(BatchJobInfo j) {
        return new JsonBuilder().put("id", j.id()).put("status", j.status().wire()).putIfNonEmpty("label", j.label())
            .putIfNonEmpty("created_at", j.createdAt()).putIfNonEmpty("provider_data", j.providerData()).build();
    }

    public static BatchJobInfo batchJobFromJson(JsonObject d) {
        return new BatchJobInfo(requireString(d, "id"), requireEnum(d, "status", BatchStatus::fromWire), readString(d, "label"), readString(d, "created_at"), readObject(d, "provider_data"));
    }

    public static JsonObject toJson(BatchEntry e) {
        return new JsonBuilder().put("index", JsonInt.of(e.index())).put("outcome", e.outcome().wire())
            .putIfNonEmpty("response", e.response() == null ? null : toJson(e.response(), true))
            .putIfNonEmpty("error", e.error() == null ? null : toJson(e.error())).build();
    }

    public static BatchEntry batchEntryFromJson(JsonObject d) {
        Integer index = readInt(d, "index");
        if (index == null) throw ValidationException.type("missing required field: index");
        JsonObject response = readObject(d, "response");
        JsonObject error = readObject(d, "error");
        return new BatchEntry(index, requireEnum(d, "outcome", BatchOutcome::fromWire), response == null ? null : responseFromJson(response), error == null ? null : errorDetailFromJson(error));
    }

    // ─── files ───────────────────────────────────────────────────────────

    public static JsonObject toJson(FileUploadRequest r) {
        return new JsonBuilder().put("filename", r.filename())
            .putIfNonEmpty("bytes_data", r.bytesData() == null ? null : Base64.getEncoder().encodeToString(r.bytesData()))
            .put("media_type", r.mediaType()).putIfNonEmpty("extensions", r.extensions())
            .putIfNonEmpty("path", r.path() == null ? null : r.path().toString()).build();
    }

    public static FileUploadRequest fileUploadRequestFromJson(JsonObject d) {
        String raw = readString(d, "bytes_data");
        String path = readString(d, "path");
        return new FileUploadRequest(requireString(d, "filename"), raw == null ? null : Base64.getDecoder().decode(raw),
            readString(d, "media_type", "application/octet-stream"), readObject(d, "extensions"), path == null ? null : Path.of(path));
    }

    public static JsonObject toJson(FileInfo f) {
        return new JsonBuilder().put("id", f.id()).putIfNonEmpty("filename", f.filename()).putIfNonEmpty("media_type", f.mediaType())
            .putIfNonEmpty("size_bytes", integer(f.sizeBytes())).putIfNonEmpty("created_at", f.createdAt()).putIfNonEmpty("expires_at", f.expiresAt())
            .put("readiness", f.readiness().wire()).putIfNonEmpty("downloadable", bool(f.downloadable())).putIfNonEmpty("provider_data", f.providerData()).build();
    }

    public static FileInfo fileInfoFromJson(JsonObject d) {
        FileReadiness readiness = readEnum(d, "readiness", FileReadiness::fromWire);
        return new FileInfo(requireString(d, "id"), readString(d, "filename"), readString(d, "media_type"), readLong(d, "size_bytes"),
            readString(d, "created_at"), readString(d, "expires_at"), readiness == null ? FileReadiness.READY : readiness, readBool(d, "downloadable"), readObject(d, "provider_data"));
    }

    public static JsonObject toJson(FilePage p) {
        List<JsonValue> items = new ArrayList<>();
        for (FileInfo f : p.items()) items.add(toJson(f));
        return new JsonBuilder().putIfNonEmpty("items", new JsonArray(items)).putIfNonEmpty("next_cursor", p.nextCursor()).build();
    }

    public static FilePage filePageFromJson(JsonObject d) {
        List<FileInfo> items = new ArrayList<>();
        JsonArray raw = readArray(d, "items");
        if (raw != null) for (JsonValue f : raw) items.add(fileInfoFromJson(f.asObject()));
        return new FilePage(items, readString(d, "next_cursor"));
    }

    // ─── caches ──────────────────────────────────────────────────────────

    public static JsonObject toJson(CacheInfo c) {
        return new JsonBuilder().put("id", c.id()).put("model", c.model()).putIfNonEmpty("tokens", integer(c.tokens()))
            .putIfNonEmpty("created_at", c.createdAt()).putIfNonEmpty("expires_at", c.expiresAt()).putIfNonEmpty("label", c.label())
            .putIfNonEmpty("provider_data", c.providerData()).build();
    }

    public static CacheInfo cacheInfoFromJson(JsonObject d) {
        return new CacheInfo(requireString(d, "id"), requireString(d, "model"), readInt(d, "tokens"), readString(d, "created_at"),
            readString(d, "expires_at"), readString(d, "label"), readObject(d, "provider_data"));
    }

    public static JsonObject toJson(CachePage p) {
        List<JsonValue> items = new ArrayList<>();
        for (CacheInfo c : p.items()) items.add(toJson(c));
        return new JsonBuilder().putIfNonEmpty("items", new JsonArray(items)).putIfNonEmpty("next_cursor", p.nextCursor()).build();
    }

    public static CachePage cachePageFromJson(JsonObject d) {
        List<CacheInfo> items = new ArrayList<>();
        JsonArray raw = readArray(d, "items");
        if (raw != null) for (JsonValue c : raw) items.add(cacheInfoFromJson(c.asObject()));
        return new CachePage(items, readString(d, "next_cursor"));
    }

    public static JsonObject toJson(CachedPrefix c) {
        return new JsonBuilder().put("prefix", toJson(c.prefix())).putIfNonEmpty("resource", c.resource() == null ? null : toJson(c.resource())).build();
    }

    public static CachedPrefix cachedPrefixFromJson(JsonObject d) {
        JsonObject resource = readObject(d, "resource");
        return new CachedPrefix(requestFromJson(require(d, "prefix").asObject()), resource == null ? null : cacheInfoFromJson(resource));
    }

    // ─── media generation ────────────────────────────────────────────────

    private static JsonValue imagesToJson(List<ImagePart> images) {
        if (images.isEmpty()) return JsonNull.INSTANCE;
        List<JsonValue> out = new ArrayList<>();
        for (ImagePart p : images) out.add(toJson(p));
        return new JsonArray(out);
    }

    private static List<ImagePart> imagesFromJson(JsonObject d, String key) {
        List<ImagePart> out = new ArrayList<>();
        JsonArray raw = readArray(d, key);
        if (raw != null) {
            for (JsonValue x : raw) {
                Part p = partFromJson(x.asObject());
                if (!(p instanceof ImagePart img)) throw ValidationException.type(key + " must contain ImagePart objects");
                out.add(img);
            }
        }
        return out;
    }

    public static JsonObject toJson(ImageGenerationRequest r) {
        return new JsonBuilder().put("model", r.model()).put("prompt", r.prompt()).putIfNonEmpty("size", r.size())
            .putIfNonEmpty("images", imagesToJson(r.images())).putIfNonEmpty("extensions", r.extensions()).build();
    }

    public static ImageGenerationRequest imageGenerationRequestFromJson(JsonObject d) {
        return new ImageGenerationRequest(requireString(d, "model"), requireString(d, "prompt"), readString(d, "size"), imagesFromJson(d, "images"), readObject(d, "extensions"));
    }

    public static JsonObject toJson(ImageGenerationResponse r) {
        List<JsonValue> images = new ArrayList<>();
        for (ImagePart p : r.images()) images.add(toJson(p));
        return new JsonBuilder().put("images", new JsonArray(images)).putIfNonEmpty("text", r.text()).putIfNonEmpty("id", r.id())
            .putIfNonEmpty("model", r.model()).putIfNonEmpty("usage", toJson(r.usage())).putIfNonEmpty("provider_data", r.providerData()).build();
    }

    public static ImageGenerationResponse imageGenerationResponseFromJson(JsonObject d) {
        JsonObject usage = readObject(d, "usage");
        return new ImageGenerationResponse(imagesFromJson(d, "images"), readString(d, "text"), readString(d, "id"), readString(d, "model"),
            usage == null ? Usage.EMPTY : usageFromJson(usage), readObject(d, "provider_data"));
    }

    public static JsonObject toJson(SpeechGenerationRequest r) {
        return new JsonBuilder().put("model", r.model()).put("prompt", r.prompt()).putIfNonEmpty("voice", r.voice())
            .putIfNonEmpty("format", r.format()).putIfNonEmpty("extensions", r.extensions()).build();
    }

    public static SpeechGenerationRequest speechGenerationRequestFromJson(JsonObject d) {
        return new SpeechGenerationRequest(requireString(d, "model"), requireString(d, "prompt"), readString(d, "voice"), readString(d, "format"), readObject(d, "extensions"));
    }

    public static JsonObject toJson(SpeechGenerationResponse r) {
        return new JsonBuilder().put("audio", toJson(r.audio())).putIfNonEmpty("id", r.id()).putIfNonEmpty("model", r.model())
            .putIfNonEmpty("usage", toJson(r.usage())).putIfNonEmpty("provider_data", r.providerData()).build();
    }

    public static SpeechGenerationResponse speechGenerationResponseFromJson(JsonObject d) {
        Part audio = partFromJson(require(d, "audio").asObject());
        if (!(audio instanceof AudioPart a)) throw ValidationException.type("audio must be an AudioPart");
        JsonObject usage = readObject(d, "usage");
        return new SpeechGenerationResponse(a, readString(d, "id"), readString(d, "model"), usage == null ? Usage.EMPTY : usageFromJson(usage), readObject(d, "provider_data"));
    }

    public static JsonObject toJson(VideoGenerationRequest r) {
        return new JsonBuilder().put("model", r.model()).put("prompt", r.prompt()).putIfNonEmpty("seconds", integer(r.seconds()))
            .putIfNonEmpty("images", imagesToJson(r.images())).putIfNonEmpty("extensions", r.extensions()).build();
    }

    public static VideoGenerationRequest videoGenerationRequestFromJson(JsonObject d) {
        JsonValue seconds = present(d, "seconds");
        if (seconds instanceof JsonFloat || seconds instanceof JsonBool) throw ValidationException.value("VideoGenerationRequest.seconds must be a positive int");
        return new VideoGenerationRequest(requireString(d, "model"), requireString(d, "prompt"), readInt(d, "seconds"), imagesFromJson(d, "images"), readObject(d, "extensions"));
    }

    public static JsonObject toJson(VideoJobInfo j) {
        return new JsonBuilder().put("id", j.id()).put("status", j.status().wire()).putIfNonEmpty("progress", integer(j.progress()))
            .putIfNonEmpty("created_at", j.createdAt()).putIfNonEmpty("model", j.model()).putIfNonEmpty("provider_data", j.providerData()).build();
    }

    public static VideoJobInfo videoJobFromJson(JsonObject d) {
        JsonValue progress = present(d, "progress");
        if (progress instanceof JsonFloat || progress instanceof JsonBool) throw ValidationException.value("VideoJobInfo.progress must be an int percentage 0-100");
        return new VideoJobInfo(requireString(d, "id"), requireEnum(d, "status", VideoStatus::fromWire), readInt(d, "progress"),
            readString(d, "created_at"), readString(d, "model"), readObject(d, "provider_data"));
    }

    // ─── model info ──────────────────────────────────────────────────────

    public static JsonObject toJson(InferencePricing p) {
        return new JsonBuilder().putIfNonEmpty("input_per_million", dbl(p.inputPerMillion())).putIfNonEmpty("output_per_million", dbl(p.outputPerMillion()))
            .putIfNonEmpty("cache_read_per_million", dbl(p.cacheReadPerMillion())).putIfNonEmpty("cache_write_per_million", dbl(p.cacheWritePerMillion()))
            .putIfNonEmpty("currency", p.currency()).putIfNonEmpty("dimensions", p.dimensions()).build();
    }

    public static InferencePricing inferencePricingFromJson(JsonObject d) {
        return new InferencePricing(readDouble(d, "input_per_million"), readDouble(d, "output_per_million"), readDouble(d, "cache_read_per_million"),
            readDouble(d, "cache_write_per_million"), readString(d, "currency", "USD"), readObject(d, "dimensions"));
    }

    public static JsonObject toJson(InferenceModelInfo i) {
        return new JsonBuilder().putIfNonEmpty("input_modalities", strings(i.inputModalities())).putIfNonEmpty("output_modalities", strings(i.outputModalities()))
            .putIfNonEmpty("context_window", integer(i.contextWindow())).putIfNonEmpty("max_output_tokens", integer(i.maxOutputTokens()))
            .putIfNonEmpty("supports_reasoning", i.supportsReasoning() ? JsonBool.TRUE : null)
            .putIfNonEmpty("reasoning_efforts", strings(i.reasoningEfforts()))
            .putIfNonEmpty("pricing", i.pricing() == null ? null : toJson(i.pricing())).putIfNonEmpty("extensions", i.extensions()).build();
    }

    public static InferenceModelInfo inferenceModelInfoFromJson(JsonObject d) {
        JsonObject pricing = readObject(d, "pricing");
        Boolean sr = readBool(d, "supports_reasoning");
        return new InferenceModelInfo(d.has("input_modalities") ? readStrings(d, "input_modalities") : null,
            d.has("output_modalities") ? readStrings(d, "output_modalities") : null, readInt(d, "context_window"), readInt(d, "max_output_tokens"),
            sr != null && sr, readStrings(d, "reasoning_efforts"), pricing == null ? null : inferencePricingFromJson(pricing), readObject(d, "extensions"));
    }

    public static JsonObject toJson(ModelOrigin o) {
        return new JsonBuilder().putIfNonEmpty("type", o.type()).putIfNonEmpty("id", o.id()).putIfNonEmpty("base_model", o.baseModel())
            .putIfNonEmpty("provider_data", o.providerData()).build();
    }

    public static ModelOrigin modelOriginFromJson(JsonObject d) {
        return new ModelOrigin(readString(d, "type", "provider"), readString(d, "id"), readString(d, "base_model"), readObject(d, "provider_data"));
    }

    public static JsonObject toJson(ModelInfo m) {
        JsonObject origin = toJson(m.origin());
        if (origin.equals(Json.obj("type", "provider"))) origin = JsonObject.EMPTY; // the default origin carries no information
        return new JsonBuilder().put("id", m.id()).put("provider", m.provider()).put("api_family", m.apiFamily())
            .putIfNonEmpty("aliases", strings(m.aliases())).putIfNonEmpty("origin", origin)
            .putIfNonEmpty("inference", m.inference() == null ? null : toJson(m.inference())).putIfNonEmpty("extensions", m.extensions()).build();
    }

    public static ModelInfo modelInfoFromJson(JsonObject d) {
        JsonObject origin = readObject(d, "origin");
        JsonObject inference = readObject(d, "inference");
        return new ModelInfo(requireString(d, "id"), requireString(d, "provider"), requireString(d, "api_family"), readStrings(d, "aliases"),
            origin == null ? ModelOrigin.PROVIDER : modelOriginFromJson(origin), inference == null ? null : inferenceModelInfoFromJson(inference), readObject(d, "extensions"));
    }

    // ─── audio / live ────────────────────────────────────────────────────

    public static JsonObject toJson(AudioFormat af) {
        return Json.obj("encoding", af.encoding().wire(), "sample_rate", af.sampleRate(), "channels", af.channels());
    }

    public static AudioFormat audioFormatFromJson(JsonObject d) {
        Integer rate = readInt(d, "sample_rate");
        if (rate == null) throw ValidationException.type("missing required field: sample_rate");
        Integer channels = readInt(d, "channels");
        return new AudioFormat(requireEnum(d, "encoding", AudioEncoding::fromWire), rate, channels == null ? 1 : channels);
    }

    public static JsonObject toJson(LiveConfig lc) {
        List<JsonValue> tools = new ArrayList<>();
        for (Tool t : lc.tools()) tools.add(toJson(t));
        return new JsonBuilder().put("model", lc.model()).putIfNonEmpty("system", systemToJson(lc.system())).putIfNonEmpty("tools", new JsonArray(tools))
            .putIfNonEmpty("voice", lc.voice()).putIfNonEmpty("input_format", lc.inputFormat() == null ? null : toJson(lc.inputFormat()))
            .putIfNonEmpty("output_format", lc.outputFormat() == null ? null : toJson(lc.outputFormat())).putIfNonEmpty("extensions", lc.extensions()).build();
    }

    public static LiveConfig liveConfigFromJson(JsonObject d) {
        List<Tool> tools = new ArrayList<>();
        JsonArray rawTools = readArray(d, "tools");
        if (rawTools != null) for (JsonValue t : rawTools) tools.add(toolFromJson(t.asObject()));
        JsonValue in = present(d, "input_format");
        JsonValue out = present(d, "output_format");
        return new LiveConfig(requireString(d, "model"), systemFromJson(d), tools, readString(d, "voice"),
            in instanceof JsonObject i ? audioFormatFromJson(i) : null, out instanceof JsonObject o ? audioFormatFromJson(o) : null, readObject(d, "extensions"));
    }

    /** Client events emit ALL their fields verbatim (no cleaning), including {@code turn_complete: false}. */
    public static JsonObject toJson(LiveClientEvent e) {
        return switch (e) {
            case LiveClientEvent.Turn x -> {
                List<JsonValue> parts = new ArrayList<>();
                for (Part p : x.parts()) parts.add(toJson(p));
                yield Json.obj("type", "turn", "parts", new JsonArray(parts), "turn_complete", x.turnComplete());
            }
            case LiveClientEvent.Audio x -> Json.obj("type", "audio", "data", x.data(), "media_type", x.mediaType());
            case LiveClientEvent.Image x -> Json.obj("type", "image", "data", x.data(), "media_type", x.mediaType());
            case LiveClientEvent.Text x -> Json.obj("type", "text", "text", x.text());
            case LiveClientEvent.ToolResult x -> {
                List<JsonValue> parts = new ArrayList<>();
                for (Part p : x.content()) parts.add(toJson(p));
                yield Json.obj("type", "tool_result", "id", x.id(), "content", new JsonArray(parts));
            }
            case LiveClientEvent.Interrupt x -> Json.obj("type", "interrupt");
            case LiveClientEvent.EndAudio x -> Json.obj("type", "end_audio");
        };
    }

    public static LiveClientEvent liveClientEventFromJson(JsonObject d) {
        String t = requireString(d, "type");
        switch (t) {
            case "turn": {
                List<Part> parts = new ArrayList<>();
                JsonArray raw = readArray(d, "parts");
                if (raw != null) for (JsonValue p : raw) parts.add(partFromJson(p.asObject()));
                Boolean tc = readBool(d, "turn_complete");
                return new LiveClientEvent.Turn(parts, tc == null || tc);
            }
            case "audio": return new LiveClientEvent.Audio(requireString(d, "data"), readString(d, "media_type", LiveClientEvent.Audio.DEFAULT_MEDIA_TYPE));
            case "image": return new LiveClientEvent.Image(requireString(d, "data"), readString(d, "media_type", LiveClientEvent.Image.DEFAULT_MEDIA_TYPE));
            case "text": return new LiveClientEvent.Text(readString(d, "text", ""));
            case "tool_result": {
                List<Part> parts = new ArrayList<>();
                JsonArray raw = readArray(d, "content");
                if (raw != null) for (JsonValue p : raw) parts.add(partFromJson(p.asObject()));
                return new LiveClientEvent.ToolResult(requireString(d, "id"), parts);
            }
            case "interrupt": return new LiveClientEvent.Interrupt();
            case "end_audio": return new LiveClientEvent.EndAudio();
            default: throw ValidationException.value("unsupported live client event type: " + t);
        }
    }

    public static JsonObject toJson(LiveServerEvent e) {
        return switch (e) {
            case LiveServerEvent.Audio x -> new JsonBuilder().put("type", "audio").put("data", x.data()).putIfNonEmpty("media_type", x.mediaType()).build();
            case LiveServerEvent.Text x -> Json.obj("type", "text", "text", x.text());
            case LiveServerEvent.ToolCall x -> Json.obj("type", "tool_call", "id", x.id(), "name", x.name(), "input", x.input());
            case LiveServerEvent.ToolCallDelta x -> new JsonBuilder().put("type", "tool_call_delta").putIfNonEmpty("id", x.id()).putIfNonEmpty("name", x.name())
                .putIfNonEmpty("input_delta", x.inputDelta()).build();
            case LiveServerEvent.Interrupted x -> Json.obj("type", "interrupted");
            case LiveServerEvent.TurnEnd x -> new JsonBuilder().put("type", "turn_end").putIfNonEmpty("usage", toJson(x.usage())).build();
            case LiveServerEvent.UsageEvent x -> new JsonBuilder().put("type", "usage").putIfNonEmpty("usage", toJson(x.usage())).build();
            case LiveServerEvent.Error x -> Json.obj("type", "error", "error", toJson(x.error()));
        };
    }

    public static LiveServerEvent liveServerEventFromJson(JsonObject d) {
        String t = requireString(d, "type");
        switch (t) {
            case "audio": return new LiveServerEvent.Audio(requireString(d, "data"), readString(d, "media_type"));
            case "text": return new LiveServerEvent.Text(readString(d, "text", ""));
            case "tool_call": {
                JsonObject input = readObject(d, "input");
                return new LiveServerEvent.ToolCall(requireString(d, "id"), requireString(d, "name"), input == null ? JsonObject.EMPTY : input);
            }
            case "tool_call_delta": return new LiveServerEvent.ToolCallDelta(readString(d, "input_delta", ""), readString(d, "id"), readString(d, "name"));
            case "interrupted": return new LiveServerEvent.Interrupted();
            case "turn_end": { JsonObject u = readObject(d, "usage"); return new LiveServerEvent.TurnEnd(u == null ? Usage.EMPTY : usageFromJson(u)); }
            case "usage": { JsonObject u = readObject(d, "usage"); return new LiveServerEvent.UsageEvent(u == null ? Usage.EMPTY : usageFromJson(u)); }
            case "error": return new LiveServerEvent.Error(errorDetailFromJson(require(d, "error").asObject()));
            default: throw ValidationException.value("unsupported live server event type: " + t);
        }
    }

    // ─── credential (AUTH-2) ─────────────────────────────────────────────

    public static JsonObject toJson(Credential c) {
        return switch (c) {
            case Credential.ApiKey k -> Json.obj("kind", "api_key", "value", k.value());
            case Credential.BearerToken b -> new JsonBuilder().put("kind", "bearer_token").put("value", b.value())
                .putIfPresent("expires_at", b.expiresAt() == null ? null : Rfc3339.format(b.expiresAt())).build();
            case Credential.AwsCredentials a -> new JsonBuilder().put("kind", "aws").put("access_key_id", a.accessKeyId())
                .put("secret_access_key", a.secretAccessKey()).putIfPresent("session_token", a.sessionToken())
                .putIfPresent("expires_at", a.expiresAt() == null ? null : Rfc3339.format(a.expiresAt())).build();
        };
    }

    public static Credential credentialFromJson(JsonObject d) {
        String kind = readString(d, "kind");
        String expiresText = readString(d, "expires_at");
        java.time.Instant expires = expiresText == null ? null : Rfc3339.parse(expiresText);
        if ("api_key".equals(kind)) return new Credential.ApiKey(requireString(d, "value"));
        if ("bearer_token".equals(kind)) return new Credential.BearerToken(requireString(d, "value"), expires);
        if ("aws".equals(kind)) {
            return new Credential.AwsCredentials(requireString(d, "access_key_id"), requireString(d, "secret_access_key"), readString(d, "session_token"), expires);
        }
        throw ValidationException.value("unknown credential kind");
    }

    // ─── the vet kind table (harness/PROTOCOL.md § Serde kinds) ──────────

    /** One serde pair, keyed by the protocol's kind string. */
    public record Kind(Function<JsonObject, Object> fromJson, Function<Object, JsonObject> toJson) {
        public JsonObject roundtrip(JsonObject value) { return toJson.apply(fromJson.apply(value)); }
    }

    private static final Map<String, Kind> KINDS = new LinkedHashMap<>();

    private static <T> void kind(String name, Class<T> cls, Function<JsonObject, T> from, Function<T, JsonObject> to) {
        KINDS.put(name, new Kind(from::apply, o -> to.apply(cls.cast(o))));
    }

    static {
        kind("part", Part.class, Canonical::partFromJson, Canonical::toJson);
        kind("message", Message.class, Canonical::messageFromJson, Canonical::toJson);
        kind("tool", Tool.class, Canonical::toolFromJson, Canonical::toJson);
        kind("tool_choice", ToolChoice.class, Canonical::toolChoiceFromJson, Canonical::toJson);
        kind("reasoning", Reasoning.class, Canonical::reasoningFromJson, Canonical::toJson);
        kind("config", Config.class, Canonical::configFromJson, Canonical::toJson);
        kind("cache_config", CacheConfig.class, Canonical::cacheConfigFromJson, Canonical::toJson);
        kind("cache_info", CacheInfo.class, Canonical::cacheInfoFromJson, Canonical::toJson);
        kind("cache_page", CachePage.class, Canonical::cachePageFromJson, Canonical::toJson);
        kind("cached_prefix", CachedPrefix.class, Canonical::cachedPrefixFromJson, Canonical::toJson);
        kind("token_logprob", TokenLogprob.class, Canonical::tokenLogprobFromJson, Canonical::toJson);
        kind("continuation_state", ContinuationState.class, Canonical::continuationStateFromJson, Canonical::toJson);
        kind("error_detail", ErrorDetail.class, Canonical::errorDetailFromJson, Canonical::toJson);
        kind("delta", Delta.class, Canonical::deltaFromJson, Canonical::toJson);
        kind("usage", Usage.class, Canonical::usageFromJson, Canonical::toJson);
        kind("credential", Credential.class, Canonical::credentialFromJson, Canonical::toJson);
        kind("stream_event", StreamEvent.class, Canonical::streamEventFromJson, Canonical::toJson);
        kind("request", Request.class, Canonical::requestFromJson, Canonical::toJson);
        kind("response", Response.class, Canonical::responseFromJson, r -> toJson(r, false));
        kind("model_info", ModelInfo.class, Canonical::modelInfoFromJson, Canonical::toJson);
        kind("batch_request", BatchRequest.class, Canonical::batchRequestFromJson, Canonical::toJson);
        kind("batch_job", BatchJobInfo.class, Canonical::batchJobFromJson, Canonical::toJson);
        kind("batch_entry", BatchEntry.class, Canonical::batchEntryFromJson, Canonical::toJson);
        kind("file_upload_request", FileUploadRequest.class, Canonical::fileUploadRequestFromJson, Canonical::toJson);
        kind("file_info", FileInfo.class, Canonical::fileInfoFromJson, Canonical::toJson);
        kind("file_page", FilePage.class, Canonical::filePageFromJson, Canonical::toJson);
        kind("image_generation_request", ImageGenerationRequest.class, Canonical::imageGenerationRequestFromJson, Canonical::toJson);
        kind("image_generation_response", ImageGenerationResponse.class, Canonical::imageGenerationResponseFromJson, Canonical::toJson);
        kind("speech_generation_request", SpeechGenerationRequest.class, Canonical::speechGenerationRequestFromJson, Canonical::toJson);
        kind("speech_generation_response", SpeechGenerationResponse.class, Canonical::speechGenerationResponseFromJson, Canonical::toJson);
        kind("video_generation_request", VideoGenerationRequest.class, Canonical::videoGenerationRequestFromJson, Canonical::toJson);
        kind("video_job", VideoJobInfo.class, Canonical::videoJobFromJson, Canonical::toJson);
        kind("audio_format", AudioFormat.class, Canonical::audioFormatFromJson, Canonical::toJson);
        kind("live_config", LiveConfig.class, Canonical::liveConfigFromJson, Canonical::toJson);
        kind("live_client_event", LiveClientEvent.class, Canonical::liveClientEventFromJson, Canonical::toJson);
        kind("live_server_event", LiveServerEvent.class, Canonical::liveServerEventFromJson, Canonical::toJson);
    }

    /** The serde pair for a protocol kind, or a {@code ValueError} for an unknown kind. */
    public static Kind kind(String name) {
        Kind k = KINDS.get(name);
        if (k == null) throw ValidationException.value("unknown kind: " + name);
        return k;
    }

    public static List<String> kinds() { return List.copyOf(KINDS.keySet()); }

    /** {@code to_json(from_json(value))} with no cleaning (the {@code serde_roundtrip} op). */
    public static JsonObject roundtrip(String kind, JsonObject value) {
        return kind(kind).roundtrip(value);
    }
}
