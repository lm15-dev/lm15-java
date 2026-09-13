package dev.lm15.auth;

import dev.lm15.types.ValidationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How an adapter reaches a backend, as pure data (spec/auth.md AUTH-10).
 * Ports copy the table ({@link Access}) as data and consult it at the same
 * named points the reference does.
 */
public record AccessPolicy(String provider, EndpointSupport supports, List<String> authModes, List<String> enterpriseVariants,
                           List<String> envKeys, CredentialPolicy credentialPolicy, List<AuthScheme> authScheme,
                           List<Map.Entry<String, String>> headers, HostSpec host, String loginHint, String backend,
                           Map<String, String> backendOptions, String systemPrefix, String baseUrl) {
    public AccessPolicy {
        if (provider == null || provider.isEmpty()) throw ValidationException.value("AccessPolicy.provider must be non-empty");
        if (supports == null) supports = EndpointSupport.CHAT;
        authModes = authModes == null ? List.of() : List.copyOf(authModes);
        enterpriseVariants = enterpriseVariants == null ? List.of() : List.copyOf(enterpriseVariants);
        envKeys = envKeys == null ? List.of() : List.copyOf(envKeys);
        if (credentialPolicy == null) credentialPolicy = CredentialPolicy.KEY;
        if (credentialPolicy == CredentialPolicy.OAUTH && !envKeys.isEmpty()) {
            throw ValidationException.value(provider + ": an 'oauth' access policy declares no env_keys");
        }
        authScheme = authScheme == null || authScheme.isEmpty() ? List.of(AuthScheme.BEARER) : List.copyOf(authScheme);
        headers = headers == null ? List.of() : List.copyOf(headers);
        if (authScheme.contains(AuthScheme.SIGV4) && (host == null || host.sigv4Service() == null)) {
            throw ValidationException.value(provider + ": sigv4 needs a host with sigv4_service");
        }
        if (credentialPolicy.isCloudChain() && host == null && !provider.equals("vertex-express")) {
            throw ValidationException.value(provider + ": a cloud chain policy needs a host");
        }
        if (backend == null) backend = "api";
        backendOptions = backendOptions == null ? Map.of() : Map.copyOf(backendOptions);
    }

    /** The first header-carrying scheme, in the two-value spelling the dialects consult for an ApiKey. */
    public String authHeader() {
        for (AuthScheme s : authScheme) {
            if (s == AuthScheme.BEARER) return "bearer";
            if (s == AuthScheme.X_API_KEY || s == AuthScheme.API_KEY) return "x-api-key";
        }
        return "bearer";
    }

    public boolean cloudChain() { return credentialPolicy.isCloudChain(); }

    /** A copy with these static headers replaced or appended (names compared case-insensitively). */
    public AccessPolicy withHeaders(Map<String, String> replace) {
        List<Map.Entry<String, String>> kept = new ArrayList<>();
        for (Map.Entry<String, String> h : headers) {
            boolean shadowed = replace.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(h.getKey()));
            if (!shadowed) kept.add(h);
        }
        for (Map.Entry<String, String> e : new LinkedHashMap<>(replace).entrySet()) kept.add(Map.entry(e.getKey(), e.getValue()));
        return new AccessPolicy(provider, supports, authModes, enterpriseVariants, envKeys, credentialPolicy, authScheme, kept, host, loginHint, backend, backendOptions, systemPrefix, baseUrl);
    }

    public static Builder builder(String provider) { return new Builder(provider); }

    public static final class Builder {
        private final String provider;
        private EndpointSupport supports = EndpointSupport.CHAT;
        private List<String> authModes = List.of();
        private List<String> enterpriseVariants = List.of();
        private List<String> envKeys = List.of();
        private CredentialPolicy credentialPolicy = CredentialPolicy.KEY;
        private List<AuthScheme> authScheme = List.of(AuthScheme.BEARER);
        private List<Map.Entry<String, String>> headers = List.of();
        private HostSpec host;
        private String loginHint;
        private String backend = "api";
        private Map<String, String> backendOptions = Map.of();
        private String systemPrefix;
        private String baseUrl;

        Builder(String provider) { this.provider = provider; }
        public Builder supports(EndpointSupport v) { supports = v; return this; }
        public Builder authModes(String... v) { authModes = List.of(v); return this; }
        public Builder enterpriseVariants(String... v) { enterpriseVariants = List.of(v); return this; }
        public Builder envKeys(String... v) { envKeys = List.of(v); return this; }
        public Builder credentialPolicy(CredentialPolicy v) { credentialPolicy = v; return this; }
        public Builder authScheme(AuthScheme... v) { authScheme = List.of(v); return this; }
        public Builder headers(List<Map.Entry<String, String>> v) { headers = v; return this; }
        public Builder host(HostSpec v) { host = v; return this; }
        public Builder loginHint(String v) { loginHint = v; return this; }
        public Builder backend(String v) { backend = v; return this; }
        public Builder backendOptions(Map<String, String> v) { backendOptions = v; return this; }
        public Builder systemPrefix(String v) { systemPrefix = v; return this; }
        public Builder baseUrl(String v) { baseUrl = v; return this; }
        public AccessPolicy build() {
            return new AccessPolicy(provider, supports, authModes, enterpriseVariants, envKeys, credentialPolicy, authScheme, headers, host, loginHint, backend, backendOptions, systemPrefix, baseUrl);
        }
    }
}
