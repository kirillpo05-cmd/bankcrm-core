package com.client360.client.api;

import com.client360.client.service.ClientService;
import com.client360.common.idempotency.IdempotencyService;
import com.client360.common.security.CurrentUser;
import com.client360.common.web.ETags;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
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
    private final IdempotencyService idempotency;

    public ClientController(ClientService clients, IdempotencyService idempotency) {
        this.clients = clients;
        this.idempotency = idempotency;
    }

    /**
     * {@code POST /clients} (CP-US-03).
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
}
