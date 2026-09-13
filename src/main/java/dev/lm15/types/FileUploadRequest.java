package dev.lm15.types;

import dev.lm15.json.JsonObject;

import java.nio.file.Path;

/** A file upload: exactly one of {@code bytesData} or {@code path}; files are account-scoped (no model). */
public record FileUploadRequest(String filename, byte[] bytesData, String mediaType, JsonObject extensions, Path path) {
    public FileUploadRequest {
        Check.nonEmpty(filename, "FileUploadRequest.filename");
        if (bytesData == null && path == null) throw ValidationException.type("FileUploadRequest requires bytes_data or path");
        if (bytesData != null && path != null) throw ValidationException.value("FileUploadRequest requires exactly one of bytes_data or path");
        if (bytesData != null && bytesData.length == 0) throw ValidationException.value("bytes_data is required");
        if (path != null && path.toString().isEmpty()) throw ValidationException.value("path cannot be empty");
        if (mediaType == null) mediaType = "application/octet-stream";
        Check.nonEmpty(mediaType, "FileUploadRequest.media_type");
        extensions = Check.extensions(extensions);
        if (bytesData != null) bytesData = bytesData.clone();
    }

    public FileUploadRequest(String filename, byte[] bytesData, String mediaType) { this(filename, bytesData, mediaType, null, null); }

    /** The bytes: inline, or read from the path. */
    public byte[] bytes() {
        if (bytesData != null) return bytesData.clone();
        try {
            return java.nio.file.Files.readAllBytes(path);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override public boolean equals(Object o) {
        return o instanceof FileUploadRequest f && filename.equals(f.filename) && java.util.Arrays.equals(bytesData, f.bytesData)
            && mediaType.equals(f.mediaType) && java.util.Objects.equals(extensions, f.extensions) && java.util.Objects.equals(path, f.path);
    }

    @Override public int hashCode() { return java.util.Objects.hash(filename, java.util.Arrays.hashCode(bytesData), mediaType, extensions, path); }

    @Override public String toString() {
        return "FileUploadRequest(filename=" + filename + ", bytes_data=" + (bytesData == null ? "null" : "<bytes: " + bytesData.length + " bytes>")
            + ", media_type=" + mediaType + ", extensions=" + extensions + ", path=" + path + ")";
    }
}
