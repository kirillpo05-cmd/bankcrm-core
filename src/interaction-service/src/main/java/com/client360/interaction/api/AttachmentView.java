package com.client360.interaction.api;

import com.client360.interaction.domain.Attachment;
import com.client360.interaction.domain.ScanStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * An attachment as the API shows it (SPEC.md §6.3, IL-US-06).
 *
 * <p>Carries no URL. A download is a separate request that issues a link valid for sixty seconds
 * and audits the disclosure — putting a link in the listing would hand out access to every file on
 * the interaction to anyone who merely opened it (IL-BR-11).
 *
 * @param checksumSha256 hex, so a manager who downloads a file can tell whether it is the one that
 *     was attached
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AttachmentView(
        UUID id,
        String filename,
        String contentType,
        long sizeBytes,
        ScanStatus scan,
        String checksumSha256,
        UUID uploadedBy,
        Instant uploadedAt) {

    public static AttachmentView of(Attachment attachment) {
        return new AttachmentView(
                attachment.id(),
                attachment.filename(),
                attachment.contentType(),
                attachment.sizeBytes(),
                attachment.scan(),
                HexFormat.of().formatHex(attachment.checksumSha256()),
                attachment.uploadedBy(),
                attachment.uploadedAt());
    }
}
