package dev.lm15.types;

/**
 * Universal prompt-cache configuration (MAP-6): intents, mapped by each
 * adapter to the best tier it has. {@code mode=off} forbids everything else
 * (INV-027); {@code prefix} and {@code prefixUntilIndex} are exclusive.
 */
public record CacheConfig(CacheMode mode, CacheRetention retention, String key, Integer prefixUntilIndex,
                          CachePrefix prefix, String resource) {
    public CacheConfig {
        Check.required(mode, "cache mode");
        Check.optNonEmpty(key, "CacheConfig.key");
        Check.optNonEmpty(resource, "CacheConfig.resource");
        if (mode == CacheMode.OFF && (retention != null || key != null || prefix != null || prefixUntilIndex != null || resource != null)) {
            throw ValidationException.value("CacheConfig(mode='off') cannot specify retention, key, prefix, prefix_until_index, or resource");
        }
        if (prefix != null && prefixUntilIndex != null) {
            throw ValidationException.value("CacheConfig cannot specify both prefix and prefix_until_index");
        }
        Check.nonNegative(prefixUntilIndex, "prefix_until_index");
    }

    public static final CacheConfig AUTO = new CacheConfig(CacheMode.AUTO, null, null, null, null, null);
    public static final CacheConfig OFF = new CacheConfig(CacheMode.OFF, null, null, null, null, null);

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private CacheMode mode = CacheMode.AUTO;
        private CacheRetention retention;
        private String key;
        private Integer prefixUntilIndex;
        private CachePrefix prefix;
        private String resource;

        public Builder mode(CacheMode v) { mode = v; return this; }
        public Builder retention(CacheRetention v) { retention = v; return this; }
        public Builder key(String v) { key = v; return this; }
        public Builder prefixUntilIndex(Integer v) { prefixUntilIndex = v; return this; }
        public Builder prefix(CachePrefix v) { prefix = v; return this; }
        public Builder resource(String v) { resource = v; return this; }
        public CacheConfig build() { return new CacheConfig(mode, retention, key, prefixUntilIndex, prefix, resource); }
    }
}
