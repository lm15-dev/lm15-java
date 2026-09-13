package dev.lm15.vet;

import dev.lm15.auth.AuthChain;
import dev.lm15.auth.Credential;
import dev.lm15.auth.CredentialProvider;
import dev.lm15.auth.Doctor;
import dev.lm15.auth.EndpointSupport;
import dev.lm15.auth.Rfc3339;
import dev.lm15.cloud.ChainContext;
import dev.lm15.cloud.Chains;
import dev.lm15.cloud.SigV4;
import dev.lm15.errors.LM15Error;
import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.registry.ProviderDefinition;
import dev.lm15.registry.Registry;
import dev.lm15.serde.Canonical;
import dev.lm15.types.ValidationException;
import dev.lm15.wire.Clock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The auth-driven ops (explain_auth, token_exchange_build, token_exchange_parse, sigv4_sign) and the providers block of surface_dump. */
final class AuthOps {
    private AuthOps() {}

    static void register(Map<String, Main.Op> handlers) {
        handlers.put("explain_auth", AuthOps::opExplainAuth);
        handlers.put("token_exchange_build", AuthOps::opTokenExchangeBuild);
        handlers.put("token_exchange_parse", AuthOps::opTokenExchangeParse);
        handlers.put("sigv4_sign", AuthOps::opSigv4Sign);
    }

