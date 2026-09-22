package com.client360.common.web;

import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * An opaque keyset position: base64url of {@code {"ts":"…","id":"…"}} matching the sort key
 * (SPEC.md §4.5).
 *
 * <p>{@code ts} keeps full microsecond precision. The API renders timestamps with milliseconds,
 * but a cursor truncated to milliseconds would re-serve or skip rows that differ below that.
 */
public record Cursor(Instant ts, UUID id) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public String encode() {
        ObjectNode node = JSON.createObjectNode().put("ts", ts.toString()).put("id", id.toString());
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(node.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** {@code null} when no cursor was supplied; {@code 400 INVALID_CURSOR} when it is malformed. */
    public static Cursor decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(Base64.getUrlDecoder().decode(encoded));
            return new Cursor(
                    Instant.parse(node.get("ts").asText()),
                    UUID.fromString(node.get("id").asText()));
        } catch (Exception e) {
            throw ApiException.badRequest(
                    ErrorCodes.INVALID_CURSOR, "The cursor is malformed or expired. Restart from the first page.");
        }
    }
}
