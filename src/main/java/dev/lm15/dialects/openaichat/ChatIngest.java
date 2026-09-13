package dev.lm15.dialects.openaichat;

import dev.lm15.compat.OpenAIChatCompat;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBool;
import dev.lm15.json.JsonNull;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.serde.Canonical;
import dev.lm15.types.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * MAP-12: a Chat Completions request body read INTO a canonical Request
 * under ONE preset's spellings (the same resolved compat the builder writes
 * with). Every wire key has exactly one verdict
 * (lm15-contract/tools/openai-chat-ingest-verdicts.json, copied here as
 * data): map, extensions, refuse, call-mode, or default. A key with no
 * verdict is refused: lm15 never drops a key silently (port.md rule 4).
 * Malformed input (a wrong JSON type, a missing required key, an unparsable
 * arguments string) is a ValueError / TypeError, like serde.
 */
final class ChatIngest {
    private ChatIngest() {}

    /** Top-level keys forwarded verbatim into config.extensions (the builder re-emits them, so they round-trip). */
    static final Set<String> EXTENSIONS_KEYS = Set.of("seed", "logit_bias", "presence_penalty", "frequency_penalty", "metadata", "verbosity", "moderation", "provider");

    /** Top-level keys refused with the reason a canonical Request cannot carry them. */
    static final Map<String, String> REFUSED_KEYS;

    /** Call-mode keys: they say HOW the request is sent, not WHAT is asked; read and dropped (the one drop MAP-12 makes). */
    static final Set<String> CALL_MODE_KEYS = Set.of("stream", "stream_options");

    /** Keys the config decoder consumes itself. */
    static final Set<String> CONFIG_KEYS = Set.of(
        "model", "messages", "tools", "tool_choice", "parallel_tool_calls",
        "max_completion_tokens", "max_tokens", "temperature", "top_p", "stop",
        "logprobs", "top_logprobs", "response_format", "service_tier", "store",
        "user", "safety_identifier", "user_id",
        "reasoning_effort", "reasoning", "thinking", "enable_thinking", "chat_template_kwargs", "reasoning_format",
        "prompt_cache_key", "prompt_cache_retention", "prompt_cache_options");

    /** Assistant-row keys that are a client library's object model (litellm), not the wire: empty reads as absent. */
    static final Set<String> CLIENT_OBJECT_KEYS = Set.of("provider_specific_fields", "thinking_blocks", "images");

    static final Map<String, String> AUDIO_MEDIA_TYPES = Map.of("wav", "audio/wav", "mp3", "audio/mpeg");

    static {
        LinkedHashMap<String, String> r = new LinkedHashMap<>();
        r.put("n", "lm15 reads one choice per response; n>1 would silently lose choices — fan out in the caller");
        r.put("functions", "the deprecated function-calling shape; declare tools with {type: function, function: {...}}");
        r.put("function_call", "the deprecated function-calling shape; use tool_choice");
        r.put("audio", "audio output parameters have no canonical slot on the chat surface");
        r.put("modalities", "output modality selection has no canonical slot on the chat surface");
        r.put("prediction", "predicted-output content has no canonical slot");
        r.put("web_search_options", "a server-executed search the chat dialect cannot map to parts (MAP-1); the Responses dialect carries web_search as a BuiltinTool");
        r.put("top_k", "the Chat Completions wire has no top_k (the builder raises on Config.top_k for the same reason); servers that take it do so through extensions");
        REFUSED_KEYS = Map.copyOf(r);
    }

    // ─── small helpers ───

    private static UnsupportedFeatureError unsupported(String provider, String what, String why) {
        return new UnsupportedFeatureError(provider + ": " + what + " cannot be carried by a canonical Request — " + why, ErrorMeta.of(provider));
    }

    private static String str(JsonValue value, String where) {
        if (value instanceof JsonString s) return s.value();
        throw ValidationException.type(where + " must be a string, got " + ChatErrors.pyTypeName(value));
    }

    private static JsonObject object(JsonValue value, String where) {
        if (value instanceof JsonObject o) return o;
        throw ValidationException.type(where + " must be a JSON object, got " + ChatErrors.pyTypeName(value));
    }

    /** An unlisted key inside a block or object is a refusal, never a drop. */
    private static void onlyKeys(String provider, JsonObject obj, Set<String> allowed, String where) {
        TreeSet<String> extra = new TreeSet<>(obj.keys());
        extra.removeAll(allowed);
        if (!extra.isEmpty()) throw unsupported(provider, where + " key '" + extra.first() + "'", "no canonical slot for it");
    }

    /** Present and not JSON null ({@code x is not None}). */
    private static boolean present(JsonObject o, String key) {
        return o.hasNonNull(key);
    }

    /** {@code data:<media_type>;base64,<payload>} → [media_type, payload]; the inverse of media_data_uri. */
    private static String[] dataUri(String value, String where) {
        if (!value.startsWith("data:")) throw ValidationException.value(where + " must be a base64 data URI");
        String rest = value.substring(5);
        int comma = rest.indexOf(',');
        if (comma < 0) throw ValidationException.value(where + " must be a base64 data URI (data:<media-type>;base64,<payload>)");
        String head = rest.substring(0, comma);
        String payload = rest.substring(comma + 1);
        if (!head.endsWith(";base64") || payload.isEmpty()) {
            throw ValidationException.value(where + " must be a base64 data URI (data:<media-type>;base64,<payload>)");
        }
        String mediaType = head.substring(0, head.length() - ";base64".length());
        if (mediaType.isEmpty()) throw ValidationException.value(where + " data URI has no media type");
        return new String[] {mediaType, payload};
    }

