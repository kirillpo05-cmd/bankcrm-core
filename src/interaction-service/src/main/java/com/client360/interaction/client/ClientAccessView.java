package com.client360.interaction.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientAccessView(UUID clientId, UUID ownerManagerId, UUID teamId, String status, String kycStatus) {

    public boolean isClosed() {
        return "CLOSED".equals(status);
    }
}
