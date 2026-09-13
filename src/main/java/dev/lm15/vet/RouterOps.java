package dev.lm15.vet;

import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.router.LMRouter;
import dev.lm15.router.ModelRegistry;
import dev.lm15.router.Resolution;
import dev.lm15.router.RouterConfig;
import dev.lm15.types.ValidationException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The router op ({@code resolve_model}; PROTOCOL.md): the router's
 * {@code resolve} over harness-supplied inputs only. {@code env} is the whole
 * environment (always passed, so the process environment never leaks in);
 * {@code catalog}, when present, is the explicit catalog of canonical
 * {@code model_info} values and replaces any discovery. Pure: no network, no
 * credential lookup, no adapter construction. A routing failure is the
 * ordinary failure envelope ({@code Main.errorReply} adds {@code model} /
 * {@code providers}).
 */
final class RouterOps {
    private RouterOps() {}

    static void register(Map<String, Main.Op> handlers) {
        handlers.put("resolve_model", RouterOps::resolveModel);
    }

    static JsonObject resolveModel(JsonObject msg) {
        JsonValue model = msg.get("model");
        if (model == null || !model.isString()) throw ValidationException.type("model must be a string");
        RouterConfig.Builder config = RouterConfig.builder().env(envOf(msg));
        JsonValue catalog = msg.opt("catalog");
        if (catalog != null) {
            if (!(catalog instanceof JsonArray entries)) throw ValidationException.type("catalog must be a list of model_info objects");
            config.catalog(ModelRegistry.fromJson(entries));
        }
        Resolution r = new LMRouter(config.build()).resolve(model.asString());
        return Json.obj("provider", r.provider(), "model", r.model(), "source", r.source().wire());
    }

    /** The harness's env map; an absent field is the empty environment, never the process's. */
    static Map<String, String> envOf(JsonObject msg) {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        JsonValue given = msg.opt("env");
        if (given == null) return env;
        if (!(given instanceof JsonObject o)) throw ValidationException.type("env must be an object of strings");
        for (String key : o.keys()) {
            JsonValue v = o.get(key);
            if (!v.isString()) throw ValidationException.type("env[" + key + "] must be a string");
            env.put(key, v.asString());
        }
        return env;
    }
}
