package dev.lm15.types;

import java.util.ArrayList;
import java.util.List;

/**
 * A reusable prompt beginning: {@code prefix} (a Request with a default
 * config) plus, on providers with the resource tier, the stored object.
 * {@link #request(List)} appends messages and sets the cache boundary at the seam.
 */
public record CachedPrefix(Request prefix, CacheInfo resource) {
    public CachedPrefix {
        if (prefix == null) throw ValidationException.type("CachedPrefix.prefix must be a Request");
        if (!prefix.config().isDefault()) {
            throw ValidationException.value("CachedPrefix.prefix must carry a default Config: a cached object has no generation settings");
        }
        if (resource != null && !resource.model().equals(prefix.model())) {
            throw ValidationException.value("CachedPrefix.resource.model must equal the prefix model (a stored cache belongs to one model)");
        }
    }

    public String id() { return resource == null ? null : resource.id(); }
    public String expiresAt() { return resource == null ? null : resource.expiresAt(); }

    /** The CacheConfig that marks the seam between prefix and suffix. */
    public CacheConfig cacheConfig() {
        return CacheConfig.builder().prefixUntilIndex(prefix.messages().size() - 1).resource(id()).build();
    }

    public Request request(String userText) { return request(List.of(Message.user(userText)), null); }
    public Request request(Message message) { return request(List.of(message), null); }
    public Request request(List<Message> messages) { return request(messages, null); }

    /** Append {@code messages}; {@code config} supplies generation settings and must not carry {@code cache}. */
    public Request request(List<Message> messages, Config config) {
        if (messages == null || messages.isEmpty()) throw ValidationException.type("messages must be a Message or a non-empty sequence of Messages");
        Config base = config == null ? Config.DEFAULT : config;
        if (base.cache() != null) throw ValidationException.value("config.cache is decided by the CachedPrefix; leave it unset");
        List<Message> all = new ArrayList<>(prefix.messages());
        all.addAll(messages);
        return new Request(prefix.model(), all, prefix.system(), prefix.tools(), base.withCache(cacheConfig()));
    }

    /** Append a suffix Request (same model, no system, no tools; its config is used when none is given). */
    public Request request(Request suffix, Config config) {
        if (!suffix.model().equals(prefix.model())) throw ValidationException.value("suffix Request model must equal the prefix model");
        if (suffix.system() != null || !suffix.tools().isEmpty()) {
            throw ValidationException.value("suffix Request cannot redefine system or tools: the prefix owns them");
        }
        Config c = config != null ? config : (suffix.config().isDefault() ? null : suffix.config());
        return request(suffix.messages(), c);
    }
}