    // ─── content blocks ───

    /** Inverse of the builder's image block: a data URI → inline data with its media type; another URL stays a URL (type guessed from the path). */
    private static ImagePart imageBlock(String provider, JsonObject block, String where) {
        onlyKeys(provider, block, Set.of("type", "image_url", "prompt_cache_breakpoint"), where);
        JsonObject spec = object(block.get("image_url"), where + ".image_url");
        onlyKeys(provider, spec, Set.of("url", "detail"), where + ".image_url");
        String url = str(spec.get("url"), where + ".image_url.url");
        ImageDetail detail = null;
        if (present(spec, "detail")) detail = ImageDetail.fromWire(str(spec.get("detail"), where + ".image_url.detail"));
        if (url.startsWith("data:")) {
            String[] parsed = dataUri(url, where + ".image_url.url");
            return Parts.image(Parts.Address.ofData(parsed[1]), parsed[0], detail);
        }
        String guessed = MediaTypes.guessImageType(url);
        return Parts.image(Parts.Address.ofUrl(url), guessed, detail);
    }

    private static TextPart textBlock(String provider, JsonObject block, String where) {
        onlyKeys(provider, block, Set.of("type", "text", "prompt_cache_breakpoint"), where);
        return new TextPart(str(block.get("text"), where + ".text"));
    }

    private static boolean hasBreakpoint(JsonObject block, String where) {
        JsonValue mark = block.opt("prompt_cache_breakpoint");
        if (mark == null) return false;
        JsonObject spec = object(mark, where + ".prompt_cache_breakpoint");
        if (!spec.equals(Json.obj("mode", "explicit"))) throw ValidationException.value(where + ".prompt_cache_breakpoint must be {\"mode\": \"explicit\"}");
        JsonValue type = block.get("type");
        if (!(type instanceof JsonString s && s.value().equals("text"))) {
            // The builder places a mark on a text block only (CacheConfig table).
            throw ValidationException.value(where + ": a prompt_cache_breakpoint rides on a text block, not " + ChatErrors.pyStr(type));
        }
        return true;
    }

    private record Blocks(List<Part> parts, boolean breakpointAtEnd) {}

    /** A row's content → parts, plus whether its LAST block carries the prompt-cache breakpoint. Which block types a role admits follows the wire's own schema. */
    private static Blocks contentBlocks(String provider, JsonValue content, String role, String where) {
        if (content instanceof JsonString s) return new Blocks(List.of(new TextPart(s.value())), false);
        if (!(content instanceof JsonArray arr)) throw ValidationException.type(where + ".content must be a string or an array of content parts");
        List<Part> parts = new ArrayList<>();
        boolean breakpointAtEnd = false;
        for (int index = 0; index < arr.size(); index++) {
            String blockWhere = where + ".content[" + index + "]";
            JsonObject block = object(arr.get(index), blockWhere);
            String kind = block.get("type") instanceof JsonString s ? s.value() : null;
            boolean marked = hasBreakpoint(block, blockWhere);
            if (marked && index != arr.size() - 1) {
                throw ValidationException.value(blockWhere + ": a prompt_cache_breakpoint marks the end of a message; it must be on the last block");
            }
            breakpointAtEnd = breakpointAtEnd || marked;
            if ("text".equals(kind)) {
                parts.add(textBlock(provider, block, blockWhere));
            } else if ("image_url".equals(kind) && (role.equals("user") || role.equals("tool"))) {
                parts.add(imageBlock(provider, block, blockWhere));
            } else if ("input_audio".equals(kind) && role.equals("user")) {
                onlyKeys(provider, block, Set.of("type", "input_audio", "prompt_cache_breakpoint"), blockWhere);
                JsonObject spec = object(block.get("input_audio"), blockWhere + ".input_audio");
                onlyKeys(provider, spec, Set.of("data", "format"), blockWhere + ".input_audio");
                String fmt = str(spec.get("format"), blockWhere + ".input_audio.format");
                String mediaType = AUDIO_MEDIA_TYPES.get(fmt);
                if (mediaType == null) throw ValidationException.value(blockWhere + ".input_audio.format must be one of " + new TreeSet<>(AUDIO_MEDIA_TYPES.keySet()));
                parts.add(Parts.audio(Parts.Address.ofData(str(spec.get("data"), blockWhere + ".input_audio.data")), mediaType));
            } else if ("file".equals(kind) && role.equals("user")) {
                onlyKeys(provider, block, Set.of("type", "file", "prompt_cache_breakpoint"), blockWhere);
                JsonObject spec = object(block.get("file"), blockWhere + ".file");
                onlyKeys(provider, spec, Set.of("file_data", "file_id", "filename"), blockWhere + ".file");
                if (present(spec, "filename")) throw unsupported(provider, blockWhere + ".file.filename", "DocumentPart has no filename slot");
                if (present(spec, "file_id") && !present(spec, "file_data")) {
                    parts.add(Parts.document(Parts.Address.ofFileId(str(spec.get("file_id"), blockWhere + ".file.file_id"))));
                } else if (present(spec, "file_data") && !present(spec, "file_id")) {
                    String[] parsed = dataUri(str(spec.get("file_data"), blockWhere + ".file.file_data"), blockWhere + ".file.file_data");
                    parts.add(Parts.document(Parts.Address.ofData(parsed[1]), parsed[0]));
                } else {
                    throw ValidationException.value(blockWhere + ".file needs exactly one of file_data / file_id");
                }
            } else if ("refusal".equals(kind) && role.equals("assistant")) {
                onlyKeys(provider, block, Set.of("type", "refusal"), blockWhere);
                parts.add(new RefusalPart(str(block.get("refusal"), blockWhere + ".refusal")));
            } else {
                throw unsupported(provider, blockWhere + " of type " + ChatErrors.pyStr(block.get("type") == null ? null : block.get("type")) + " in a " + role + " message",
                    "no canonical part for that block on this wire (a part is not a knob: there is no extensions door for content)");
            }
        }
        return new Blocks(parts, breakpointAtEnd);
    }

