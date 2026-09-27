package com.client360.interaction.api;

import com.client360.interaction.domain.Interaction;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /tickets} (SPEC.md §6.3, IL-US-05).
 *
 * <p>Not a {@link TimelineEntry}: the timeline is one client's feed and its rows have no reason to
 * name the client, while a queue crosses clients and a row that does not say whose it is cannot be
 * acted on. It carries no {@code bodyPreview} either — a queue is a worklist, and previewing fifty
 * bodies to decide what to pick up next would be reading fifty interactions to open one.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TicketQueueEntry(
        UUID id, UUID clientId, String subject, Instant occurredAt, UserSummary author, TicketView ticket) {

    public static TicketQueueEntry of(Interaction interaction, UserSummary author, TicketView ticket) {
        return new TicketQueueEntry(
                interaction.id(),
                interaction.clientId(),
                interaction.subject(),
                interaction.occurredAt(),
                author,
                ticket);
    }
}
