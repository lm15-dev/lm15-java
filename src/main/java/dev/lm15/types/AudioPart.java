package dev.lm15.types;

import java.nio.file.Path;
import java.util.List;

/** Audio content, addressed by exactly one of data/url/fileId/path; default media type {@code audio/wav}. */
public record AudioPart(String mediaType, String data, String url, String fileId, Path path,
                        List<ContinuationState> continuation) implements MediaPart {
    public static final String DEFAULT_MEDIA_TYPE = "audio/wav";

    public AudioPart {
        if (mediaType == null || mediaType.isEmpty()) throw ValidationException.value("AudioPart requires media_type");
        MediaPart.Media.validateAddress("AudioPart", data, url, fileId, path);
        if (data != null) MediaPart.Media.validateBase64("AudioPart", data);
        continuation = Check.continuation(continuation);
    }

    @Override public PartType type() { return PartType.AUDIO; }
    @Override public AudioPart withContinuation(List<ContinuationState> c) {
        return new AudioPart(mediaType, data, url, fileId, path, c);
    }

    @Override public String toString() {
        return "AudioPart(media_type=" + mediaType + ", data=" + (data == null ? "null" : "<base64: " + MediaPart.Media.payload("AudioPart", data).length() + " chars>")
            + ", url=" + url + ", file_id=" + fileId + ", path=" + path + ")";
    }
}
