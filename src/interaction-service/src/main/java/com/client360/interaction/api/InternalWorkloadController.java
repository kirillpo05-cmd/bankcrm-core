package com.client360.interaction.api;

import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Permissions;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a staff member still has open here, so client-service can apply RB-BR-07.
 *
 * <p>Deactivating a user is refused while they are the assignee of open tickets, and those live in
 * this service. This is the one question that travels in the unusual direction — client-service
 * asking interaction-service — and it is not an authorization question, which is what keeps §3.1
 * intact: client-service remains the single authorization authority, and this answers a count.
 * The card summary already fans out this way (§5.3), so the dependency is not a new one.
 *
 * <p>Open tasks will join this answer when Task/Reminder ships (§7, v3). They are absent rather
 * than reported as zero: a blocker that silently reads "none" because its module does not exist
 * would let an admin deactivate someone holding forty open follow-ups.
 */
@RestController
@RequestMapping("/api/v1/internal/users")
public class InternalWorkloadController {

    private final JdbcClient jdbc;
    private final AccessPolicy accessPolicy;

    public InternalWorkloadController(JdbcClient jdbc, AccessPolicy accessPolicy) {
        this.jdbc = jdbc;
        this.accessPolicy = accessPolicy;
    }

    /**
     * @return the number of tickets assigned to this user that are not finished. {@code WAITING_CLIENT}
     *     counts as open — the SLA clock is paused, not stopped, and the ticket still needs an owner
     *     (IL-EC-10).
     */
    @GetMapping("/{userId}/open-work")
    public OpenWork openWork(@PathVariable UUID userId, CurrentUser caller) {
        // ticket:read at any scope. The caller is whoever is administering the user — an admin holds
        // it at ALL — and the answer is a count, not a record: it names no client and no ticket.
        if (accessPolicy.scopeOf(caller, Permissions.TICKET_READ).isEmpty()) {
            throw ApiException.forbidden(
                            ErrorCodes.PERMISSION_DENIED, "This action requires the ticket:read permission.")
                    .detail("permission", Permissions.TICKET_READ);
        }
        int openTickets =
                jdbc.sql("""
                        SELECT count(*) FROM interactions
                         WHERE ticket_assignee_id = :userId
                           AND deleted_at IS NULL
                           AND ticket_status IN ('NEW', 'IN_PROGRESS', 'WAITING_CLIENT')
                        """).param("userId", userId).query(Integer.class).single();
        return new OpenWork(openTickets);
    }

    public record OpenWork(int openTickets) {}
}
