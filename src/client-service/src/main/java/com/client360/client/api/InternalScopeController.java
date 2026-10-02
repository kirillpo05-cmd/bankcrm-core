package com.client360.client.api;

import com.client360.client.persistence.TeamRepository;
import com.client360.common.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own {@code TEAM} scope, for a service that has to answer a question about many
 * clients at once (SPEC.md §3.1, §5.3 internal endpoints).
 *
 * <p>{@code GET /internal/clients/{id}/access} settles one client; it cannot settle the
 * supervisor's cross-client feed or the team ticket queue, which have no client id to ask about.
 * Those need the set of teams the caller may see, and RB-BR-02 defines it as the teams they
 * supervise plus the teams they are an active member of — three tables in a schema no other service
 * may read.
 *
 * <p>Answered here rather than from the token's {@code teamIds} claim on purpose, and the reason is
 * the same one the feed already gave for asking: membership changes, and a claim minted an hour ago
 * would scope a supervisor to a team they have left. The claim is the fallback that keeps the other
 * services working when this one is slow; it is not the authority.
 *
 * <p>Authentication only, no permission. It discloses nothing but which teams the caller is already
 * in, and it writes no audit event — knowing your own team is not a disclosure (the same reasoning as
 * CP-BR-13 for the access check).
 */
@RestController
@RequestMapping("/api/v1/internal/me")
public class InternalScopeController {

    private final TeamRepository teams;

    public InternalScopeController(TeamRepository teams) {
        this.teams = teams;
    }

    /**
     * @return every team in the caller's {@code TEAM} scope, primary first; empty for a manager with
     *     no team, which a caller must read as "nothing", never as "everything"
     */
    @GetMapping("/teams")
    public ScopeView teams(CurrentUser caller) {
        List<TeamRepository.Membership> memberships = teams.scopeMembershipsOf(caller.id());
        UUID primary = memberships.stream()
                .filter(TeamRepository.Membership::isPrimary)
                .map(TeamRepository.Membership::teamId)
                .findFirst()
                .orElse(null);
        return new ScopeView(
                memberships.stream().map(TeamRepository.Membership::teamId).toList(), primary);
    }

    /**
     * @param primaryTeamId the one CP-BR-03 derives a new client's team from; {@code null} when the
     *     caller has none, which is what makes CP-BR-03 unsatisfiable for a cross-team role
     */
    public record ScopeView(List<UUID> teamIds, UUID primaryTeamId) {}
}
