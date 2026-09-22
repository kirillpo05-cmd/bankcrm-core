package com.client360.common.web;

import com.client360.common.api.ApiException;

/**
 * Optimistic concurrency over HTTP (SPEC.md §4.8): {@code GET} returns {@code ETag: "7"}, and
 * every {@code PATCH}/{@code DELETE} of a versioned resource must send {@code If-Match: "7"}.
 */
public final class ETags {

    private ETags() {}

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /**
     * The version the caller last saw. Missing header → {@code 412 PRECONDITION_REQUIRED}; an
     * unparseable one is a validation error rather than a silent "no precondition".
     */
    public static int requireIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.preconditionRequired(
                    "If-Match is required on this write. Send the ETag from your last read.");
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            int version = Integer.parseInt(value);
            if (version < 0) {
                throw new NumberFormatException();
            }
            return version;
        } catch (NumberFormatException e) {
            throw ApiException.validation("If-Match", "must be the quoted version from an ETag, e.g. \"7\"");
        }
    }
}
