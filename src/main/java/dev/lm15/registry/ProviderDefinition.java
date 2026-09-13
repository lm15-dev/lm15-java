package dev.lm15.registry;

import dev.lm15.auth.AccessPolicy;
import dev.lm15.auth.CredentialPolicy;
import dev.lm15.auth.EndpointSupport;
import dev.lm15.types.ValidationException;

/**
 * Everything lm15 knows about one named provider, as a value: the wire
 * dialect, the access policy, the compat preset (bound entries), the
 * placeholder key of a keyless local server, and human notes.
 */
public record ProviderDefinition(String id, DialectId dialect, String dialectImpl, AccessPolicy access, String compat,
                                 String placeholderKey, String consoleUrl, String note) {
    public ProviderDefinition {
        if (!id.equals(Registry.canonicalProvider(id))) throw ValidationException.value("provider id must be hyphenated: " + id);
        if (!Registry.canonicalProvider(access.provider()).equals(id)) throw ValidationException.value(id + ": access policy names provider " + access.provider());
        if (placeholderKey != null && !access.envKeys().isEmpty()) throw ValidationException.value(id + ": a keyless local server declares no env_keys");
        if (dialectImpl == null) dialectImpl = dialect.wire();
        if (note == null) note = "";
    }

    public boolean hosted() { return access.host() != null; }

    public java.util.List<String> envKeys() { return access.envKeys(); }
    public CredentialPolicy credentialPolicy() { return access.credentialPolicy(); }
    public EndpointSupport supports() { return access.supports(); }
    public String baseUrl() { return access.baseUrl(); }
}
