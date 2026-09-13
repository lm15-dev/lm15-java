package dev.lm15.dialects.openaichat;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The image media types Python's {@code mimetypes.guess_type} knows, keyed by
 * extension: MAP-12 rule 4 guesses an ImagePart's media type from a URL's
 * path (the wire carries none) and keeps only {@code image/*} answers.
 */
final class MediaTypes {
    private MediaTypes() {}

    private static final Map<String, String> IMAGE_TYPES = Map.ofEntries(
        Map.entry(".png", "image/png"),
        Map.entry(".jpg", "image/jpeg"),
        Map.entry(".jpeg", "image/jpeg"),
        Map.entry(".jpe", "image/jpeg"),
        Map.entry(".gif", "image/gif"),
        Map.entry(".webp", "image/webp"),
        Map.entry(".bmp", "image/bmp"),
        Map.entry(".svg", "image/svg+xml"),
        Map.entry(".tif", "image/tiff"),
        Map.entry(".tiff", "image/tiff"),
        Map.entry(".ico", "image/vnd.microsoft.icon"),
        Map.entry(".heic", "image/heic"),
        Map.entry(".heif", "image/heif"),
        Map.entry(".avif", "image/avif"),
        Map.entry(".pnm", "image/x-portable-anymap"),
        Map.entry(".pbm", "image/x-portable-bitmap"),
        Map.entry(".pgm", "image/x-portable-graymap"),
        Map.entry(".ppm", "image/x-portable-pixmap"),
        Map.entry(".ras", "image/x-cmu-raster"),
        Map.entry(".rgb", "image/x-rgb"),
        Map.entry(".xbm", "image/x-xbitmap"),
        Map.entry(".xpm", "image/x-xpixmap"),
        Map.entry(".xwd", "image/x-xwindowdump"),
        Map.entry(".ief", "image/ief"));

    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.\\-]*):(.*)$", Pattern.DOTALL);

    /** {@code mimetypes.guess_type(url)[0]} when it is an {@code image/*} type; else null. */
    static String guessImageType(String url) {
        String path = url;
        var m = SCHEME.matcher(url);
        if (m.matches() && m.group(1).length() > 1) {
            // urlparse(url).path: after an optional //netloc, up to the query or fragment.
            String rest = m.group(2);
            if (rest.startsWith("//")) {
                int slash = rest.indexOf('/', 2);
                rest = slash < 0 ? "" : rest.substring(slash);
            }
            int cut = rest.length();
            int q = rest.indexOf('?');
            int h = rest.indexOf('#');
            if (q >= 0) cut = Math.min(cut, q);
            if (h >= 0) cut = Math.min(cut, h);
            path = rest.substring(0, cut);
        }
        String segment = path.substring(path.lastIndexOf('/') + 1);
        int first = 0;
        while (first < segment.length() && segment.charAt(first) == '.') first++;
        int dot = segment.lastIndexOf('.');
        if (dot < 0 || dot < first) return null;
        String ext = segment.substring(dot);
        String type = IMAGE_TYPES.get(ext);
        if (type == null) type = IMAGE_TYPES.get(ext.toLowerCase());
        return type;
    }
}
