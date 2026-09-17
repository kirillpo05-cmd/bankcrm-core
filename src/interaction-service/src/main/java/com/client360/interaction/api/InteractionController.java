package com.client360.interaction.api;

import com.client360.common.idempotency.IdempotencyService;
import com.client360.common.security.CurrentUser;
import com.client360.common.web.KeysetPage;
import com.client360.interaction.domain.InteractionType;
import com.client360.interaction.service.InteractionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
}
