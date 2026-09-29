package com.client360.client.persistence;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code client.teams} and {@code client.team_members}. Team administration is still §9.3's
 * and arrives with the admin endpoints; what is here is what an authorization decision needs.
 */
@Repository
public class TeamRepository {

    private final JdbcClient jdbc;

    public TeamRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The team's IANA zone, for the business-hours SLA arithmetic interaction-service does
     * (IL-BR-07, TR-BR-14). It travels on the authorization answer rather than through an endpoint
     * of its own, so a ticket write still costs one call.
     */
    public Optional<String> timezoneOf(UUID teamId) {
        if (teamId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT timezone FROM client.teams WHERE id = :id")
                .param("id", teamId)
                .query(String.class)
                .optional();
    }

    /**
     * Every team whose clients fall inside this user's {@code TEAM} scope (RB-BR-02): the teams they
     * supervise, the teams they are an active member of, and their primary team.
     *
     * <p>All three, because a supervisor covering two branches is a member of one and the supervisor
     * of both, and V8's {@code team_members} was added precisely so that case stops being
     * inexpressible. Before it, this resolved to {@code users.primary_team_id} alone — the only
     * membership the MVP schema recorded — which quietly denied a supervisor half their own book.
     *
     * <p>A left membership grants nothing: {@code left_at IS NULL} is what makes a transfer take
     * effect, rather than leaving a manager reading their old team indefinitely.
     */
    public Set<UUID> scopeTeamsOf(UUID userId) {
        List<UUID> ids = jdbc.sql("""
                        SELECT t.id FROM teams t WHERE t.supervisor_id = :userId
                        UNION
                        SELECT tm.team_id FROM team_members tm
                         WHERE tm.user_id = :userId AND tm.left_at IS NULL
                        UNION
                        SELECT u.primary_team_id FROM users u
                         WHERE u.id = :userId AND u.primary_team_id IS NOT NULL
                        """).param("userId", userId).query(UUID.class).list();
        return Set.copyOf(ids);
    }

    /**
     * The same teams as {@link #scopeTeamsOf}, with the primary one flagged — {@code GET /me}'s
     * {@code teams} (§9.3).
     *
     * <p>{@code /me} reports the teams in the caller's <em>scope</em>, not only the rows they hold a
     * membership in. A supervisor sees the clients of a team they supervise whether or not anyone
     * added them as a member, and a screen that listed memberships would contradict what the API
     * lets them read. One concept, answered the same way here and in the token claim.
     */
    public List<Membership> scopeMembershipsOf(UUID userId) {
        return jdbc.sql("""
                        SELECT team_id, bool_or(is_primary) AS is_primary
                          FROM (
                                SELECT u.primary_team_id AS team_id, true AS is_primary
                                  FROM users u
                                 WHERE u.id = :userId AND u.primary_team_id IS NOT NULL
                                UNION ALL
                                SELECT tm.team_id, tm.is_primary
                                  FROM team_members tm
                                 WHERE tm.user_id = :userId AND tm.left_at IS NULL
                                UNION ALL
                                SELECT t.id, false
                                  FROM teams t
                                 WHERE t.supervisor_id = :userId
                               ) m
                         GROUP BY team_id
                         ORDER BY is_primary DESC, team_id
                        """)
                .param("userId", userId)
                .query((rs, n) -> new Membership(rs.getObject("team_id", UUID.class), rs.getBoolean("is_primary")))
                .list();
    }

    /** Name and zone for one team, for the {@code primaryTeam} object in a login response. */
    public Optional<TeamRef> findById(UUID teamId) {
        if (teamId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT id, name, timezone FROM teams WHERE id = :id")
                .param("id", teamId)
                .query((rs, n) ->
                        new TeamRef(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("timezone")))
                .optional();
    }

    public record Membership(UUID teamId, boolean isPrimary) {}

    public record TeamRef(UUID id, String name, String timezone) {}
}
