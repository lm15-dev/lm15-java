package dev.lm15.types;

import java.nio.file.Path;
import java.util.List;

/** An image, addressed by exactly one of data/url/fileId/path; default media type {@code image/png}. */
public record ImagePart(String mediaType, String data, String url, String fileId, Path path, ImageDetail detail,
                        List<ContinuationState> continuation) implements MediaPart {
    public static final String DEFAULT_MEDIA_TYPE = "image/png";

    public ImagePart {
        if (mediaType == null || mediaType.isEmpty()) throw ValidationException.value("ImagePart requires media_type");
        MediaPart.Media.validateAddress("ImagePart", data, url, fileId, path);
        if (data != null) MediaPart.Media.validateBase64("ImagePart", data);
        continuation = Check.continuation(continuation);
    }

    @Override public PartType type() { return PartType.IMAGE; }
    @Override public ImagePart withContinuation(List<ContinuationState> c) {
        return new ImagePart(mediaType, data, url, fileId, path, detail, c);
    }
    public ImagePart withDetail(ImageDetail d) { return new ImagePart(mediaType, data, url, fileId, path, d, continuation); }

    @Override public String toString() {
        return "ImagePart(media_type=" + mediaType + ", data=" + (data == null ? "null" : "<base64: " + MediaPart.Media.payload("ImagePart", data).length() + " chars>")
            + ", url=" + url + ", file_id=" + fileId + ", path=" + path + (detail == null ? "" : ", detail=" + detail) + ")";
    }
}
