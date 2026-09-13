package dev.lm15.cloud;

import dev.lm15.auth.Access;
import dev.lm15.auth.AccessPolicy;
import dev.lm15.auth.AuthScheme;
import dev.lm15.auth.Credential;
import dev.lm15.auth.HostSetting;
import dev.lm15.auth.HostSpec;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.NotConfiguredError;
import dev.lm15.errors.UnsupportedFeatureError;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;
import dev.lm15.types.ValidationException;
import dev.lm15.wire.Wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A dialect reaches a cloud door through a host (AUTH-10): settings
 * resolution, base-URL rendering and the closed set of request rewrites
 * (the reference's {@code lm15/cloud/hosts.py}).
 */
public final class Hosts {
    private Hosts() {}

    private static final Pattern DNS_LABEL = Pattern.compile("[A-Za-z0-9-]+");

    /** Explicit values, then env (when given), then the cloud profile, then defaults; a required setting with none raises. */
    public static Map<String, String> resolveSettings(HostSpec host, Map<String, String> given, Map<String, String> env, String provider,
                                                      Function<String, String> profile) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (host == null) return given == null ? out : new LinkedHashMap<>(given);
        LinkedHashMap<String, String> remaining = new LinkedHashMap<>(given == null ? Map.of() : given);
        for (HostSetting setting : host.settings()) {
            String value = remaining.remove(setting.name());
            if ((value == null || value.isEmpty()) && env != null) {
                for (String var : setting.env()) {
                    String candidate = env.get(var);
                    if (candidate != null && !candidate.isEmpty()) { value = candidate; break; }
                }
            }
            if ((value == null || value.isEmpty()) && profile != null) value = profile.apply(setting.name());
            if (value == null || value.isEmpty()) value = setting.defaultValue();
            if (value == null || value.isEmpty()) {
                String hint = setting.env().isEmpty() ? "pass settings={'" + setting.name() + "': ...}" : "set " + String.join(" or ", setting.env());
                String who = provider == null || provider.isEmpty() ? "host" : provider;
                throw new NotConfiguredError(who + ": setting '" + setting.name() + "' is required and has no default; " + hint,
                    ErrorMeta.of(provider == null || provider.isEmpty() ? null : provider), List.of(), hint);
            }
            out.put(setting.name(), value);
        }
        if (!remaining.isEmpty()) {
            String who = provider == null || provider.isEmpty() ? "host" : provider;
            throw ValidationException.value(who + ": unknown host setting(s) " + new TreeSet<>(remaining.keySet()) + "; known: " + host.settingNames());
        }
        return out;
    }

    /** Vertex host for a location. */
    public static String locationHost(String location) {
        if (location.equals("global")) return "aiplatform.googleapis.com";
        if (location.equals("us") || location.equals("eu")) return "aiplatform." + location + ".rep.googleapis.com";
        return location + "-aiplatform.googleapis.com";
    }

    public static String renderBaseUrl(HostSpec host, Map<String, String> settings) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>(settings);
        for (String name : List.of("region", "resource", "location")) {
            if (values.containsKey(name) && !DNS_LABEL.matcher(values.get(name)).matches()) {
                throw new NotConfiguredError("host setting '" + name + "' must be a DNS label");
            }
        }
        if (values.containsKey("project")) values.put("project", Wire.percentEncode(values.get("project"), ""));
        if (values.containsKey("location") && !values.containsKey("location_host")) values.put("location_host", locationHost(values.get("location")));
        return format(host.baseUrl(), values);
    }

    /** Python {@code str.format} over {name} placeholders; a missing name is a NotConfiguredError. */
    static String format(String template, Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i);
                if (end < 0) throw new IllegalArgumentException("unbalanced template: " + template);
                String name = template.substring(i + 1, end);
                String value = values.get(name);
                if (value == null) throw new NotConfiguredError("host base URL needs setting '" + name + "'");
                sb.append(value);
                i = end + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** The finished request: URL, headers, body and decoded params after the host's rewrites. */
    public record Finished(String url, List<Map.Entry<String, String>> headers, JsonValue body, List<Map.Entry<String, String>> params) {}

    public static Finished finishRequest(AccessPolicy policy, Map<String, String> settings, String baseUrl, String url,
                                         List<Map.Entry<String, String>> headers, JsonValue body, List<Map.Entry<String, String>> params,
                                         String endpoint, boolean stream, String model, Credential credential) {
        HostSpec host = policy.host();
        List<Map.Entry<String, String>> outHeaders = new ArrayList<>(headers);
        List<Map.Entry<String, String>> outParams = new ArrayList<>(params);
        if (host == null) return new Finished(url, outHeaders, body, outParams);

        if (!host.streamFraming().equals("sse") && stream) {
            throw new UnsupportedFeatureError(policy.provider() + ": " + host.streamFraming() + " stream framing is not implemented yet (phase 2)", ErrorMeta.of(policy.provider()));
        }

        String key = (endpoint != null && stream && host.paths().containsKey(endpoint + "/stream")) ? endpoint + "/stream" : endpoint;
        if (key != null && host.paths().containsKey(key)) {
            String template = host.paths().get(key);
            if (template.contains("{model}") && (model == null || model.isEmpty())) {
                throw ValidationException.value(policy.provider() + ": endpoint '" + endpoint + "' needs the model in the path");
            }
            String pathModel = model == null ? "" : model;
            if ("generateContent".equals(endpoint) && pathModel.startsWith("models/")) pathModel = pathModel.substring("models/".length());
            String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            url = base + template.replace("{model}", Wire.percentEncode(pathModel, ":@"));
        }

        if (body instanceof JsonObject obj) {
            JsonObject rewritten = obj;
            if (host.modelIn().equals("path")) rewritten = rewritten.without("model");
            if (host.anthropicVersionIn().startsWith("body:")) {
                rewritten = rewritten.with("anthropic_version", new JsonString(host.anthropicVersionIn().substring("body:".length())));
                outHeaders.removeIf(h -> h.getKey().equalsIgnoreCase("anthropic-version"));
            }
            body = rewritten;
        }

        for (Map.Entry<String, String> required : host.requiredHeaders()) {
            String value = settings.get(required.getValue());
            if (value == null || value.isEmpty()) {
                throw new NotConfiguredError(policy.provider() + ": header " + required.getKey() + " needs setting '" + required.getValue() + "'", ErrorMeta.of(policy.provider()));
            }
            outHeaders.removeIf(h -> h.getKey().equalsIgnoreCase(required.getKey()));
            outHeaders.add(Map.entry(required.getKey().toLowerCase(), value));
        }

        if (credential instanceof Credential.ApiKey key2 && policy.authScheme().contains(AuthScheme.QUERY_KEY)
            && Access.selectScheme(policy, key2) == AuthScheme.QUERY_KEY) {
            outParams.removeIf(p -> p.getKey().equals("key"));
            outParams.add(Map.entry("key", key2.value()));
        }
        return new Finished(url, outHeaders, body, outParams);
    }
}
