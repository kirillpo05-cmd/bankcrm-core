package com.client360.interaction.api;

import com.client360.common.idempotency.IdempotencyService;
import com.client360.common.security.CurrentUser;
import com.client360.common.web.ETags;
import com.client360.common.web.KeysetPage;
import com.client360.interaction.domain.InteractionType;
import com.client360.interaction.domain.TicketPriority;
import com.client360.interaction.domain.TicketStatus;
import com.client360.interaction.service.AttachmentService;
import com.client360.interaction.service.InteractionService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Interaction Log endpoints (SPEC.md §6.3).
 *
 * <p>Thin on purpose. Every decision about who may see what is made in {@link InteractionService},
 * and the scope part of it is made by client-service, which owns that question (§3.1).
 */
@RestController
@RequestMapping("/api/v1")
public class InteractionController {

    private final InteractionService interactions;
    private final AttachmentService attachments;
    private final IdempotencyService idempotency;

    public InteractionController(
            InteractionService interactions, AttachmentService attachments, IdempotencyService idempotency) {
        this.interactions = interactions;
        this.attachments = attachments;
        this.idempotency = idempotency;
    }

    /** {@code POST /clients/{clientId}/interactions} (IL-US-02). */
    @PostMapping("/clients/{clientId}/interactions")
    public ResponseEntity<Object> create(
            @PathVariable UUID clientId,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateInteractionRequest request,
            CurrentUser caller) {
        return idempotency.execute(idempotencyKey, request, () -> {
            InteractionResponse created = interactions.create(clientId, request, caller);
            return ResponseEntity.created(URI.create("/api/v1/interactions/" + created.id()))
                    .body(created);
        });
    }

