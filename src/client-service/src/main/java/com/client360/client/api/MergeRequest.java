package com.client360.client.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

/**
 * {@code POST /clients/merge} body (SPEC.md §5.3, CP-US-06).
 *
 * <p>A merge is not reversible through the API (CP-BR-10, Q-08), which is why the reason is long
 * and mandatory rather than a courtesy: it is the only account of why two records became one, and
 * a DBA asked to unpick it years later has nothing else to go on.
 *
 * @param fieldResolution which record each contested field comes from. Absent means the survivor
 *     keeps its own, so the safe outcome is the default and a caller has to ask for the other one
 */
public record MergeRequest(
        @NotNull UUID survivorId,
        @NotNull UUID mergedId,
        @NotNull @Size(min = 20, max = 500) String reason,
        Map<Field, Source> fieldResolution) {

    public MergeRequest {
        fieldResolution = fieldResolution == null ? Map.of() : Map.copyOf(fieldResolution);
    }

    /**
     * The contact fields a duplicate can legitimately hold a better value for.
     *
     * <p>Identity is not here. {@code externalRef} is immutable (CP-BR-01) and re-pointing it would
     * silently move history; ownership, KYC and status each have their own path and must not change
     * as a side effect of a deduplication.
     */
    public enum Field {
        EMAIL,
        PHONE,
        TAX_ID,
        ADDRESS,
        DATE_OF_BIRTH,
        MIDDLE_NAME
    }

    public enum Source {
        SURVIVOR,
        MERGED
    }

    public Source resolutionFor(Field field) {
        return fieldResolution.getOrDefault(field, Source.SURVIVOR);
    }

    public boolean takesFromMerged(Field field) {
        return resolutionFor(field) == Source.MERGED;
    }
}
