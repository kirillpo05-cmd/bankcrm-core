package com.client360.client.api;

import com.client360.client.domain.ClientSegment;
import com.client360.client.domain.ClientStatus;
import com.client360.client.domain.KycStatus;
import com.client360.client.service.ClientCardService;
import com.client360.client.service.ClientService;
import com.client360.common.idempotency.IdempotencyService;
import com.client360.common.security.CurrentUser;
import com.client360.common.web.ETags;
import com.client360.common.web.OffsetPage;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
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

/**
 * Client Profile endpoints (SPEC.md §5.3).
 *
 * <p>Thin on purpose: it binds and shapes HTTP, and every decision about who may see what lives
 * in {@link ClientService} and {@code ClientAccess}. There is no {@code @PreAuthorize} and no role
 * check here — authorization is permission + scope, asked per request (CLAUDE.md rule 5).
 */
@RestController
@RequestMapping("/api/v1/clients")
public class ClientController {

    private final ClientService clients;
    private final ClientCardService cards;
    private final IdempotencyService idempotency;
    private final ClientPatchReader patches;

    public ClientController(
            ClientService clients, ClientCardService cards, IdempotencyService idempotency, ClientPatchReader patches) {
        this.clients = clients;
        this.cards = cards;
        this.idempotency = idempotency;
        this.patches = patches;
    }

    /**
     * {@code POST /clients} (§5.3).
     *
     * <p>The header is bound as optional so a missing key becomes {@code 400 VALIDATION_FAILED}
     * naming {@code Idempotency-Key} (§4.6), rather than the generic missing-header error.
     */
    @PostMapping
    public ResponseEntity<Object> create(
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateClientRequest request,
            CurrentUser caller) {
        return idempotency.execute(idempotencyKey, request, () -> {
            ClientResponse created = clients.create(request, caller);
            return ResponseEntity.created(URI.create("/api/v1/clients/" + created.id()))
                    .header(HttpHeaders.ETAG, ETags.of(created.version()))
                    .body(created);
        });
    }

    /**
     * {@code GET /clients} (§5.3) — the scoped admin list. Offset-paginated because it is bounded
     * and sortable; the interaction timeline and the audit log keyset instead (§4.5).
     */
    @GetMapping
    public OffsetPage<ClientSummaryResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ClientSegment segment,
            @RequestParam(required = false) ClientStatus status,
            @RequestParam(required = false) KycStatus kycStatus,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) Integer kycExpiringWithinDays,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            CurrentUser caller) {
        ClientListQuery query = new ClientListQuery(
                q, segment, status, kycStatus, ownerId, teamId, kycExpiringWithinDays, page, size, sort);
        return clients.list(query, caller);
    }

    /**
     * {@code GET /clients/lookup} (CP-US-01). Declared before no path variable can shadow it:
     * {@code lookup} is a literal segment and Spring matches it ahead of {@code /{id}}.
     */
    @GetMapping("/lookup")
    public LookupResponse lookup(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String externalRef,
            @RequestParam(required = false) String q,
            CurrentUser caller) {
        return clients.lookup(email, phone, externalRef, q, caller);
    }

    /** {@code GET /clients/{id}} (CP-US-02). {@code ETag} is what a later {@code If-Match} echoes. */
    @GetMapping("/{id}")
    public ResponseEntity<ClientResponse> get(@PathVariable UUID id, CurrentUser caller) {
        ClientResponse client = clients.read(id, caller);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ETags.of(client.version()))
                .body(client);
    }

    /**
     * {@code GET /clients/{id}/summary} (CP-US-02, S-CP-02) — the card's first paint.
     *
     * <p>No {@code ETag}: the body aggregates resources owned by two services and changes whenever
     * either does, so a version taken from the client row alone would go stale without changing,
     * and {@code If-Match} on it would guard nothing.
     */
    @GetMapping("/{id}/summary")
    public ClientCardResponse summary(@PathVariable UUID id, CurrentUser caller) {
        return cards.card(id, caller);
    }

    /**
     * {@code PATCH /clients/{id}} (CP-US-03).
     *
     * <p>Bound as a raw {@link JsonNode} rather than a DTO because merge-patch needs three states
     * per field — absent, explicitly null, and set — and a record of nullable fields can only
     * express two. {@link ClientPatchReader} turns it into a validated patch.
     *
     * <p>{@code If-Match} is parsed before the body is read, so a write sent without the header a
     * caller's last read gave them fails on the precondition rather than on its contents.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<ClientResponse> update(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) JsonNode body,
            CurrentUser caller) {
        int expectedVersion = ETags.requireIfMatch(ifMatch);
        ClientResponse updated = clients.update(id, expectedVersion, patches.read(body), caller);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ETags.of(updated.version()))
                .body(updated);
    }

    /**
     * {@code POST /clients/{id}/reassign} (CP-US-05).
     *
     * <p>No {@code If-Match}: a reassignment is not an edit of the fields the caller was looking
     * at, and a supervisor covering for an absent colleague should not be blocked because someone
     * corrected a phone number a moment earlier.
     */
    @PostMapping("/{id}/reassign")
    public ResponseEntity<ReassignResponse> reassign(
            @PathVariable UUID id, @Valid @RequestBody ReassignRequest request, CurrentUser caller) {
        ReassignResponse reassigned = clients.reassign(id, request, caller);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ETags.of(reassigned.client().version()))
                .body(reassigned);
    }

    /**
     * {@code DELETE /clients/{id}} — soft delete (§4.9). {@code If-Match} is required: this is
     * destructive, so the caller must prove they are acting on the record they last saw.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestParam(required = false) String reason,
            CurrentUser caller) {
        clients.softDelete(id, ETags.requireIfMatch(ifMatch), reason, caller);
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code POST /clients/{id}/kyc} (CP-BR-04, CP-BR-05).
     *
     * <p>No {@code Idempotency-Key}: this is a state-machine move, not a create, and re-sending it
     * is already safe — the second attempt finds the client in the target state and gets
     * {@code 409 ILLEGAL_STATE_TRANSITION} rather than a second decision.
     */
    @PostMapping("/{id}/kyc")
    public ResponseEntity<ClientResponse> transitionKyc(
            @PathVariable UUID id, @Valid @RequestBody KycTransitionRequest request, CurrentUser caller) {
        ClientResponse updated = clients.transitionKyc(id, request, caller);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ETags.of(updated.version()))
                .body(updated);
    }
}
