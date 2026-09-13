package dev.lm15.types;

import java.nio.file.Path;
import java.util.List;

/** Binary content, addressed by exactly one of data/url/fileId/path; default media type {@code application/octet-stream}. */
public record BinaryPart(String mediaType, String data, String url, String fileId, Path path,
                        List<ContinuationState> continuation) implements MediaPart {
    public static final String DEFAULT_MEDIA_TYPE = "application/octet-stream";

    public BinaryPart {
        if (mediaType == null || mediaType.isEmpty()) throw ValidationException.value("BinaryPart requires media_type");
        MediaPart.Media.validateAddress("BinaryPart", data, url, fileId, path);
        if (data != null) MediaPart.Media.validateBase64("BinaryPart", data);
        continuation = Check.continuation(continuation);
    }

    @Override public PartType type() { return PartType.BINARY; }
    @Override public BinaryPart withContinuation(List<ContinuationState> c) {
        return new BinaryPart(mediaType, data, url, fileId, path, c);
    }

    @Override public String toString() {
        return "BinaryPart(media_type=" + mediaType + ", data=" + (data == null ? "null" : "<base64: " + MediaPart.Media.payload("BinaryPart", data).length() + " chars>")
            + ", url=" + url + ", file_id=" + fileId + ", path=" + path + ")";
    }
}
