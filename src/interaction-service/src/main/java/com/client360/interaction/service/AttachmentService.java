package com.client360.interaction.service;

import static com.client360.common.security.Permissions.INTERACTION_READ;
import static com.client360.common.security.Permissions.INTERACTION_WRITE;

import com.client360.common.api.ApiException;
import com.client360.common.id.UuidV7;
import com.client360.common.ratelimit.RateLimiter;
import com.client360.common.security.CurrentUser;
import com.client360.interaction.api.AttachmentView;
import com.client360.interaction.api.InteractionErrorCodes;
import com.client360.interaction.client.ClientAccessClient;
import com.client360.interaction.domain.Attachment;
import com.client360.interaction.domain.Interaction;
import com.client360.interaction.persistence.AttachmentRepository;
import com.client360.interaction.persistence.InteractionRepository;
import com.client360.interaction.storage.AttachmentStore;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Interaction attachments (SPEC.md §6.3, IL-US-06).
 *
 * <p>Its own bean rather than more methods on {@link InteractionService}, because the interesting
 * part here is not the interaction: it is a file, a bucket and a limit that only a transaction can
 * hold.
 */
@Service
public class AttachmentService {

    /** {@code ck_att_type}. Stated here so the API refuses before the database has to. */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/tiff",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    /** §4.10: twenty attachments an hour, per user. */
    private static final int UPLOADS_PER_HOUR = 20;

    private static final String UPLOAD_BUCKET = "interactions.attachment_upload";

    private final InteractionRepository interactions;
    private final AttachmentRepository attachments;
    private final AttachmentStore store;
    private final ClientAccessClient clientAccess;
    private final InteractionEvents events;
    private final RateLimiter rateLimiter;

    public AttachmentService(
            InteractionRepository interactions,
            AttachmentRepository attachments,
            AttachmentStore store,
            ClientAccessClient clientAccess,
            InteractionEvents events,
            RateLimiter rateLimiter) {
        this.interactions = interactions;
        this.attachments = attachments;
        this.store = store;
        this.clientAccess = clientAccess;
        this.events = events;
        this.rateLimiter = rateLimiter;
    }

    /**
     * {@code POST /interactions/{id}/attachments} (IL-US-06).
     *
     * <p>The object is written before the row, and the row is what makes it real. If the insert
     * fails the object is removed again; if the removal also fails an unreferenced object is left
     * in the bucket, which is a cleanup job's problem and never a caller's. The other order — row
     * first — would leave a row pointing at nothing, which is a broken download for a file the UI
     * says exists.
     */
    @Transactional
    public AttachmentView upload(UUID interactionId, MultipartFile file, CurrentUser caller) {
        Interaction interaction = interactions.findById(interactionId).orElseThrow(InteractionService::notFound);
        clientAccess.require(interaction.clientId(), INTERACTION_WRITE);

        // §4.10: 20 an hour per user. Counted before the bytes are read, so a caller cannot make
        // this service buffer twenty megabytes at a time to find out they are over the limit.
        rateLimiter.check(caller.id().toString(), UPLOAD_BUCKET, UPLOADS_PER_HOUR, Duration.ofHours(1));

        byte[] content = read(file);
        validate(file, content);

        // The count and the insert are one transaction with the parent row held, or two uploads
        // racing would each see four files and each write a fifth (§6.2.3).
        if (attachments.countForUpdate(interactionId) >= Attachment.MAX_PER_INTERACTION) {
            throw ApiException.businessRule(
                            "An interaction may carry at most " + Attachment.MAX_PER_INTERACTION + " attachments.")
                    .detail(
                            "code",
                            InteractionErrorCodes.ATTACHMENT_LIMIT_REACHED,
                            "limit",
                            Attachment.MAX_PER_INTERACTION);
        }

        UUID id = UuidV7.next();
        // Derived from identifiers, never from the filename: a caller-supplied name would otherwise
        // choose a path inside the bucket.
        String key = "interactions/" + interactionId + "/" + id;
        store.put(key, content, file.getContentType());
        try {
            Attachment saved = attachments.insert(new Attachment(
                    id,
                    interactionId,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    content.length,
                    key,
                    sha256(content),
                    null,
                    null,
                    caller.id(),
                    null,
                    null));
            events.attachmentAdded(interaction, saved);
            return AttachmentView.of(saved);
        } catch (RuntimeException e) {
            store.deleteQuietly(key);
            throw e;
        }
    }

