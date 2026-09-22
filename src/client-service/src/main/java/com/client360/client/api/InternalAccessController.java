package com.client360.client.api;

import com.client360.client.service.ClientService;
import com.client360.common.security.CurrentUser;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authorization question other services ask (SPEC.md §3.1: client-service is the authorization
 * authority, and interaction-service asks it, never the reverse).
 *
 * <p>Separate from {@code GET /clients/{id}} on purpose. Authorizing a write to an interaction
 * does not need the client's name, email or phone, and reusing the card endpoint would ship all
 * three between services and write a {@code READ_SENSITIVE} row for a disclosure nobody read
 * (CP-BR-13). This returns a decision and the two fields the caller needs to apply CP-BR-07 and
 * CP-BR-08, and audits nothing.
 *
 * <p>It carries the caller's own bearer token, not a service credential: the decision is about the
 * human making the request, so the identity must be theirs all the way down. That also keeps ER-01
 * honest end to end — a client outside their scope is {@code 404} here, and the calling service
 * passes that through unchanged.
 */
@RestController
@RequestMapping("/api/v1/internal/clients")
public class InternalAccessController {

    private final ClientService clients;

    public InternalAccessController(ClientService clients) {
        this.clients = clients;
    }

    /**
     * @param permission a code from the §9.2.8 matrix, e.g. {@code interaction:write}
     * @return the client's authorization-relevant state, or {@code 404} when the caller may not
     *     act on it — indistinguishable from "no such client", as ER-01 requires
     */
    @GetMapping("/{clientId}/access")
    public ClientAccessView access(@PathVariable UUID clientId, @RequestParam String permission, CurrentUser caller) {
        return clients.checkAccess(clientId, permission, caller);
    }
}
