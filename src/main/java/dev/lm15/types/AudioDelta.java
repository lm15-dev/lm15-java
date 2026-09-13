package dev.lm15.types;

/**
 * A media chunk: at most one of data/url/fileId (INV-018), possibly none
 * (metadata only); {@code data} may be unaligned partial base64. Final media
 * validation happens at assembly.
 */
public record AudioDelta(String data, String url, String fileId, int partIndex, String mediaType) implements Delta {
    public AudioDelta {
        Check.partIndex(partIndex);
        int count = (data != null ? 1 : 0) + (url != null ? 1 : 0) + (fileId != null ? 1 : 0);
        if (count > 1) throw ValidationException.value("AudioDelta can include at most one of data, url, or file_id");
        Check.optNonEmpty(mediaType, "AudioDelta.media_type");
    }

    public static AudioDelta ofData(String data, int partIndex, String mediaType) { return new AudioDelta(data, null, null, partIndex, mediaType); }
    public static AudioDelta ofUrl(String url, int partIndex, String mediaType) { return new AudioDelta(null, url, null, partIndex, mediaType); }
    public static AudioDelta ofFileId(String fileId, int partIndex, String mediaType) { return new AudioDelta(null, null, fileId, partIndex, mediaType); }

    @Override public DeltaType type() { return DeltaType.AUDIO; }

    @Override public String toString() {
        return "AudioDelta(data=" + (data == null ? "null" : "<base64: " + data.length() + " chars>") + ", url=" + url
            + ", file_id=" + fileId + ", part_index=" + partIndex + ", media_type=" + mediaType + ")";
    }
}
