package dev.lm15.types;

import java.nio.file.Path;
import java.util.Base64;

/**
 * A media part addressed by exactly one of {@code data} (base64), {@code url},
 * {@code fileId} or {@code path} (INV-010..012). {@code mediaType} is always
 * emitted and non-empty.
 */
public sealed interface MediaPart extends Part permits ImagePart, AudioPart, VideoPart, DocumentPart, BinaryPart {
    String mediaType();
    String data();
    String url();
    String fileId();
    Path path();

    /** The decoded inline bytes, or the file at {@code path}. */
    default byte[] bytes() {
        if (data() != null) return Media.decode(type().wire(), data());
        if (path() != null) {
            try {
                return java.nio.file.Files.readAllBytes(path());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        throw ValidationException.value(getClass().getSimpleName()
            + " has no inline data or path; fetch url/file_id-addressed media before decoding");
    }

    /** Base64 helpers shared by the media parts and the live events (INV-012). */
    final class Media {
        private Media() {}

        private static final java.util.regex.Pattern BASE64 = java.util.regex.Pattern.compile("^[A-Za-z0-9+/]*={0,2}$");

        /** Exactly one non-empty media source. */
        static void validateAddress(String partType, String data, String url, String fileId, Path path) {
            int count = (data != null ? 1 : 0) + (url != null ? 1 : 0) + (fileId != null ? 1 : 0) + (path != null ? 1 : 0);
            if (count != 1) {
                throw ValidationException.value(partType + " requires exactly one of data, url, file_id, or path");
            }
            if (path != null) {
                if (path.toString().isEmpty()) throw ValidationException.value(partType + " path cannot be empty");
                return;
            }
            String name = data != null ? "data" : url != null ? "url" : "file_id";
            String value = data != null ? data : url != null ? url : fileId;
            if (value.isEmpty()) throw ValidationException.value(partType + " " + name + " cannot be empty");
        }

        /** The base64 payload of a raw string or data URI, whitespace collapsed. */
        public static String payload(String partType, String data) {
            if (data == null) {
                throw ValidationException.value(partType + " has no inline data; fetch url/file_id-addressed media before decoding");
            }
            if (data.isEmpty()) throw ValidationException.value(partType + ".data cannot be empty");
            if (data.startsWith("data:") && data.contains(";base64,")) {
                data = data.substring(data.indexOf(";base64,") + ";base64,".length());
            }
            boolean hasWs = false;
            for (int i = 0; i < data.length(); i++) {
                if (Character.isWhitespace(data.charAt(i))) { hasWs = true; break; }
            }
            if (!hasWs) return data;
            StringBuilder sb = new StringBuilder(data.length());
            for (int i = 0; i < data.length(); i++) {
                char c = data.charAt(i);
                if (!Character.isWhitespace(c)) sb.append(c);
            }
            return sb.toString();
        }

        /** Shape check without decoding (INV-012). */
        public static void validateBase64(String partType, String data) {
            String p = payload(partType, data);
            if (p.length() % 4 != 0 || !BASE64.matcher(p).matches()) {
                throw ValidationException.value(partType + ".data must be a valid base64 string");
            }
        }

        public static byte[] decode(String partType, String data) {
            try {
                return Base64.getDecoder().decode(payload(partType, data));
            } catch (IllegalArgumentException e) {
                throw ValidationException.value(partType + ".data must be a valid base64 string");
            }
        }

        public static String encode(byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }

        /** {@code image/png} for {@code x.png}, etc.; null when unknown. */
        public static String guessMediaType(Path path) {
            try {
                return java.nio.file.Files.probeContentType(path);
            } catch (java.io.IOException e) {
                return null;
            }
        }
    }
}
