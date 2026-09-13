package dev.lm15.types;

/**
 * A media chunk: at most one of data/url/fileId (INV-018), possibly none
 * (metadata only); {@code data} may be unaligned partial base64. Final media
 * validation happens at assembly.
 */
public record ImageDelta(String data, String url, String fileId, int partIndex, String mediaType) implements Delta {
    public ImageDelta {
        Check.partIndex(partIndex);
        int count = (data != null ? 1 : 0) + (url != null ? 1 : 0) + (fileId != null ? 1 : 0);
        if (count > 1) throw ValidationException.value("ImageDelta can include at most one of data, url, or file_id");
        Check.optNonEmpty(mediaType, "ImageDelta.media_type");
    }

    public static ImageDelta ofData(String data, int partIndex, String mediaType) { return new ImageDelta(data, null, null, partIndex, mediaType); }
    public static ImageDelta ofUrl(String url, int partIndex, String mediaType) { return new ImageDelta(null, url, null, partIndex, mediaType); }
    public static ImageDelta ofFileId(String fileId, int partIndex, String mediaType) { return new ImageDelta(null, null, fileId, partIndex, mediaType); }

    @Override public DeltaType type() { return DeltaType.IMAGE; }

    @Override public String toString() {
        return "ImageDelta(data=" + (data == null ? "null" : "<base64: " + data.length() + " chars>") + ", url=" + url
            + ", file_id=" + fileId + ", part_index=" + partIndex + ", media_type=" + mediaType + ")";
    }
}