    /** A dict whose every value is null/empty (litellm: {"refusal": null}). */
    private static boolean allEmpty(JsonValue value) {
        if (!(value instanceof JsonObject o)) return false;
        for (JsonValue v : o.members().values()) {
            if (!(v instanceof JsonNull) && !(v instanceof JsonArray a && a.isEmpty()) && !(v instanceof JsonObject m && m.isEmpty())) return false;
        }
        return true;
    }

    private static boolean emptyClientValue(JsonValue value) {
        return value == null || value instanceof JsonNull || (value instanceof JsonArray a && a.isEmpty()) || (value instanceof JsonObject o && o.isEmpty()) || allEmpty(value);
    }

    /** OpenAI's assistant {@code annotations} (url_citation entries with a span into the content) → CitationParts. */
    private static List<CitationPart> annotations(String provider, JsonValue raw, String contentText, String where) {
        if (!(raw instanceof JsonArray arr)) throw ValidationException.type(where + ".annotations must be an array");
        List<CitationPart> out = new ArrayList<>();
        for (int index = 0; index < arr.size(); index++) {
            String entryWhere = where + ".annotations[" + index + "]";
            JsonObject entry = object(arr.get(index), entryWhere);
            if (!(entry.get("type") instanceof JsonString t && t.value().equals("url_citation"))) {
                throw unsupported(provider, entryWhere + " of type " + ChatErrors.pyStr(entry.get("type")), "only url_citation annotations have a canonical part (CitationPart)");
            }
            onlyKeys(provider, entry, Set.of("type", "url_citation"), entryWhere);
            JsonObject spec = object(entry.get("url_citation"), entryWhere + ".url_citation");
            onlyKeys(provider, spec, Set.of("url", "title", "start_index", "end_index"), entryWhere + ".url_citation");
            String text = null;
            JsonValue start = spec.opt("start_index");
            JsonValue end = spec.opt("end_index");
            if (contentText != null && start != null && start.isInt() && end != null && end.isInt()) {
                int s = start.asInt();
                int e = end.asInt();
                int length = contentText.codePointCount(0, contentText.length());
                if (0 <= s && s <= e && e <= length) {
                    String slice = contentText.substring(contentText.offsetByCodePoints(0, s), contentText.offsetByCodePoints(0, e));
                    text = slice.isEmpty() ? null : slice;
                }
            }
            String title = present(spec, "title") ? str(spec.get("title"), entryWhere + ".url_citation.title") : null;
            out.add(new CitationPart(str(spec.get("url"), entryWhere + ".url_citation.url"), title, text));
        }
        return out;
    }

    private static List<ToolCallPart> toolCalls(String provider, JsonValue calls, String where) {
        if (!(calls instanceof JsonArray arr)) throw ValidationException.type(where + ".tool_calls must be an array");
        List<ToolCallPart> out = new ArrayList<>();
        for (int index = 0; index < arr.size(); index++) {
            String callWhere = where + ".tool_calls[" + index + "]";
            JsonObject call = object(arr.get(index), callWhere);
            JsonValue kindV = call.has("type") ? call.get("type") : new JsonString("function");
            if (!(kindV instanceof JsonString k && k.value().equals("function"))) {
                throw unsupported(provider, callWhere + " of type " + ChatErrors.pyStr(kindV), "only function tool calls have a canonical part");
            }
            onlyKeys(provider, call, Set.of("id", "type", "function"), callWhere);
            JsonObject function = object(call.get("function"), callWhere + ".function");
            onlyKeys(provider, function, Set.of("name", "arguments"), callWhere + ".function");
            JsonValue arguments = function.has("arguments") ? function.get("arguments") : new JsonString("{}");
            JsonValue parsed;
            if (arguments instanceof JsonString s) {
                // The builder writes json.dumps(input); the inverse is exact, and a caller-authored string that is not a JSON
                // object is malformed (the lenient parse_json_object is for PROVIDER output only).
                if (s.value().isEmpty()) {
                    parsed = JsonObject.EMPTY;
                } else {
                    try {
                        parsed = Json.parse(s.value());
                    } catch (RuntimeException exc) {
                        throw ValidationException.value(callWhere + ".function.arguments is not JSON: " + exc.getMessage());
                    }
                }
            } else {
                parsed = arguments;
            }
            if (!(parsed instanceof JsonObject input)) throw ValidationException.value(callWhere + ".function.arguments must encode a JSON object");
            out.add(new ToolCallPart(str(call.get("id"), callWhere + ".id"), str(function.get("name"), callWhere + ".function.name"), input));
        }
        return out;
    }

