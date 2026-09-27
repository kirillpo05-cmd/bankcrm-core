package com.client360.interaction.client;

import com.client360.common.api.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;

/**
 * What client-service answers at {@code GET /internal/clients/{id}/access} (SPEC.md §5.3).
 *
 * <p>Declared again here rather than shared as a library type. A shared DTO would couple the two
 * services' release cycles and make an additive change on one side a compile break on the other;
 * the contract is the JSON, and unknown fields are ignored so the producer can add to it freely.
 *
 * <p>It carries no PII, which is the point: authorizing a write to an interaction needs a decision
 * and the client's state, not the client's name.
 *
 * @param status drives CP-BR-08 — a {@code CLOSED} client accepts no new interaction but a
 *     {@code NOTE}
 * @param teamTimezone the owning team's IANA zone, which the ticket SLA is measured in (IL-BR-07,
 *     TR-BR-14). It arrives on the authorization answer rather than through a call of its own, so
 *     a ticket write still costs one hop.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientAccessView(
        UUID clientId, UUID ownerManagerId, UUID teamId, String status, String kycStatus, String teamTimezone) {

    public boolean isClosed() {
        return "CLOSED".equals(status);
    }

    /**
     * The zone to do business-hours arithmetic in.
     *
     * @throws ApiException {@code 503} when client-service could not name one. Falling back to the
     *     server's zone would silently give a Warsaw team a London deadline, and an SLA that is
     *     quietly wrong is worse than one that failed loudly — the caller retries, nobody inherits
     *     a ticket whose clock was never right (TR-BR-14).
     */
    public ZoneId zone() {
        if (teamTimezone == null || teamTimezone.isBlank()) {
            throw ApiException.dependencyUnavailable("The owning team's timezone is unknown. Retry shortly.");
        }
        try {
            return ZoneId.of(teamTimezone);
        } catch (DateTimeException e) {
            throw ApiException.dependencyUnavailable("The owning team's timezone is not a valid zone.");
        }
    }
}
