package com.client360.interaction.api;

import com.client360.interaction.domain.InteractionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The query string of {@code GET /clients/{clientId}/interactions} (SPEC.md §6.3), apart from the
 * paging parameters.
 *
 * @param types repeatable: {@code ?type=CALL&type=MEETING}. Empty means every type
 * @param from inclusive lower bound on {@code occurredAt}
 * @param to exclusive upper bound, so consecutive windows neither overlap nor leave a gap
 * @param q a substring of the subject. Bodies are encrypted and not searchable (§4.7)
 * @param includeDeleted soft-deleted rows as well, struck through — auditors and admins only
 */
public record TimelineFilter(
        List<InteractionType> types, Instant from, Instant to, UUID authorId, String q, boolean includeDeleted) {

    public TimelineFilter {
        types = types == null ? List.of() : List.copyOf(types);
    }
}