    /** What the interaction detail shows. Listing metadata is not a disclosure (IL-BR-11). */
    @Transactional(readOnly = true)
    public List<AttachmentView> list(UUID interactionId) {
        return attachments.listFor(interactionId).stream()
                .map(AttachmentView::of)
                .toList();
    }

    /**
     * {@code GET /interactions/{id}/attachments/{attachmentId}} — a short-lived link.
     *
     * <p>This one <em>is</em> a disclosure and audits {@code READ_SENSITIVE} (IL-BR-11): a document
     * attached to a client interaction is exactly the kind of thing an investigation asks who
     * opened. The event is written before the link is issued, so a link never exists unaudited.
     */
    @Transactional
    public URI downloadLink(UUID interactionId, UUID attachmentId, CurrentUser caller) {
        Interaction interaction = interactions.findById(interactionId).orElseThrow(InteractionService::notFound);
        clientAccess.require(interaction.clientId(), INTERACTION_READ);
        if (!interaction.canReadBodyAs(caller.id())) {
            // A private note's attachment is as private as its body (IL-BR-09).
            throw InteractionService.notFound();
        }
        Attachment attachment =
                attachments.findLive(interactionId, attachmentId).orElseThrow(InteractionService::notFound);

        switch (attachment.scan()) {
            case PENDING ->
                throw ApiException.conflict(
                        InteractionErrorCodes.ATTACHMENT_SCAN_PENDING,
                        "This attachment has not been scanned yet. Try again shortly.");
            case INFECTED ->
                throw ApiException.forbidden(
                        InteractionErrorCodes.ATTACHMENT_INFECTED, "This attachment was found to be malicious.");
            case FAILED ->
                throw ApiException.conflict(
                        InteractionErrorCodes.ATTACHMENT_SCAN_PENDING,
                        "This attachment could not be scanned and is not available.");
            case CLEAN -> {
                /* falls through to the link below */
            }
        }
        events.readSensitive(interaction, "interaction.attachment");
        return store.presignedGet(attachment.storageKey(), attachment.filename());
    }

    /**
     * {@code DELETE …} — the author inside their edit window, or an admin (§6.3). The same window
     * as an edit, and for the same reason: attaching the wrong file is a slip to undo, not a
     * record to rewrite.
     */
    @Transactional
    public void delete(UUID interactionId, UUID attachmentId, CurrentUser caller, boolean admin) {
        Interaction interaction = interactions.findById(interactionId).orElseThrow(InteractionService::notFound);
        clientAccess.require(interaction.clientId(), INTERACTION_WRITE);
        Attachment attachment =
                attachments.findLive(interactionId, attachmentId).orElseThrow(InteractionService::notFound);

        if (!admin && !attachment.uploadedBy().equals(caller.id())) {
            throw InteractionService.notFound();
        }
        if (!admin && !interaction.isEditableBy(caller.id(), java.time.Instant.now())) {
            throw ApiException.conflict(
                    InteractionErrorCodes.INTERACTION_EDIT_WINDOW_CLOSED,
                    "The 15-minute window has closed; an attachment can no longer be removed.");
        }
        attachments.softDelete(interactionId, attachmentId);
        events.attachmentDeleted(interaction, attachment);
    }

    private void validate(MultipartFile file, byte[] content) {
        if (content.length == 0) {
            throw ApiException.validation("file", "must not be empty");
        }
        if (content.length > Attachment.MAX_BYTES) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    InteractionErrorCodes.ATTACHMENT_TOO_LARGE,
                    "An attachment may be at most 10 MB.");
        }
        if (file.getContentType() == null || !ALLOWED_TYPES.contains(file.getContentType())) {
            throw new ApiException(
                            org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                            InteractionErrorCodes.ATTACHMENT_TYPE_UNSUPPORTED,
                            "That file type cannot be attached.")
                    .detail("contentType", String.valueOf(file.getContentType()));
        }
        if (file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            throw ApiException.validation("file", "must have a filename");
        }
        if (file.getOriginalFilename().length() > 255) {
            throw ApiException.validation("file", "filename must be at most 255 characters");
        }
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw ApiException.validation("file", "could not be read");
        }
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
