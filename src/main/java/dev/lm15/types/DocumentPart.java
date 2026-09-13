package dev.lm15.types;

import java.nio.file.Path;
import java.util.List;

/** Document content, addressed by exactly one of data/url/fileId/path; default media type {@code application/pdf}. */
public record DocumentPart(String mediaType, String data, String url, String fileId, Path path,
                        List<ContinuationState> continuation) implements MediaPart {
    public static final String DEFAULT_MEDIA_TYPE = "application/pdf";

    public DocumentPart {
        if (mediaType == null || mediaType.isEmpty()) throw ValidationException.value("DocumentPart requires media_type");
        MediaPart.Media.validateAddress("DocumentPart", data, url, fileId, path);
        if (data != null) MediaPart.Media.validateBase64("DocumentPart", data);
        continuation = Check.continuation(continuation);
    }

    @Override public PartType type() { return PartType.DOCUMENT; }
    @Override public DocumentPart withContinuation(List<ContinuationState> c) {
        return new DocumentPart(mediaType, data, url, fileId, path, c);
    }

    @Override public String toString() {
        return "DocumentPart(media_type=" + mediaType + ", data=" + (data == null ? "null" : "<base64: " + MediaPart.Media.payload("DocumentPart", data).length() + " chars>")
            + ", url=" + url + ", file_id=" + fileId + ", path=" + path + ")";
    }
}
