package com.client360.interaction.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A file attached to an interaction ({@code interaction.interaction_attachments}, SPEC.md §6.2.3).
 *
 * @param storageKey the object-store key, never a URL. A download is a pre-signed link issued per
 *     request, so every access goes through this service and can be audited (IL-BR-11)
 * @param checksumSha256 of the bytes as stored. Recorded at upload so a file that comes back
 *     different can be told from a file that was always that way
 */
public record Attachment(
        UUID id,
        UUID interactionId,
        String filename,
        String contentType,
        long sizeBytes,
        String storageKey,
        byte[] checksumSha256,
        ScanStatus scan,
        Instant scannedAt,
        UUID uploadedBy,
        Instant uploadedAt,
        Instant deletedAt) {

    /** §6.2.3 {@code ck_att_size}: ten megabytes, stated here so the API can refuse before storing. */
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    /** §6.3: five files per interaction. Not a CHECK — a per-parent count cannot be one. */
    public static final int MAX_PER_INTERACTION = 5;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