    // ─── rows ───

    private static final class Rows {
        SystemPrompt system;
        boolean systemBreakpoint;
        Integer breakpointIndex;
        final List<Message> messages = new ArrayList<>();
        final List<ToolResultPart> pendingResults = new ArrayList<>();

        void flushResults() {
            if (!pendingResults.isEmpty()) {
                messages.add(new Message(Role.TOOL, new ArrayList<>(pendingResults)));
                pendingResults.clear();
            }
        }
    }

    /**
     * Wire rows → system, messages, and the breakpoint placement. The first row, when system/developer, is Request.system;
     * a later system/developer row is a developer Message; consecutive tool rows form one tool Message; a {@code name} on any
     * non-tool row is refused (no canonical slot).
     */
    private static Rows messages(String provider, JsonValue rowsV) {
        if (!(rowsV instanceof JsonArray rows)) throw ValidationException.type("messages must be an array");
        Rows out = new Rows();
        for (int index = 0; index < rows.size(); index++) {
            String where = "messages[" + index + "]";
            JsonObject row = object(rows.get(index), where);
            String role = row.get("role") instanceof JsonString s ? s.value() : null;
            if (present(row, "name") && !"tool".equals(role)) {
                throw unsupported(provider, where + ".name", "a per-message participant name has no canonical slot");
            }

            if ("system".equals(role) || "developer".equals(role)) {
                out.flushResults();
                onlyKeys(provider, row, Set.of("role", "content"), where);
                Blocks blocks = contentBlocks(provider, row.get("content"), "system", where);
                if (index == 0) {
                    if (blocks.breakpointAtEnd()) out.systemBreakpoint = true;
                    if (blocks.parts().size() == 1 && blocks.parts().get(0) instanceof TextPart t) out.system = SystemPrompt.of(t.text());
                    else out.system = SystemPrompt.of(blocks.parts());
                } else {
                    if (blocks.breakpointAtEnd()) out.breakpointIndex = out.messages.size();
                    out.messages.add(new Message(Role.DEVELOPER, blocks.parts()));
                }
                continue;
            }

            if ("user".equals(role)) {
                out.flushResults();
                onlyKeys(provider, row, Set.of("role", "content", "name"), where);
                Blocks blocks = contentBlocks(provider, row.get("content"), "user", where);
                if (blocks.breakpointAtEnd()) {
                    if (out.breakpointIndex != null || out.systemBreakpoint) throw ValidationException.value(where + ": a request carries at most one prompt_cache_breakpoint");
                    out.breakpointIndex = out.messages.size();
                }
                out.messages.add(new Message(Role.USER, blocks.parts()));
                continue;
            }

            if ("assistant".equals(role)) {
                out.flushResults();
                Set<String> allowed = new java.util.HashSet<>(Set.of("role", "content", "tool_calls", "refusal", "reasoning_content", "name", "audio", "function_call", "annotations"));
                allowed.addAll(CLIENT_OBJECT_KEYS);
                onlyKeys(provider, row, allowed, where);
                if (present(row, "audio")) throw unsupported(provider, where + ".audio", "an assistant audio reference has no canonical part");
                if (present(row, "function_call")) throw unsupported(provider, where + ".function_call", "the deprecated function-calling shape; use tool_calls");
                for (String key : List.of("provider_specific_fields", "thinking_blocks", "images")) {
                    // A client library's object model (litellm) dumped into history: null or empty carries nothing; anything else has no mapping.
                    if (!emptyClientValue(row.get(key))) {
                        throw unsupported(provider, where + "." + key, "a client library's own field with no canonical part; only its empty form reads as absent");
                    }
                }
                List<Part> parts = new ArrayList<>();
                if (present(row, "reasoning_content")) parts.add(new ThinkingPart(str(row.get("reasoning_content"), where + ".reasoning_content")));
                JsonValue content = row.opt("content");
                if (content != null) {
                    Blocks blocks = contentBlocks(provider, content, "assistant", where);
                    if (blocks.breakpointAtEnd()) {
                        throw ValidationException.value(where + ": a prompt_cache_breakpoint cannot mark an assistant message (the builder refuses the same cell)");
                    }
                    parts.addAll(blocks.parts());
                }
                if (present(row, "refusal")) parts.add(new RefusalPart(str(row.get("refusal"), where + ".refusal")));
                if (present(row, "tool_calls")) parts.addAll(toolCalls(provider, row.get("tool_calls"), where));
                if (present(row, "annotations")) {
                    parts.addAll(annotations(provider, row.get("annotations"), content instanceof JsonString cs ? cs.value() : null, where));
                }
                if (parts.isEmpty()) parts.add(new TextPart("")); // the never-empty rule (MAP-2), applied to history
                out.messages.add(new Message(Role.ASSISTANT, parts));
                continue;
            }

            if ("tool".equals(role)) {
                onlyKeys(provider, row, Set.of("role", "content", "tool_call_id", "name"), where);
                Blocks blocks = contentBlocks(provider, row.get("content"), "tool", where);
                if (blocks.breakpointAtEnd()) {
                    throw ValidationException.value(where + ": a prompt_cache_breakpoint cannot mark a tool message (the builder refuses the same cell)");
                }
                String name = present(row, "name") ? str(row.get("name"), where + ".name") : null;
                out.pendingResults.add(new ToolResultPart(str(row.get("tool_call_id"), where + ".tool_call_id"), blocks.parts(), name, false, List.of()));
                continue;
            }

            if ("function".equals(role)) {
                throw unsupported(provider, where + " with role 'function'", "the deprecated function-calling shape; use a tool row with tool_call_id");
            }
            throw ValidationException.value(where + ".role must be one of system, developer, user, assistant, tool; got " + ChatErrors.pyStr(row.get("role")));
        }
        out.flushResults();
        return out;
    }

