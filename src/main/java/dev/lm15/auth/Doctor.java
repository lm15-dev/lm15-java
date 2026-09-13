package dev.lm15.auth;

import dev.lm15.registry.ProviderDefinition;
import dev.lm15.registry.Registry;
import dev.lm15.types.ValidationException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code explainAuth}: why is my key (not) being used? Walks exactly the
 * AUTH-1 chain {@link AuthChain} walks (divergence is a bug), performs no
 * network I/O, prints the resolved host settings by name and value, and
 * never renders a secret (spec/auth.md AUTH-7, AUTH-5).
 */
public final class Doctor {
    private Doctor() {}

    /** The report: every rung in chain order, whether something can supply the credential, and the resolved host settings. */
    public record AuthReport(String provider, boolean configured, List<AuthChain.Step> steps, List<Map.Entry<String, String>> settings) {
        public AuthReport {
            steps = steps == null ? List.of() : List.copyOf(steps);
            settings = settings == null ? List.of() : List.copyOf(settings);
        }

        public AuthChain.Step selected() {
            for (AuthChain.Step s : steps) if (s.state().equals("selected")) return s;
            return null;
        }

        public String describe() {
            List<String> lines = new ArrayList<>();
            lines.add("auth for provider '" + provider + "':");
            for (AuthChain.Step s : steps) lines.add("  " + s.describe());
            List<String> unprobed = steps.stream().filter(s -> s.state().equals("unprobed")).map(AuthChain.Step::source).toList();
            AuthChain.Step selected = selected();
            if (configured && selected != null) {
                lines.add("  configured: yes — " + selected.source());
                if (!unprobed.isEmpty()) lines.add("  note: " + String.join(", ", unprobed) + " run first at request time and may win");
            } else if (configured) {
                lines.add("  configured: probably — " + String.join(", ", unprobed) + " (unprobed offline)");
            } else {
                lines.add("  configured: no");
            }
            for (Map.Entry<String, String> s : settings) lines.add("  setting " + s.getKey() + ": " + s.getValue());
            return String.join("\n", lines);
        }

        @Override public String toString() { return describe(); }
    }

    /** Explain {@code provider} over the process environment and the default store paths. */
    public static AuthReport explainAuth(String provider) {
        return explainAuth(provider, null, null, null, null, null, null);
    }

    /**
     * Explain, rung by rung, how {@code provider}'s credential resolves over
     * the given inputs only (the harness owns every one of them).
     *
     * @param provider        the provider string, either spelling
     * @param apiKeys         explicit entries keyed by provider; may be null
     * @param env             the complete environment (null = the process environment)
     * @param credentialsPath an override for the provider's stored-login file
     * @param files           a sandbox file map ({@code ~/}-relative or absolute keys) replacing the filesystem
     * @param home            the home directory {@code ~} resolves against
     * @param settings        explicit host settings for cloud doors
     */
    public static AuthReport explainAuth(String provider, Map<String, CredentialProvider> apiKeys, Map<String, String> env, Path credentialsPath,
                                         Map<String, String> files, String home, Map<String, String> settings) {
        if (provider == null) throw ValidationException.type("provider must be a provider name");
        String canonical = Registry.canonicalProvider(provider);
        ProviderDefinition definition = Registry.lookup(canonical);
        if (definition == null) {
            List<String> known = new ArrayList<>(Registry.PROVIDERS.keySet());
            java.util.Collections.sort(known);
            throw ValidationException.value("Unknown provider '" + provider + "'. Known providers: " + String.join(", ", known));
        }
        AuthChain.Resolved resolved = AuthChain.resolve(definition.access(), apiKeys, env, credentialsPath, files, home, settings);
        List<Map.Entry<String, String>> shown = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(resolved.settings()).entrySet()) shown.add(Map.entry(e.getKey(), e.getValue()));
        if (resolved.settingError() != null) shown.add(Map.entry("error", resolved.settingError()));
        return new AuthReport(canonical, resolved.configured(), resolved.steps(), shown);
    }
}
