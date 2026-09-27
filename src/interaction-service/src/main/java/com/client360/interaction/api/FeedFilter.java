package com.client360.interaction.api;

import com.client360.interaction.domain.InteractionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The query of {@code GET /interactions} — the cross-client coaching feed (SPEC.md §6.3, IL-US-07).
 *
 * @param teamId only meaningful at {@code ALL} scope. A {@code TEAM} caller may pass their own and
 *     nothing else; the scope decides, not the parameter
 */
public record FeedFilter(
        UUID teamId, UUID clientId, UUID authorId, List<InteractionType> types, Instant from, Instant to) {

    public FeedFilter {
        types = types == null ? List.of() : List.copyOf(types);
    }
}