    // ─── tools ───

    private static List<Tool> tools(String provider, JsonValue raw, OpenAIChatCompat compat) {
        if (raw == null || raw instanceof JsonNull) return List.of();
        if (!(raw instanceof JsonArray arr)) throw ValidationException.type("tools must be an array");
        List<Tool> out = new ArrayList<>();
        for (int index = 0; index < arr.size(); index++) {
            String where = "tools[" + index + "]";
            JsonObject entry = object(arr.get(index), where);
            String kind = entry.get("type") instanceof JsonString s ? s.value() : null;
            if ("function".equals(kind)) {
                onlyKeys(provider, entry, Set.of("type", "function"), where);
                JsonObject function = object(entry.get("function"), where + ".function");
                onlyKeys(provider, function, Set.of("name", "description", "parameters", "strict"), where + ".function");
                if (function.get("strict") instanceof JsonBool b && b.value()) {
                    // A per-tool strict flag has no canonical slot; dropping it would lose an enforcement the caller asked for.
                    // `false` is the wire default and carries nothing.
                    throw unsupported(provider, where + ".function.strict = true", "no per-tool strict slot (compat.strict_tools is a preset policy)");
                }
                String name = str(function.get("name"), where + ".function.name");
                String description = present(function, "description") ? str(function.get("description"), where + ".function.description") : null;
                JsonObject parameters = present(function, "parameters") ? object(function.get("parameters"), where + ".function.parameters") : FunctionTool.DEFAULT_PARAMETERS;
                out.add(new FunctionTool(name, description, parameters));
            } else if (kind != null && OpenAIChatDialect.GROQ_BUILTIN_INVERSE.containsKey(kind) && compat.builtinTools().equals("groq")) {
                JsonObject config = entry.without("type");
                out.add(new BuiltinTool(OpenAIChatDialect.GROQ_BUILTIN_INVERSE.get(kind), config.isEmpty() ? null : config));
            } else {
                throw unsupported(provider, where + " of type " + ChatErrors.pyStr(entry.get("type")), "only function tools (and, on the groq preset, its server-executed tools) have a canonical form");
            }
        }
        return out;
    }

    // ─── tool choice ───

    /** Inverse of the builder's tool_choice payload plus parallel_tool_calls. */
    private static ToolChoice toolChoice(String provider, JsonValue raw, JsonValue parallel) {
        ToolChoiceMode mode = null;
        List<String> allowed = List.of();
        if (raw != null && !(raw instanceof JsonNull)) {
            if (raw instanceof JsonString s && (s.value().equals("none") || s.value().equals("auto") || s.value().equals("required"))) {
                mode = ToolChoiceMode.fromWire(s.value());
            } else if (raw instanceof JsonObject o) {
                String kind = o.get("type") instanceof JsonString s ? s.value() : null;
                if ("function".equals(kind)) {
                    onlyKeys(provider, o, Set.of("type", "function"), "tool_choice");
                    JsonObject function = object(o.get("function"), "tool_choice.function");
                    onlyKeys(provider, function, Set.of("name"), "tool_choice.function");
                    mode = ToolChoiceMode.REQUIRED;
                    allowed = List.of(str(function.get("name"), "tool_choice.function.name"));
                } else if ("allowed_tools".equals(kind)) {
                    onlyKeys(provider, o, Set.of("type", "allowed_tools"), "tool_choice");
                    JsonObject spec = object(o.get("allowed_tools"), "tool_choice.allowed_tools");
                    onlyKeys(provider, spec, Set.of("mode", "tools"), "tool_choice.allowed_tools");
                    mode = ToolChoiceMode.fromWire(str(spec.get("mode"), "tool_choice.allowed_tools.mode"));
                    if (!(spec.get("tools") instanceof JsonArray entries) || entries.isEmpty()) {
                        throw ValidationException.value("tool_choice.allowed_tools.tools must be a non-empty array");
                    }
                    List<String> names = new ArrayList<>();
                    for (int index = 0; index < entries.size(); index++) {
                        String where = "tool_choice.allowed_tools.tools[" + index + "]";
                        JsonObject entry = object(entries.get(index), where);
                        if (!(entry.get("type") instanceof JsonString t && t.value().equals("function"))) {
                            throw unsupported(provider, where + " of type " + ChatErrors.pyStr(entry.get("type")), "only function tools can be allowed on this wire");
                        }
                        JsonObject function = object(entry.get("function"), where + ".function");
                        names.add(str(function.get("name"), where + ".function.name"));
                    }
                    allowed = names;
                } else if ("custom".equals(kind)) {
                    throw unsupported(provider, "tool_choice of type 'custom'", "custom tools have no canonical form");
                } else {
                    throw ValidationException.value("tool_choice.type must be function or allowed_tools; got " + ChatErrors.pyStr(o.get("type")));
                }
            } else {
                throw ValidationException.value("tool_choice must be none, auto, required, or an object");
            }
        }
        Boolean par = null;
        if (parallel != null && !(parallel instanceof JsonNull)) {
            if (!(parallel instanceof JsonBool b)) throw ValidationException.type("parallel_tool_calls must be a boolean");
            par = b.value();
        }
        if (mode == null && par == null) return null;
        return new ToolChoice(mode == null ? ToolChoiceMode.AUTO : mode, allowed, par);
    }

