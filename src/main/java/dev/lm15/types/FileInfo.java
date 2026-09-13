package dev.lm15.types;

import dev.lm15.json.JsonObject;

/** A snapshot of one provider-side stored file; {@code id} goes into a media part's {@code fileId}. */
public record FileInfo(String id, String filename, String mediaType, Long sizeBytes, String createdAt, String expiresAt,
                       FileReadiness readiness, Boolean downloadable, JsonObject providerData) {
    public FileInfo {
        Check.nonEmpty(id, "FileInfo.id");
        Check.optNonEmpty(filename, "FileInfo.filename");
        Check.optNonEmpty(mediaType, "FileInfo.media_type");
        Check.nonNegative(sizeBytes, "FileInfo.size_bytes");
        Check.optNonEmpty(createdAt, "FileInfo.created_at");
        Check.optNonEmpty(expiresAt, "FileInfo.expires_at");
        if (readiness == null) readiness = FileReadiness.READY;
        Check.jsonObject(providerData, "provider_data", false);
    }

    public boolean ready() { return readiness == FileReadiness.READY; }

    public static Builder builder(String id) { return new Builder(id); }

    public static final class Builder {
        private final String id;
        private String filename, mediaType, createdAt, expiresAt;
        private Long sizeBytes;
        private FileReadiness readiness = FileReadiness.READY;
        private Boolean downloadable;
        private JsonObject providerData;

        Builder(String id) { this.id = id; }
        public Builder filename(String v) { filename = v; return this; }
        public Builder mediaType(String v) { mediaType = v; return this; }
        public Builder sizeBytes(Long v) { sizeBytes = v; return this; }
        public Builder createdAt(String v) { createdAt = v; return this; }
        public Builder expiresAt(String v) { expiresAt = v; return this; }
        public Builder readiness(FileReadiness v) { readiness = v; return this; }
        public Builder downloadable(Boolean v) { downloadable = v; return this; }
        public Builder providerData(JsonObject v) { providerData = v; return this; }
        public FileInfo build() { return new FileInfo(id, filename, mediaType, sizeBytes, createdAt, expiresAt, readiness, downloadable, providerData); }
    }
}
