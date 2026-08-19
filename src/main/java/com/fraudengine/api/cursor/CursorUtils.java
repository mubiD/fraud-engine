package com.fraudengine.api.cursor;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public final class CursorUtils {

    private static final char SEPARATOR = '|';

    private CursorUtils() {}

    public record DecodedCursor(Instant timestamp, UUID id) {}

    public static String encode(Instant timestamp, UUID id) {
        String raw = timestamp.toString() + SEPARATOR + id.toString();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
    }

    public static DecodedCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor));
            int sep = raw.indexOf(SEPARATOR);
            if (sep < 0) throw new IllegalArgumentException("missing separator");
            return new DecodedCursor(
                    Instant.parse(raw.substring(0, sep)),
                    UUID.fromString(raw.substring(sep + 1)));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid or malformed cursor: " + cursor, e);
        }
    }
}
