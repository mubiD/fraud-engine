package com.fraudengine.api.cursor;

import com.fraudengine.api.dto.PagedResponse;
import org.springframework.data.domain.Slice;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public final class CursorUtils {

    private static final char SEPARATOR = '|';

    private CursorUtils() {}

    public record DecodedCursor(Instant timestamp, UUID id) {}

    public static String encode(Instant timestamp, UUID id) {
        String raw = timestamp.toString() + SEPARATOR + id.toString();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
    }

    // Shared by every cursor-paginated endpoint (transactions, flagged/pending-review/passed,
    // merchant-flagged) so there's one implementation of "encode the last row's cursor key,
    // only if there's a next page" instead of one per controller method.
    public static <S, D> PagedResponse<D> toPagedResponse(Slice<S> slice, Function<S, D> mapper,
                                                           Function<D, Instant> timestampOf,
                                                           Function<D, UUID> idOf) {
        List<D> data = slice.getContent().stream().map(mapper).toList();

        String nextCursor = slice.hasNext() && !data.isEmpty()
                ? encode(timestampOf.apply(data.get(data.size() - 1)), idOf.apply(data.get(data.size() - 1)))
                : null;

        return PagedResponse.<D>builder()
                .data(data)
                .nextCursor(nextCursor)
                .hasMore(slice.hasNext())
                .build();
    }

    // Every controller needs "decode if present, else null" — centralized here instead of
    // each controller reimplementing the same null-check around decode().
    public static DecodedCursor decodeOrNull(String cursor) {
        return cursor != null ? decode(cursor) : null;
    }

    public static DecodedCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor));
            int sep = raw.indexOf(SEPARATOR);
            // IllegalStateException here (not IllegalArgumentException) deliberately: Base64's
            // own decode() above throws IllegalArgumentException on malformed input, and a
            // single catch(Exception) below wraps every failure path uniformly. A previous
            // version caught IllegalArgumentException separately and rethrew it verbatim,
            // assuming it only ever came from this method's own "missing separator" check —
            // but Base64's malformed-input IllegalArgumentException slipped through the same
            // way, leaking its raw message ("Last unit does not have enough valid bits") to
            // API clients instead of a clean "Invalid or malformed cursor" detail.
            if (sep < 0) throw new IllegalStateException("missing separator");
            return new DecodedCursor(
                    Instant.parse(raw.substring(0, sep)),
                    UUID.fromString(raw.substring(sep + 1)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid or malformed cursor: " + cursor, e);
        }
    }
}
