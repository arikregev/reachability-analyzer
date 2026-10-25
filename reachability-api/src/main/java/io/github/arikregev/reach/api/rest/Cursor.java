package io.github.arikregev.reach.api.rest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Opaque keyset cursor for the scan list: position (created_at, id) plus a fingerprint of the filters, so a cursor
 * passed with other filters is rejected (5.4a a, INVALID_PARAMETER).
 */
record Cursor(Instant createdAt, UUID id, int filterHash) {

    String encode() {
        String raw = createdAt + "|" + id + "|" + Integer.toHexString(filterHash);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Optional<Cursor> decode(String value) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|");
            if (parts.length != 3) {
                return Optional.empty();
            }
            return Optional.of(new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]),
                    Integer.parseUnsignedInt(parts[2], 16)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
