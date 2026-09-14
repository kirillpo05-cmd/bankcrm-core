package com.client360.client.support;

import java.util.Locale;

/**
 * Email normalization before hashing (SPEC.md §4.7). Must be applied identically on the write and
 * the lookup path, or the same person hashes to two different values.
 */
public final class Emails {

    private Emails() {}

    /**
     * Trimmed and lowercased. A {@code +tag} is deliberately <em>not</em> stripped (CP-EC-05): at
     * some providers it is a semantically different address, and stripping it would merge people
     * who are not the same person.
     */
    public static String normalize(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim().toLowerCase(Locale.ROOT);
    }
}
