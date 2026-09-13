package dev.lm15.types;

import java.nio.file.Path;
import java.util.List;

/** Video content, addressed by exactly one of data/url/fileId/path; default media type {@code video/mp4}. */
public record VideoPart(String mediaType, String data, String url, String fileId, Path path,
                        List<ContinuationState> continuation) implements MediaPart {
    public static final String DEFAULT_MEDIA_TYPE = "video/mp4";

    public VideoPart {
        if (mediaType == null || mediaType.isEmpty()) throw ValidationException.value("VideoPart requires media_type");
        MediaPart.Media.validateAddress("VideoPart", data, url, fileId, path);
        if (data != null) MediaPart.Media.validateBase64("VideoPart", data);
        continuation = Check.continuation(continuation);
    }

    @Override public PartType type() { return PartType.VIDEO; }
    @Override public VideoPart withContinuation(List<ContinuationState> c) {
        return new VideoPart(mediaType, data, url, fileId, path, c);
    }

    @Override public String toString() {
        return "VideoPart(media_type=" + mediaType + ", data=" + (data == null ? "null" : "<base64: " + MediaPart.Media.payload("VideoPart", data).length() + " chars>")
            + ", url=" + url + ", file_id=" + fileId + ", path=" + path + ")";
    }
}
