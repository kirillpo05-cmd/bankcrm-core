package com.client360.client.api;

import com.client360.client.domain.ClientSegment;
import com.client360.client.domain.ClientStatus;
import com.client360.client.domain.ContactChannel;
import com.client360.client.domain.KycStatus;
import com.client360.client.domain.RiskRating;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The client representation returned by create, read and update (SPEC.md §5.3).
 *
 * <p>{@code email} and {@code phone} are decrypted here; {@code taxId} is masked even on the full
 * card, exactly as §5.3 shows it. Returning this body is what CP-BR-13 counts as a genuine PII
 * disclosure and audits as {@code READ_SENSITIVE} — a masked search result is not.
 *
 * <p>{@code permissions} is computed server-side from the caller's effective grants. The UI must
 * render affordances from it and never from a client-side role check, so the button set cannot
 * drift from what the API will actually allow.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ClientResponse(
        UUID id,
        String externalRef,
        String firstName,
        String lastName,
        String middleName,
        String displayName,
        LocalDate dateOfBirth,
        String email,
        String phone,
        String taxId,
        String address,
        ContactChannel preferredChannel,
        ClientSegment segment,
        ClientStatus status,
        RiskRating risk,
        Kyc kyc,
        UserSummary owner,
        UUID teamId,
        Stats stats,
        Permissions permissions,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * @param daysUntilExpiry drives the header's {@code kyc-expiring} amber state (S-CP-02);
     *     negative once expiry has passed but the nightly sweep (CP-BR-06) has not yet run
     */
    public record Kyc(
            KycStatus status, Instant verifiedAt, Instant expiresAt, Long daysUntilExpiry, String rejectionReason) {}

    public record UserSummary(UUID id, String fullName) {}

    /**
     * Display-only counters denormalized from {@code interaction.events} / {@code task.events}
     * (CP-BR-11). They may lag by seconds and <strong>no business decision may read them</strong>
     * — anything authoritative queries interaction-service.
     */
    public record Stats(Instant lastInteractionAt, int openTaskCount) {}

    public record Permissions(boolean canEdit, boolean canDelete, boolean canReassign, boolean canMerge) {}
}
