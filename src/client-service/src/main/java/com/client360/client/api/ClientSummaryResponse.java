package com.client360.client.api;

import com.client360.client.api.ClientResponse.UserSummary;
import com.client360.client.domain.ClientSegment;
import com.client360.client.domain.KycStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * One client as it appears in a list — the search results of {@code GET /clients} and the matches
 * of {@code GET /clients/lookup} (SPEC.md §5.3).
 *
 * <p>Contacts are masked here even in scope. A list is not a card: rendering full PII for every
 * near-match would put a disclosure in the audit log for rows the manager never looked at
 * (CP-BR-13), and a search screen does not need more than enough to tell two people apart.
 *
 * <p>{@code inScope} is always true in a scoped list, which filters in SQL. It exists for the
 * lookup, where an exact identifier match outside the caller's scope is returned with everything
 * but the display name and owner stripped — the narrow ER-01 exception that lets a manager raise a
 * break-glass request (RB-US-05).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ClientSummaryResponse(
        UUID id,
        String displayName,
        String externalRef,
        ClientSegment segment,
        KycStatus kycStatus,
        String maskedEmail,
        String maskedPhone,
        UserSummary owner,
        boolean inScope,
        Instant lastInteractionAt) {}