    /**
     * {@code GET /clients/{clientId}/interactions} (IL-US-01, IL-US-03) — the timeline, newest
     * first. Keyset-paginated; a malformed cursor is {@code 400 INVALID_CURSOR} and the client
     * restarts from the first page (§4.5).
     *
     * <p>{@code type} repeats ({@code ?type=CALL&type=MEETING}); {@code from} is inclusive and
     * {@code to} exclusive; {@code q} searches subjects only, because bodies are encrypted (§4.7).
     */
    @GetMapping("/clients/{clientId}/interactions")
    public KeysetPage<TimelineEntry> timeline(
            @PathVariable UUID clientId,
            @RequestParam(required = false) List<InteractionType> type,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) UUID authorId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "false") boolean includeDeleted,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            CurrentUser caller) {
        TimelineFilter filter = new TimelineFilter(type, from, to, authorId, q, includeDeleted);
        return interactions.timeline(clientId, filter, cursor, limit, caller);
    }

    /**
     * {@code DELETE /interactions/{id}} — soft delete (§6.3). Destructive, so it takes the version
     * the caller last saw; the reason is required because an auditor will ask for it.
     */
    @DeleteMapping("/interactions/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestParam(required = false) String reason,
            CurrentUser caller) {
        interactions.delete(id, ETags.requireIfMatch(ifMatch), reason, caller);
        return ResponseEntity.noContent().build();
    }

    /** {@code GET /interactions/{id}} — the full body, which is a disclosure (IL-BR-11). */
    @GetMapping("/interactions/{id}")
    public InteractionResponse get(@PathVariable UUID id, CurrentUser caller) {
        return interactions.read(id, caller);
    }

    /**
     * {@code PATCH /interactions/{id}} (IL-US-04) — the author, inside 15 minutes, wording only.
     * {@code If-Match} is read before the body, so a write without the version the caller last
     * saw fails on the precondition rather than on its contents.
     */
    @PatchMapping("/interactions/{id}")
    public ResponseEntity<InteractionResponse> update(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) JsonNode body,
            CurrentUser caller) {
        int expectedVersion = ETags.requireIfMatch(ifMatch);
        InteractionResponse updated = interactions.update(id, expectedVersion, InteractionEdit.read(body), caller);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ETags.of(updated.version()))
                .body(updated);
    }

    /**
     * {@code POST /interactions/{id}/corrections} (IL-BR-03) — the remedy once the window has
     * closed. A create, so it takes an {@code Idempotency-Key} like every other: a correction
     * submitted twice on a flaky connection must not appear twice under the original.
     */
    /**
     * {@code POST /interactions/{id}/attachments} (IL-US-06) — {@code multipart/form-data}.
     *
     * <p>No {@code Idempotency-Key}: §4.6 asks for one on creates whose repetition would duplicate
     * a record, and a repeated upload is caught by the five-file limit rather than silently
     * creating a sixth. Two uploads of the same document are two attachments, which is what a
     * manager who pressed the button twice actually did.
     */
    @PostMapping(path = "/interactions/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AttachmentView> upload(
            @PathVariable UUID id, @RequestParam("file") MultipartFile file, CurrentUser caller) {
        AttachmentView created = attachments.upload(id, file, caller);
        return ResponseEntity.created(URI.create("/api/v1/interactions/" + id + "/attachments/" + created.id()))
                .body(created);
    }

    /**
     * {@code GET /interactions/{id}/attachments/{attachmentId}} (IL-US-06) — a 302 to a link that
     * is valid for sixty seconds.
     *
     * <p>A redirect rather than the bytes: the file goes from the object store to the browser
     * without passing through a request thread here. The disclosure is audited before the link
     * exists, so there is no link this service did not record issuing.
     */
    @GetMapping("/interactions/{id}/attachments/{attachmentId}")
    public ResponseEntity<Void> download(@PathVariable UUID id, @PathVariable UUID attachmentId, CurrentUser caller) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(attachments.downloadLink(id, attachmentId, caller))
                // A signed URL must not sit in a shared cache after it stops being the caller's.
                .cacheControl(CacheControl.noStore())
                .build();
    }

    /** {@code DELETE …} (IL-US-06) — the author inside the window, or an admin. */
    @DeleteMapping("/interactions/{id}/attachments/{attachmentId}")
    public ResponseEntity<Void> deleteAttachment(
            @PathVariable UUID id, @PathVariable UUID attachmentId, CurrentUser caller) {
        attachments.delete(id, attachmentId, caller, interactions.callerIsAdmin(caller));
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code GET /interactions} (§6.3, IL-US-07) — the supervisor's coaching feed across clients.
     *
     * <p>Declared before {@code /interactions/{id}} cannot shadow it: this path has no variable
     * segment, and Spring matches the literal first either way — but the order says the intent.
     */
    @GetMapping("/interactions")
    public KeysetPage<TimelineEntry> feed(
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) UUID authorId,
            @RequestParam(required = false) List<InteractionType> type,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            CurrentUser caller) {
        return interactions.feed(new FeedFilter(teamId, clientId, authorId, type, from, to), cursor, limit, caller);
    }

    /**
     * {@code GET /tickets} (§6.3, IL-US-05) — the ticket queue, soonest deadline first.
     *
     * <p>Keyset-paginated on the deadline, not offset-paginated: the queue is exactly the list
     * that shifts under you as tickets are worked, and an offset would skip rows while you paged
     * (§4.5).
     *
     * <p>With no {@code assigneeId} and no {@code clientId} this is the caller's own queue.
     */
    @GetMapping("/tickets")
    public KeysetPage<TicketQueueEntry> tickets(
            @RequestParam(required = false) UUID assigneeId,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) List<TicketStatus> status,
            @RequestParam(required = false) TicketPriority priority,
            @RequestParam(required = false, defaultValue = "false") boolean slaBreached,
            @RequestParam(required = false, defaultValue = "false") boolean stale,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            CurrentUser caller) {
        TicketFilter filter = new TicketFilter(assigneeId, clientId, status, priority, slaBreached, stale);
        return interactions.tickets(filter, cursor, limit, caller);
    }

    /**
     * {@code PATCH /interactions/{id}/ticket} (IL-US-05) — move a ticket through IL-BR-08.
     *
     * <p>Separate from {@code PATCH /interactions/{id}}, which edits wording inside the author's
     * 15-minute window and nothing else. A transition is not an edit: it is allowed to a different
     * set of people, long after the window has closed, and it changes no word of what was recorded.
     */
    @PatchMapping("/interactions/{id}/ticket")
    public InteractionResponse patchTicket(
            @PathVariable UUID id, @Valid @RequestBody TicketPatch patch, CurrentUser caller) {
        return interactions.patchTicket(id, patch, caller);
    }

    @PostMapping("/interactions/{id}/corrections")
    public ResponseEntity<Object> correct(
            @PathVariable UUID id,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateCorrectionRequest request,
            CurrentUser caller) {
        return idempotency.execute(idempotencyKey, request, () -> {
            InteractionResponse correction = interactions.correct(id, request, caller);
            return ResponseEntity.created(URI.create("/api/v1/interactions/" + correction.id()))
                    .body(correction);
        });
    }
}