    // ─── response format ───

    /** Inverse of the builder's response_format. {@code {type: text}} is the wire default and reads as absent; a name of exactly "response" too. */
    private static JsonObject responseFormat(String provider, JsonValue rawV) {
        JsonObject raw = object(rawV, "response_format");
        String kind = raw.get("type") instanceof JsonString s ? s.value() : null;
        if ("text".equals(kind)) {
            onlyKeys(provider, raw, Set.of("type"), "response_format");
            return null;
        }
        if ("json_object".equals(kind)) {
            onlyKeys(provider, raw, Set.of("type"), "response_format");
            return Json.obj("type", "json_object");
        }
        if ("json_schema".equals(kind)) {
            onlyKeys(provider, raw, Set.of("type", "json_schema"), "response_format");
            JsonObject inner = object(raw.get("json_schema"), "response_format.json_schema");
            onlyKeys(provider, inner, Set.of("name", "schema", "strict", "description"), "response_format.json_schema");
            if (present(inner, "description")) {
                throw unsupported(provider, "response_format.json_schema.description", "the canonical response_format has no description slot (INV-050)");
            }
            dev.lm15.json.JsonBuilder out = new dev.lm15.json.JsonBuilder().put("type", "json_schema")
                .put("schema", object(inner.get("schema"), "response_format.json_schema.schema"));
            JsonValue name = inner.opt("name");
            if (name != null && !(name instanceof JsonString n && n.value().equals("response"))) {
                out.put("name", str(name, "response_format.json_schema.name"));
            }
            if (present(inner, "strict")) {
                if (!(inner.get("strict") instanceof JsonBool b)) throw ValidationException.type("response_format.json_schema.strict must be a boolean");
                out.put("strict", b.value());
            }
            return out.build();
        }
        throw ValidationException.value("response_format.type must be text, json_object or json_schema; got " + ChatErrors.pyStr(raw.get("type")));
    }

    // ─── reasoning ───

    private record ReasoningRead(Reasoning reasoning, LinkedHashMap<String, JsonValue> extensions) {}

