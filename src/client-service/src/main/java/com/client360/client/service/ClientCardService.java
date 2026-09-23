package com.client360.client.service;

import static com.client360.common.security.Permissions.INTERACTION_READ;

import com.client360.client.api.ClientCardResponse;
import com.client360.client.api.ClientResponse;
import com.client360.client.client.InteractionFeedClient;
import com.client360.client.client.RecentInteraction;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.CurrentUsers;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Assembles {@code GET /clients/{id}/summary} — the card's first paint (SPEC.md §5.3, CP-US-02).
 *
 * <p>Its own bean rather than another method on {@link ClientService} for a reason that matters at
 * runtime: the profile has to be read <em>and its transaction committed</em> before any network
 * call is made. A remote call inside {@code @Transactional} would hold a Hikari connection for the
 * whole 800 ms sub-resource budget, and at the 200 rps §10.2 load-tests this endpoint at, twenty
 * connections would be gone long before the request threads were. Calling {@link ClientService}
 * from a separate bean also means the transaction proxy is actually applied — a private call inside
 * one bean would have bypassed it silently.
 */
@Service
public class ClientCardService {

    /** §5.3: the card shows the last ten interactions. */
    private static final int RECENT_LIMIT = 10;

    private static final Logger log = LoggerFactory.getLogger(ClientCardService.class);

    private final ClientService clients;
    private final ClientAccess access;
    private final InteractionFeedClient feed;

    public ClientCardService(ClientService clients, ClientAccess access, InteractionFeedClient feed) {
        this.clients = clients;
        this.access = access;
        this.feed = feed;
    }

    /**
     * @throws com.client360.common.api.ApiException {@code 404} when the client is absent, deleted
     *     or out of scope — the profile is the one panel whose failure fails the whole card (ER-01)
     */
    public ClientCardResponse card(UUID id, CurrentUser caller) {
        // Authorization, the READ_SENSITIVE disclosure (CP-BR-13) and the 404/410 shapes all come
        // from the ordinary card read, so the summary cannot drift from GET /clients/{id}.
        ClientResponse client = clients.read(id, caller);

        List<String> degraded = new ArrayList<>();
        List<RecentInteraction> recent = recentInteractions(id, caller, degraded);

        // Task/Reminder is v3 (§11.1). Reporting the panel as degraded rather than returning a
        // bare empty list is the whole point: "no open tasks" is a statement about the client, and
        // a manager reading it before a call would act on it. "Not loaded" is the truth.
        degraded.add(ClientCardResponse.OPEN_TASKS);

        return new ClientCardResponse(client, recent, List.of(), List.copyOf(degraded));
    }

    private List<RecentInteraction> recentInteractions(UUID clientId, CurrentUser caller, List<String> degraded) {
        // This service owns the RBAC tables, so whether the caller holds interaction:read at all is
        // a local question. Asking it here keeps a caller who holds none from being told "the feed
        // failed to load" about a panel they were never entitled to see. In the §9.2.8 seed every
        // role that holds client:read also holds interaction:read; nothing guarantees a custom role
        // will, which is why this is checked rather than assumed.
        if (access.scopeOf(caller, INTERACTION_READ).isEmpty()) {
            return List.of();
        }
        String bearer = CurrentUsers.bearerToken().orElse(null);
        if (bearer == null) {
            degraded.add(ClientCardResponse.RECENT_INTERACTIONS);
            return List.of();
        }
        try {
            return feed.recent(clientId, RECENT_LIMIT, bearer);
        } catch (RuntimeException e) {
            // Deliberately broad: a timeout, a refused connection, a 5xx and a malformed body are
            // one outcome for this screen. Never the exception's message — it can carry a URL and
            // the response body, and CLAUDE.md rule 10 keeps bodies out of the log.
            log.warn(
                    "timeline unavailable for the card of client {}: {}",
                    clientId,
                    e.getClass().getSimpleName());
            degraded.add(ClientCardResponse.RECENT_INTERACTIONS);
            return List.of();
        }
    }
}