    private static Map<String, String> stringMap(JsonValue value) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (value instanceof JsonObject o) {
            for (Map.Entry<String, JsonValue> e : o.members().entrySet()) {
                out.put(e.getKey(), e.getValue().isString() ? e.getValue().asString() : e.getValue().toJson());
            }
        }
        return out;
    }

    /**
     * AUTH-7 over harness-supplied inputs only: {@code env} (always passed,
     * even empty), {@code api_keys_providers} planted with the sentinel,
     * {@code credentials_path} (the harness-written borrowed file),
     * {@code files} under the sandbox HOME, {@code settings}.
     */
    static JsonObject opExplainAuth(JsonObject msg) {
        String provider = msg.get("provider").asString();
        String sentinel = msg.get("sentinel").asString();
        Map<String, String> env = stringMap(msg.opt("env"));
        Map<String, CredentialProvider> apiKeys = null;
        JsonArray providers = msg.optArray("api_keys_providers");
        if (providers != null && !providers.isEmpty()) {
            apiKeys = new LinkedHashMap<>();
            for (JsonValue p : providers) apiKeys.put(p.asString(), CredentialProvider.of(sentinel));
        }
        Map<String, String> files = msg.opt("files") instanceof JsonObject ? stringMap(msg.opt("files")) : null;
        String home = env.get("HOME");
        if (home != null && home.isEmpty()) home = null;
        Map<String, String> settings = msg.opt("settings") instanceof JsonObject ? stringMap(msg.opt("settings")) : null;
        String credentialsPath = msg.optString("credentials_path");

        Doctor.AuthReport report = Doctor.explainAuth(provider, apiKeys, env, credentialsPath == null ? null : Path.of(credentialsPath), files, home, settings);
        List<JsonValue> steps = new ArrayList<>();
        for (AuthChain.Step step : report.steps()) steps.add(Json.obj("kind", step.kind(), "state", step.state()));
        return Json.obj("configured", report.configured(), "steps", new JsonArray(steps),
            "report_text", report.describe() + "\n" + report + "\n" + debug(report));
    }

    private static String debug(Doctor.AuthReport report) {
        StringBuilder sb = new StringBuilder("AuthReport(provider='").append(report.provider()).append("', steps=(");
        for (AuthChain.Step s : report.steps()) {
            sb.append("AuthStep(kind='").append(s.kind()).append("', source='").append(s.source()).append("', detail='").append(s.detail())
                .append("', state='").append(s.state()).append("'), ");
        }
        sb.append("), configured=").append(report.configured()).append(", settings=").append(report.settings()).append(")");
        return sb.toString();
    }

    private static ProviderDefinition definition(JsonObject msg) {
        ProviderDefinition definition = Registry.lookup(msg.get("provider").asString());
        if (definition == null) throw ValidationException.value("unknown provider: " + msg.get("provider").asString());
        return definition;
    }

    /** PROTOCOL.md token_exchange_build: the exact token-exchange request a chain rung sends under the fixed clock. */
    static JsonObject opTokenExchangeBuild(JsonObject msg) {
        ProviderDefinition definition = definition(msg);
        JsonObject inputs = msg.optObject("input");
        if (inputs == null) inputs = msg.optObject("credential");
        if (inputs == null) inputs = JsonObject.EMPTY;
        Map<String, String> env = stringMap(inputs.opt("env"));
        Map<String, String> files = null;
        String certificatePem = inputs.optString("certificate_pem");
        String certPath = env.get("AZURE_CLIENT_CERTIFICATE_PATH");
        if (certificatePem != null && !certificatePem.isEmpty() && certPath != null && !certPath.isEmpty()) {
            // The harness gives the certificate and the key separately; the environment rung reads one PEM file carrying both.
            String key = inputs.optString("private_key_pem");
            files = Map.of(certPath, certificatePem + "\n" + (key == null ? "" : key));
        }
        Instant fixed = Rfc3339.parse(msg.get("now").asString());
        Map<String, String> settings = stringMap(inputs.opt("settings") != null ? inputs.opt("settings") : msg.opt("settings"));
        ChainContext ctx = new ChainContext(env, null, files, null, null, Clock.fixed(fixed)).withSettings(settings);
        return Chains.tokenExchangeBuild(definition.access(), msg.get("rung").asString(), inputs, ctx);
    }

    static JsonObject opTokenExchangeParse(JsonObject msg) {
        ProviderDefinition definition = definition(msg);
        Instant fixed = Rfc3339.parse(msg.get("now").asString());
        JsonValue body = msg.opt("body");
        if (body == null && msg.opt("body_b64") != null) body = Json.parse(Base64.getDecoder().decode(msg.get("body_b64").asString()));
        JsonObject object;
        if (body instanceof JsonObject o) object = o;
        else if (body instanceof JsonString s) { JsonValue parsed = Json.parse(s.value()); object = parsed instanceof JsonObject o2 ? o2 : JsonObject.EMPTY; }
        else object = JsonObject.EMPTY;
        int status = msg.opt("status") == null ? 200 : msg.get("status").asInt();
        ChainContext ctx = new ChainContext(Map.of(), null, null, null, null, Clock.fixed(fixed));
        Credential credential;
        try {
            credential = Chains.tokenExchangeParse(definition.access(), msg.get("rung").asString(), status, object, ctx);
        } catch (LM15Error e) {
            return Json.obj("ok", false, "error", Json.obj("class", e.className(), "code", e.code().wire()));
        }
        return Json.obj("ok", true, "credential", Canonical.toJson(credential));
    }

    /** PROTOCOL.md sigv4_sign: the AWS test-suite vectors; a list value is the same name repeated, joined with commas. */
    static JsonObject opSigv4Sign(JsonObject msg) {
        Credential credential = Canonical.credentialFromJson(msg.get("credential").asObject());
        if (!(credential instanceof Credential.AwsCredentials aws)) throw ValidationException.value("sigv4_sign needs an aws credential");
        JsonObject req = msg.get("request").asObject();
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        JsonObject given = req.optObject("headers");
        if (given != null) {
            for (Map.Entry<String, JsonValue> h : given.members().entrySet()) {
                String name = h.getKey().toLowerCase();
                if (name.equals("host") || name.equals("x-amz-date") || name.equals("x-amz-security-token")) continue;
                String value;
                if (h.getValue() instanceof JsonArray a) {
                    List<String> parts = new ArrayList<>();
                    for (JsonValue v : a) parts.add(v.asString());
                    value = String.join(",", parts);
                } else {
                    value = h.getValue().asString();
                }
                headers.add(Map.entry(h.getKey(), value));
            }
        }
        String body = req.optString("body");
        SigV4.Signature signature = SigV4.sign(req.get("method").asString(), req.get("url").asString(), headers,
            (body == null ? "" : body).getBytes(StandardCharsets.UTF_8), aws, msg.get("region").asString(), msg.get("service").asString(),
            Rfc3339.parse(msg.get("now").asString()));
        JsonBuilder out = new JsonBuilder();
        for (Map.Entry<String, String> h : signature.headers()) out.put(h.getKey(), h.getValue());
        return Json.obj("canonical_request", signature.canonicalRequest(), "string_to_sign", signature.stringToSign(),
            "authorization", signature.authorization(), "headers", out.build());
    }

    /** Every registered provider's access policy — who supports what — the support matrix the contract pins. */
    static JsonObject reflectProviders() {
        TreeMap<String, JsonValue> out = new TreeMap<>();
        for (Map.Entry<String, ProviderDefinition> e : Registry.PROVIDERS.entrySet()) {
            EndpointSupport s = e.getValue().access().supports();
            List<String> extra = new ArrayList<>(s.extra());
            java.util.Collections.sort(extra);
            JsonObject supports = new JsonBuilder().put("complete", s.complete()).put("stream", s.stream()).put("live", s.live()).put("files", s.files())
                .put("batches", s.batches()).put("images", s.images()).put("speech", s.speech()).put("video", s.video())
                .put("responses_api", s.responsesApi()).put("models", s.models()).put("caches", s.caches()).put("extra", extra).build();
            out.put(e.getKey(), Json.obj("supports", supports, "auth_modes", e.getValue().access().authModes(), "env_keys", e.getValue().access().envKeys()));
        }
        return new JsonObject(out);
    }
}
