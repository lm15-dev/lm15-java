package dev.lm15.auth;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** RFC 3339 timestamps: parse any offset form, format as {@code YYYY-MM-DDTHH:MM:SSZ} (whole seconds, UTC). */
public final class Rfc3339 {
    private Rfc3339() {}

    private static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    public static Instant parse(String value) {
        String text = value.strip();
        if (text.endsWith("Z") || text.endsWith("z")) text = text.substring(0, text.length() - 1) + "+00:00";
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            // no offset at all: read as UTC
            try {
                return java.time.LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException("not an RFC 3339 timestamp: " + value, e2);
            }
        }
    }

    public static String format(Instant instant) {
        return OUT.format(instant.getEpochSecond() >= 0 ? Instant.ofEpochSecond(instant.getEpochSecond()) : instant);
    }

    /** Seconds since the epoch → canonical string. */
    public static String fromEpochSeconds(long seconds) {
        return format(Instant.ofEpochSecond(seconds));
    }
}
