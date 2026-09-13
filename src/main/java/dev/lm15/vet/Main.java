package dev.lm15.vet;

import dev.lm15.errors.AmbiguousModelError;
import dev.lm15.errors.LM15Error;
import dev.lm15.errors.StreamAssemblyError;
import dev.lm15.errors.UnknownModelError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonException;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.serde.Canonical;
import dev.lm15.types.ValidationException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The lm15 vet shim (lm15-contract/harness/PROTOCOL.md): newline-delimited
 * JSON on stdin/stdout, one reply per request, same {@code id}. Thin by rule:
 * it parses the protocol line, calls the public functions users call, and
 * serializes. No network.
 *
 * <p>Run as {@code java -jar target/lm15.jar} (cwd: lm15-java).
 */
public final class Main {
    private Main() {}

    public static final String LANGUAGE = "java";
    public static final String IMPL_VERSION = dev.lm15.Version.VERSION;

    /** One protocol op: the request object in, the {@code result} object out. */
    @FunctionalInterface
    public interface Op {
        JsonObject apply(JsonObject msg);
    }

    /** A typed failure that also carries extra reply fields (replay_stream: the parsed event trace, MAP-9). */
    public static final class OpFailure extends RuntimeException {
        private final Throwable cause;
        private final JsonObject extra;

        public OpFailure(Throwable cause, JsonObject extra) {
            super(cause.getMessage(), cause);
            this.cause = cause;
            this.extra = extra;
        }
    }

    private static final Map<String, Op> HANDLERS = new TreeMap<>();

    static {
        HANDLERS.put("capabilities", Main::opCapabilities);
        HANDLERS.put("serde_roundtrip", Main::opSerdeRoundtrip);
        HANDLERS.put("validate", Main::opValidate);
        HANDLERS.put("surface_dump", SurfaceDump::op);
        AdapterOps.register(HANDLERS);
        AuthOps.register(HANDLERS);
        RouterOps.register(HANDLERS);
    }

    static JsonObject opCapabilities(JsonObject msg) {
        List<JsonValue> ops = new ArrayList<>();
        for (String name : HANDLERS.keySet()) ops.add(new JsonString(name));
        return Json.obj("language", LANGUAGE, "ops", new JsonArray(ops), "impl_version", IMPL_VERSION);
    }

    static JsonObject opSerdeRoundtrip(JsonObject msg) {
        String kind = msg.get("kind").asString();
        return Json.obj("value", Canonical.roundtrip(kind, msg.get("value").asObject()));
    }

    static JsonObject opValidate(JsonObject msg) {
        String kind = msg.get("kind").asString();
        return Json.obj("ok", true, "normalized", Canonical.roundtrip(kind, msg.get("value").asObject()));
    }

    /** The failure envelope: canonical class + code for lm15 errors, the native name otherwise. */
    static JsonObject errorReply(JsonValue id, Throwable exc) {
        JsonObject extra = JsonObject.EMPTY;
        if (exc instanceof OpFailure f) {
            extra = f.extra;
            exc = f.cause;
        }
        JsonBuilder error = new JsonBuilder();
        if (exc instanceof LM15Error e) {
            error.put("type", e.className()).put("message", e.message()).put("code", e.code().wire());
            if (e instanceof StreamAssemblyError sae && sae.partial() != null) {
                error.put("partial_response", Canonical.toJson(sae.partial()));
            }
            if (e instanceof UnknownModelError u) error.put("model", u.model());
            if (e instanceof AmbiguousModelError a) {
                error.put("model", a.model());
                error.put("providers", Json.of(a.providers()));
            }
        } else if (exc instanceof ValidationException v) {
            error.put("type", v.protocolName()).put("message", String.valueOf(v.getMessage()));
        } else if (exc instanceof JsonException) {
            error.put("type", "ValueError").put("message", String.valueOf(exc.getMessage()));
        } else if (exc instanceof ArithmeticException || exc instanceof NumberFormatException) {
            error.put("type", "ValueError").put("message", String.valueOf(exc.getMessage()));
        } else if (exc instanceof NullPointerException || exc instanceof ClassCastException) {
            error.put("type", "TypeError").put("message", String.valueOf(exc.getMessage()));
        } else {
            error.put("type", exc.getClass().getSimpleName()).put("message", String.valueOf(exc.getMessage()));
        }
        error.putAll(extra);
        return Json.obj("id", id == null ? null : id, "ok", false, "error", error.build());
    }

    public static JsonObject handleLine(String line) {
        JsonValue parsed;
        try {
            parsed = Json.parse(line);
        } catch (RuntimeException exc) {
            return errorReply(null, exc);
        }
        if (!(parsed instanceof JsonObject msg)) {
            return errorReply(null, ValidationException.value("request must be a JSON object"));
        }
        JsonValue id = msg.get("id");
        try {
            JsonValue op = msg.get("op");
            Op handler = op == null ? null : HANDLERS.get(op.isString() ? op.asString() : op.toJson());
            if (handler == null) throw ValidationException.value("unknown op: " + op);
            JsonObject result = handler.apply(msg);
            return Json.obj("id", id, "ok", true, "result", result);
        } catch (Throwable exc) {
            if (exc instanceof Error err && !(exc instanceof StackOverflowError)) throw err;
            return errorReply(id, exc);
        }
    }

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            out.print(Json.write(handleLine(line)));
            out.print('\n');
            out.flush();
        }
        System.exit(0);
    }
}
