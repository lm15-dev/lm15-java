package dev.lm15.errors;

import java.util.List;

/** The "To fix:" guidance appended to auth and configuration messages (never pinned by the contract). */
final class Guidance {
    private Guidance() {}

    static final String MARKER = "\n\n  To fix:";

    static String auth(String message, String provider, List<String> envKeys, String credentialHint) {
        String base = message == null ? "" : message;
        StringBuilder g = new StringBuilder();
        if (credentialHint != null && !credentialHint.isEmpty()) {
            g.append("\n\n  To fix:\n    - ").append(credentialHint).append('\n');
        } else {
            g.append("\n\n  To fix:\n    - Check that your API key is correct and not expired\n");
            if (envKeys != null && !envKeys.isEmpty()) {
                g.append("    - Pass the key explicitly (api_key, or RouterConfig api_keys), or on a host with an environment set ")
                    .append(String.join(" or ", envKeys.stream().map(k -> k + "=...").toList())).append('\n');
            } else {
                g.append("    - Pass the key explicitly (api_key, or RouterConfig api_keys)\n");
            }
            if (provider != null) g.append("    - Verify your ").append(provider).append(" account/project has access\n");
        }
        return append(base, g.toString());
    }

    static String notConfigured(String message, String provider, List<String> envKeys, String credentialHint) {
        String base = message == null ? "" : message;
        StringBuilder g = new StringBuilder();
        if (credentialHint != null && !credentialHint.isEmpty()) {
            g.append("\n\n  To fix:\n    - ").append(credentialHint).append('\n');
        } else if ((envKeys != null && !envKeys.isEmpty()) || provider != null) {
            g.append("\n\n  To fix:\n");
            if (envKeys != null && !envKeys.isEmpty()) {
                g.append("    - Pass the key explicitly (api_key, or RouterConfig api_keys), or on a host with an environment set ")
                    .append(String.join(" or ", envKeys.stream().map(k -> k + "=...").toList())).append('\n');
            }
            if (provider != null) g.append("    - Configure credentials for ").append(provider).append('\n');
        }
        return g.length() == 0 ? base : append(base, g.toString());
    }

    static String append(String message, String guidance) {
        if (message.contains(guidance.strip())) return message;
        return message.stripTrailing() + guidance;
    }

    static String stripGuidance(String message) {
        int i = message.indexOf(MARKER);
        return i < 0 ? message : message.substring(0, i);
    }
}
