package com.client360.client.api;

import com.client360.client.api.ClientResponse.UserSummary;
import com.client360.client.domain.ClientSegment;
import com.client360.client.domain.KycStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /clients/lookup} (SPEC.md §5.3, CP-US-01) — the single box that has to turn an email,
 * a phone number or a CIF id into an open card in one step.
 *
 * <p>Contacts are masked here even for in-scope hits: a result list is not a card, and rendering
 * full PII for every near-match would flood the audit log with disclosures nobody asked for
 * (CP-BR-13).
 *
 * <p>An out-of-scope hit is still returned, with {@code inScope: false} and nothing beyond the
 * display name and owner. That is a deliberate, narrow exception to ER-01 — it applies only to
 * this endpoint and only on an exact identifier match — and it exists so a manager can see "this
 * client exists and belongs to Adam Nowak" and raise a break-glass request (RB-US-05).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LookupResponse(MatchType matchType, boolean exact, List<Match> results) {

    /** How the server read the query. {@code q} is auto-detected; an explicit parameter is not. */
    public enum MatchType {
        EMAIL,
        PHONE,
        EXTERNAL_REF,
        NAME
    }

    public record Match(
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

    /** Empty is {@code 200} with no results, never {@code 404} (§5.3). */
    public static LookupResponse empty(MatchType matchType) {
        return new LookupResponse(matchType, false, List.of());
    }
}
