package dev.lm15.auth;

import dev.lm15.types.ValidationException;

import java.util.List;
import java.util.Map;

/**
 * How a dialect reaches a cloud door (spec/auth.md AUTH-10 {@code host}):
 * a base-URL template over the settings, endpoint path overrides keyed by
 * the dialect's endpoint name, where the model and the anthropic version
 * go, the stream framing, required headers and the SigV4 service.
 */
public record HostSpec(String baseUrl, List<HostSetting> settings, Map<String, String> paths, String modelIn,
                       String anthropicVersionIn, String streamFraming, List<Map.Entry<String, String>> requiredHeaders, String sigv4Service) {
    public HostSpec {
        settings = settings == null ? List.of() : List.copyOf(settings);
        paths = paths == null ? Map.of() : Map.copyOf(paths);
        requiredHeaders = requiredHeaders == null ? List.of() : List.copyOf(requiredHeaders);
        if (modelIn == null) modelIn = "body";
        if (!modelIn.equals("body") && !modelIn.equals("path")) throw ValidationException.value("HostSpec.model_in '" + modelIn + "' not in [body, path]");
        if (anthropicVersionIn == null) anthropicVersionIn = "header";
        if (!anthropicVersionIn.equals("header") && !anthropicVersionIn.startsWith("body:")) {
            throw ValidationException.value("HostSpec.anthropic_version_in is 'header' or 'body:<value>'");
        }
        if (streamFraming == null) streamFraming = "sse";
        if (!streamFraming.equals("sse") && !streamFraming.equals("aws-event-stream")) throw ValidationException.value("HostSpec.stream_framing '" + streamFraming + "' not in [sse, aws-event-stream]");
    }

    public static HostSpec of(String baseUrl, List<HostSetting> settings) {
        return new HostSpec(baseUrl, settings, Map.of(), "body", "header", "sse", List.of(), null);
    }

    public List<String> settingNames() {
        return settings.stream().map(HostSetting::name).toList();
    }

    public HostSpec withSigv4Service(String s) { return new HostSpec(baseUrl, settings, paths, modelIn, anthropicVersionIn, streamFraming, requiredHeaders, s); }
    public HostSpec withRequiredHeaders(List<Map.Entry<String, String>> h) { return new HostSpec(baseUrl, settings, paths, modelIn, anthropicVersionIn, streamFraming, h, sigv4Service); }
    public HostSpec withPaths(Map<String, String> p) { return new HostSpec(baseUrl, settings, p, modelIn, anthropicVersionIn, streamFraming, requiredHeaders, sigv4Service); }
    public HostSpec withModelIn(String m) { return new HostSpec(baseUrl, settings, paths, m, anthropicVersionIn, streamFraming, requiredHeaders, sigv4Service); }
    public HostSpec withAnthropicVersionIn(String v) { return new HostSpec(baseUrl, settings, paths, modelIn, v, streamFraming, requiredHeaders, sigv4Service); }
}