    /** Inverse of the reasoning block of the builder for THIS preset's thinking_format (MAP-7 spellings). */
    private static ReasoningRead reasoning(String provider, JsonObject body, OpenAIChatCompat compat) {
        LinkedHashMap<String, JsonValue> extensions = new LinkedHashMap<>();
        TreeSet<String> present = new TreeSet<>();
        for (String k : List.of("reasoning_effort", "reasoning", "thinking", "enable_thinking", "chat_template_kwargs", "reasoning_format")) {
            if (body.has(k)) present.add(k);
        }
        if (present.isEmpty()) return new ReasoningRead(null, extensions);
        Set<String> spelledBy = switch (compat.thinkingFormat()) {
            case "reasoning_effort" -> Set.of("reasoning_effort");
            case "openrouter" -> Set.of("reasoning");
            case "deepseek", "kimi" -> Set.of("thinking", "reasoning_effort");
            case "qwen" -> Set.of("enable_thinking");
            case "qwen_chat_template" -> Set.of("chat_template_kwargs");
            default -> Set.of();
        };
        if (compat.builtinTools().equals("groq")) {
            Set<String> widened = new java.util.HashSet<>(spelledBy);
            widened.add("reasoning_format");
            spelledBy = widened;
        }
        TreeSet<String> foreign = new TreeSet<>(present);
        foreign.removeAll(spelledBy);
        if (!foreign.isEmpty()) {
            String spelled = spelledBy.isEmpty() ? "nowhere (no dial)" : new TreeSet<>(spelledBy).toString();
            throw unsupported(provider, "'" + foreign.first() + "'", "this server's reasoning dial is spelled " + spelled + "; another server's spelling would be sent and ignored");
        }
        String effort = null;
        boolean off = false;

        if (present.contains("reasoning_effort")) {
            String word = str(body.get("reasoning_effort"), "reasoning_effort");
            if (word.equals("none")) off = true;
            else effort = word;
        }
        if (present.contains("thinking")) {
            JsonObject spec = object(body.get("thinking"), "thinking");
            onlyKeys(provider, spec, Set.of("type"), "thinking");
            String type = spec.get("type") instanceof JsonString s ? s.value() : null;
            if ("disabled".equals(type)) {
                if (effort != null) throw ValidationException.value("thinking.type=disabled next to a reasoning_effort level is contradictory");
                off = true;
            } else if ("enabled".equals(type)) {
                if (effort == null && !off) {
                    throw unsupported(provider, "thinking.type=enabled without reasoning_effort", "lm15's dial is a level (MAP-7); set config.reasoning with an effort word");
                }
            } else {
                throw ValidationException.value("thinking.type must be enabled or disabled; got " + ChatErrors.pyStr(spec.get("type")));
            }
        }
        if (present.contains("reasoning")) {
            JsonObject spec = object(body.get("reasoning"), "reasoning");
            onlyKeys(provider, spec, Set.of("effort", "enabled"), "reasoning");
            if (spec.get("enabled") instanceof JsonBool b && !b.value()) off = true;
            else if (present(spec, "effort")) effort = str(spec.get("effort"), "reasoning.effort");
            else throw ValidationException.value("reasoning must carry effort or enabled: false");
        }
        if (present.contains("enable_thinking")) {
            JsonValue flag = body.get("enable_thinking");
            if (flag instanceof JsonBool b && !b.value()) off = true;
            else if (flag instanceof JsonBool b2 && b2.value()) {
                throw unsupported(provider, "enable_thinking = true", "this wire has no effort level; lm15's dial is a level (MAP-7) — set config.reasoning yourself");
            } else throw ValidationException.type("enable_thinking must be a boolean");
        }
        if (present.contains("chat_template_kwargs")) {
            JsonObject spec = object(body.get("chat_template_kwargs"), "chat_template_kwargs");
            onlyKeys(provider, spec, Set.of("enable_thinking", "preserve_thinking"), "chat_template_kwargs");
            JsonValue flag = spec.get("enable_thinking");
            if (flag instanceof JsonBool b && !b.value()) off = true;
            else if (flag instanceof JsonBool b2 && b2.value()) {
                throw unsupported(provider, "chat_template_kwargs.enable_thinking = true", "this wire has no effort level; lm15's dial is a level (MAP-7) — set config.reasoning yourself");
            } else throw ValidationException.type("chat_template_kwargs.enable_thinking must be a boolean");
        }

        ReasoningSummary summary = null;
        if (present.contains("reasoning_format")) {
            JsonValue value = body.get("reasoning_format");
            if (!(value instanceof JsonString s && s.value().equals("parsed"))) {
                throw unsupported(provider, "reasoning_format = " + ChatErrors.pyStr(value), "only 'parsed' maps (Reasoning.summary='auto', MAP-7 rule 7)");
            }
            if (effort == null) {
                // The documented door: extensions={"reasoning_format": "parsed"} with reasoning absent (the Qwen dial has no levels).
                extensions.put("reasoning_format", value);
            } else {
                summary = ReasoningSummary.AUTO;
            }
        }

        if (off) return new ReasoningRead(Reasoning.OFF, extensions);
        if (effort == null) return new ReasoningRead(null, extensions);
        return new ReasoningRead(new Reasoning(ReasoningEffort.fromWire(effort), null, summary), extensions);
    }

    // ─── cache ───

    /** Inverse of the cache payload and the breakpoint placement (MAP-6 on the OpenAI classes). */
    private static CacheConfig cache(String provider, JsonObject body, OpenAIChatCompat compat, boolean systemBreakpoint, Integer breakpointIndex) {
        TreeSet<String> keys = new TreeSet<>();
        for (String k : List.of("prompt_cache_key", "prompt_cache_retention", "prompt_cache_options")) if (body.has(k)) keys.add(k);
        boolean marked = systemBreakpoint || breakpointIndex != null;
        if (keys.isEmpty() && !marked) return null;
        String control = compat.cacheControl();
        if (!control.equals("openai") && !control.equals("openai_implicit")) {
            String what = keys.isEmpty() ? "prompt_cache_breakpoint" : keys.first();
            throw unsupported(provider, "'" + what + "'", "this server has no OpenAI prompt-cache control (compat.cache_control)");
        }
        if (marked && !control.equals("openai")) {
            throw unsupported(provider, "prompt_cache_breakpoint", "this server swallows an explicit breakpoint silently (compat.cache_control=openai_implicit)");
        }
        String key = present(body, "prompt_cache_key") ? str(body.get("prompt_cache_key"), "prompt_cache_key") : null;
        CacheRetention retention = null;
        if (body.has("prompt_cache_retention")) {
            JsonValue r = body.get("prompt_cache_retention");
            if (!(r instanceof JsonString s && s.value().equals("24h"))) {
                throw unsupported(provider, "prompt_cache_retention = " + ChatErrors.pyStr(r), "only '24h' has a canonical value (CacheConfig.retention='long')");
            }
            retention = CacheRetention.LONG;
        }
        boolean explicit = false;
        if (body.has("prompt_cache_options")) {
            JsonObject spec = object(body.get("prompt_cache_options"), "prompt_cache_options");
            onlyKeys(provider, spec, Set.of("mode", "ttl"), "prompt_cache_options");
            if (present(spec, "ttl")) throw unsupported(provider, "prompt_cache_options.ttl", "CacheConfig.retention names 24h only");
            String mode = spec.get("mode") instanceof JsonString s ? s.value() : null;
            if ("explicit".equals(mode)) explicit = true;
            else if ("implicit".equals(mode)) throw unsupported(provider, "prompt_cache_options.mode = 'implicit'", "the server default; a canonical CacheConfig names auto or off");
            else throw ValidationException.value("prompt_cache_options.mode must be explicit or implicit; got " + ChatErrors.pyStr(spec.get("mode")));
        }
        if (explicit && !marked) {
            // Explicit mode with no mark is the cache-WRITE off switch (MAP-6 rule 2).
            if (key != null || retention != null) {
                throw ValidationException.value("prompt_cache_options.mode=explicit with no breakpoint is the off switch; it cannot carry a key or retention (INV-027)");
            }
            return CacheConfig.OFF;
        }
        if (systemBreakpoint) return new CacheConfig(CacheMode.AUTO, retention, key, null, CachePrefix.STABLE, null);
        if (breakpointIndex != null) return new CacheConfig(CacheMode.AUTO, retention, key, breakpointIndex, null, null);
        return new CacheConfig(CacheMode.AUTO, retention, key, null, null, null);
    }

