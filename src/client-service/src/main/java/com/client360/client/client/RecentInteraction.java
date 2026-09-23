package com.client360.client.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the timeline as the client card shows it (SPEC.md §5.3).
 *
 * <p>A projection of interaction-service's much wider {@code TimelineEntry}, kept to the seven
 * fields §5.3 renders. Unknown properties are ignored on purpose: interaction-service owns that
 * contract and may add to it, and a new field there must not break the card here.
 *
 * <p>{@code type}, {@code direction} and {@code outcome} stay {@link String} rather than becoming
 * enums. They are pass-through display values from another service's vocabulary, and copying that
 * enum into this module would mean a deployment of interaction-service could start returning a
 * value this one refuses to deserialize.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecentInteraction(
        UUID id, String type, String direction, String subject, Instant occurredAt, Author author, String outcome) {

    public record Author(UUID id, String fullName) {}
}
