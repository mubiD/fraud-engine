package com.fraudengine.api.cursor;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorUtilsTest {

    @Test
    void encodeThenDecode_roundTrips() {
        Instant ts = Instant.parse("2026-07-23T09:00:00Z");
        UUID id = UUID.randomUUID();

        CursorUtils.DecodedCursor decoded = CursorUtils.decode(CursorUtils.encode(ts, id));

        assertThat(decoded.timestamp()).isEqualTo(ts);
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    void decode_malformedBase64_throwsCleanMessageWithoutLeakingDecoderInternals() {
        // "not-valid-base64!!!" is not legal Base64 URL-safe input: Base64.getUrlDecoder()
        // itself throws IllegalArgumentException("Last unit does not have enough valid
        // bits") for input like this. That raw decoder message used to leak straight to API
        // clients (a previous version of decode() rethrew IllegalArgumentException verbatim,
        // assuming it only ever came from this method's own "missing separator" check).
        assertThatThrownBy(() -> CursorUtils.decode("not-valid-base64!!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid or malformed cursor:")
                .hasMessageNotContaining("valid bits");
    }

    @Test
    void decode_validBase64ButMissingSeparator_throwsCleanMessage() {
        String noSeparator = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("no-separator-here".getBytes());

        assertThatThrownBy(() -> CursorUtils.decode(noSeparator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid or malformed cursor:");
    }
}
