package com.client360.interaction.api;

import com.client360.common.idempotency.IdempotencyService;
import com.client360.common.security.CurrentUser;
import com.client360.common.web.ETags;
import com.client360.common.web.KeysetPage;
import com.client360.interaction.domain.InteractionType;
import com.client360.interaction.service.InteractionService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    private final IdempotencyService idempotency;

    public InteractionController(InteractionService interactions, IdempotencyService idempotency) {
        this.interactions = interactions;
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
     * {@code GET /clients/{clientId}/interactions} (IL-US-01) — the timeline, newest first.
     * Keyset-paginated; a malformed cursor is {@code 400 INVALID_CURSOR} and the client restarts
     * from the first page (§4.5).
     */
    @GetMapping("/clients/{clientId}/interactions")
    public KeysetPage<TimelineEntry> timeline(
            @PathVariable UUID clientId,
            @RequestParam(required = false) InteractionType type,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            CurrentUser caller) {
        return interactions.timeline(clientId, type, cursor, limit, caller);
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
