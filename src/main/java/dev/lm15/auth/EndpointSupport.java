package dev.lm15.auth;

import java.util.Set;

/** The endpoint surfaces an access path carries (spec/auth.md AUTH-10 {@code supports}). */
public record EndpointSupport(boolean complete, boolean stream, boolean live, boolean files, boolean batches, boolean images,
                              boolean speech, boolean video, boolean responsesApi, boolean models, boolean caches, Set<String> extra) {
    public EndpointSupport {
        extra = extra == null ? Set.of() : Set.copyOf(extra);
    }

    public static final EndpointSupport CHAT = new Builder().build();

    public boolean supports(String name) {
        if (extra.contains(name)) return true;
        return switch (name) {
            case "complete" -> complete;
            case "stream" -> stream;
            case "live" -> live;
            case "files" -> files;
            case "batches" -> batches;
            case "images" -> images;
            case "speech" -> speech;
            case "video" -> video;
            case "responses_api" -> responsesApi;
            case "models" -> models;
            case "caches" -> caches;
            default -> false;
        };
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private boolean complete = true, stream = true, live, files, batches, images, speech, video, responsesApi, models, caches;
        private Set<String> extra = Set.of();

        public Builder complete(boolean v) { complete = v; return this; }
        public Builder stream(boolean v) { stream = v; return this; }
        public Builder live(boolean v) { live = v; return this; }
        public Builder files(boolean v) { files = v; return this; }
        public Builder batches(boolean v) { batches = v; return this; }
        public Builder images(boolean v) { images = v; return this; }
        public Builder speech(boolean v) { speech = v; return this; }
        public Builder video(boolean v) { video = v; return this; }
        public Builder responsesApi(boolean v) { responsesApi = v; return this; }
        public Builder models(boolean v) { models = v; return this; }
        public Builder caches(boolean v) { caches = v; return this; }
        public Builder extra(Set<String> v) { extra = v; return this; }
        public EndpointSupport build() { return new EndpointSupport(complete, stream, live, files, batches, images, speech, video, responsesApi, models, caches, extra); }
    }
}