    // ─── config ───

    private static Config config(String provider, JsonObject body, OpenAIChatCompat compat, boolean systemBreakpoint, Integer breakpointIndex) {
        Config.Builder b = Config.builder();
        if (body.has("max_completion_tokens") || body.has("max_tokens")) {
            List<JsonValue> values = new ArrayList<>();
            for (String k : List.of("max_completion_tokens", "max_tokens")) if (body.has(k)) values.add(body.get(k));
            if (values.size() > 1 && !values.get(0).equals(values.get(1))) {
                throw ValidationException.value("max_tokens and max_completion_tokens disagree: " + values);
            }
            b.maxTokens(Canonical.toInt(values.get(0), "max_tokens"));
        }
        if (body.has("temperature")) b.temperature(Canonical.toDouble(body.get("temperature"), "temperature"));
        if (body.has("top_p")) b.topP(Canonical.toDouble(body.get("top_p"), "top_p"));
        if (body.has("service_tier")) b.serviceTier(Canonical.readString(body, "service_tier"));
        if (body.has("store")) b.store(Canonical.readBool(body, "store"));
        if (body.has("stop")) b.stop(Canonical.readStrings(body, "stop"));
        JsonValue logprobs = body.opt("logprobs");
        if (logprobs instanceof JsonBool lb && lb.value()) {
            JsonValue top = body.has("top_logprobs") ? body.get("top_logprobs") : dev.lm15.json.JsonInt.of(0);
            b.logprobs(Canonical.toInt(top, "top_logprobs"));
        } else if (logprobs != null && !(logprobs instanceof JsonBool)) {
            throw ValidationException.type("logprobs must be a boolean");
        } else if (body.has("top_logprobs")) {
            throw ValidationException.value("top_logprobs requires logprobs: true");
        }
        if (body.has("response_format")) b.responseFormat(responseFormat(provider, body.get("response_format")));
        b.toolChoice(toolChoice(provider, body.get("tool_choice"), body.get("parallel_tool_calls")));

        List<String> userKeys = new ArrayList<>();
        for (String k : List.of("user", "safety_identifier", "user_id")) if (body.has(k)) userKeys.add(k);
        if (userKeys.contains("user_id") && !compat.userField().equals("user_id")) {
            throw unsupported(provider, "'user_id'", "this server spells the end-user field '" + compat.userField() + "'");
        }
        if (userKeys.size() > 1) throw ValidationException.value("one end-user identifier only; got " + userKeys);
        if (!userKeys.isEmpty()) b.userId(Canonical.readString(body, userKeys.get(0)));

        ReasoningRead r = reasoning(provider, body, compat);
        b.reasoning(r.reasoning());
        b.cache(cache(provider, body, compat, systemBreakpoint, breakpointIndex));
        LinkedHashMap<String, JsonValue> extensions = r.extensions();
        for (String key : body.keys()) if (EXTENSIONS_KEYS.contains(key)) extensions.put(key, body.get(key));
        b.extensions(extensions.isEmpty() ? null : new JsonObject(extensions));
        return b.build();
    }

    // ─── the entry point ───

    static Request ingest(String provider, JsonObject body, OpenAIChatCompat compat) {
        for (String key : body.keys()) {
            if (REFUSED_KEYS.containsKey(key)) throw unsupported(provider, "'" + key + "'", REFUSED_KEYS.get(key));
            if (!CONFIG_KEYS.contains(key) && !EXTENSIONS_KEYS.contains(key) && !CALL_MODE_KEYS.contains(key)) {
                throw unsupported(provider, "'" + key + "'", "no verdict for this key (lm15-contract/tools/openai-chat-ingest-verdicts.json); lm15 never drops a key silently");
            }
        }
        if (!(body.get("model") instanceof JsonString m) || m.value().isEmpty()) throw ValidationException.value("model must be a non-empty string");
        if (!body.has("messages")) throw ValidationException.value("messages is required");
        Rows rows = messages(provider, body.get("messages"));
        List<Tool> tools = tools(provider, body.get("tools"), compat);
        Config config = config(provider, body, compat, rows.systemBreakpoint, rows.breakpointIndex);
        return new Request(m.value(), rows.messages, rows.system, tools, config);
    }
}
