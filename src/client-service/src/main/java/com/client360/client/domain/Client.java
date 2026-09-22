package com.client360.client.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A row of {@code client.clients} with its sensitive columns already decrypted (SPEC.md §5.2.2).
 *
 * <p>Decryption happens in the repository, so nothing above it handles ciphertext and nothing
 * below it handles plaintext. A read that cannot decrypt fails whole rather than yielding a
 * partly populated record — CP-EC-13 forbids serving a half-decrypted card.
 */
public record Client(
        UUID id,
        String externalRef,
        String firstName,
        String lastName,
        String middleName,
        LocalDate dateOfBirth,
        String email,
        String phone,
        String taxId,
        String address,
        ContactChannel preferredChannel,
        ClientSegment segment,
        ClientStatus status,
        RiskRating risk,
        KycStatus kycStatus,
        Instant kycVerifiedAt,
        Instant kycExpiresAt,
        String kycRejectionReason,
        String kycNote,
        UUID ownerManagerId,
        UUID teamId,
        Instant lastInteractionAt,
        int openTaskCount,
        UUID mergedIntoId,
        Instant mergedAt,
        int version,
        Instant createdAt,
        UUID createdBy,
        Instant updatedAt,
        UUID updatedBy,
        Instant deletedAt) {

    public String displayName() {
        return firstName + " " + lastName;
    }

    /**
     * The same client with its editable profile replaced. Identity, ownership, KYC and bookkeeping
     * columns are carried over untouched — they change through their own paths, never as a side
     * effect of a profile edit (CP-BR-01, CP-BR-03, CP-BR-04).
     */
    public Client withProfile(
            String firstName,
            String lastName,
            String middleName,
            LocalDate dateOfBirth,
            String email,
            String phone,
            String taxId,
            String address,
            ContactChannel preferredChannel,
            ClientSegment segment,
            ClientStatus status,
            RiskRating risk) {
        return new Client(
                id,
                externalRef,
                firstName,
                lastName,
                middleName,
                dateOfBirth,
                email,
                phone,
                taxId,
                address,
                preferredChannel,
                segment,
                status,
                risk,
                kycStatus,
                kycVerifiedAt,
                kycExpiresAt,
                kycRejectionReason,
                kycNote,
                ownerManagerId,
                teamId,
                lastInteractionAt,
                openTaskCount,
                mergedIntoId,
                mergedAt,
                version,
                createdAt,
                createdBy,
                updatedAt,
                updatedBy,
                deletedAt);
    }

    /** The same client with a new KYC decision; everything else carried over (CP-BR-04). */
    public Client withKyc(
            KycStatus kycStatus,
            Instant kycVerifiedAt,
            Instant kycExpiresAt,
            String kycRejectionReason,
            String kycNote) {
        return new Client(
                id,
                externalRef,
                firstName,
                lastName,
                middleName,
                dateOfBirth,
                email,
                phone,
                taxId,
                address,
                preferredChannel,
                segment,
                status,
                risk,
                kycStatus,
                kycVerifiedAt,
                kycExpiresAt,
                kycRejectionReason,
                kycNote,
                ownerManagerId,
                teamId,
                lastInteractionAt,
                openTaskCount,
                mergedIntoId,
                mergedAt,
                version,
                createdAt,
                createdBy,
                updatedAt,
                updatedBy,
                deletedAt);
    }

    public boolean isMerged() {
        return mergedIntoId != null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** CP-BR-08: a closed client accepts no field edits except reopening {@code status}. */
    public boolean isClosed() {
        return status == ClientStatus.CLOSED;
    }

    /**
     * CP-BR-07: products are read-only while KYC is rejected or expired, or the client is blocked.
     * Interactions and tasks stay writable — the manager still has to call the client about it.
     */
    public boolean productsReadOnly() {
        return status == ClientStatus.BLOCKED || kycStatus == KycStatus.REJECTED || kycStatus == KycStatus.EXPIRED;
    }
}
