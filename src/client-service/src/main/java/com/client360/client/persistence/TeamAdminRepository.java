package com.client360.client.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Team administration (SPEC.md §9.3). Kept apart from {@link TeamRepository}, which the
 * authorization path reads on every scoped request — these are the writes, behind
 * {@code team:manage}.
 */
@Repository
public class TeamAdminRepository {

    private static final String COLUMNS =
            "id, name, code, supervisor_id, parent_team_id, timezone, active, created_at, version";

    private final JdbcClient jdbc;

    public TeamAdminRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code GET /teams} (§9.3) — "includes member counts". */
    public List<TeamView> list() {
        return jdbc.sql("""
                        SELECT t.id, t.name, t.code, t.supervisor_id, t.parent_team_id, t.timezone,
                               t.active, t.version,
                               -- Members by either route RB-BR-02 recognizes: a primary team on the
                               -- user, or an active team_members row. Counted distinctly, because a
                               -- user may hold both and is still one person.
                               (SELECT count(DISTINCT m.user_id) FROM (
                                    SELECT u.id AS user_id FROM users u
                                     WHERE u.primary_team_id = t.id AND u.status = 'ACTIVE'
                                    UNION
                                    SELECT tm.user_id FROM team_members tm
                                      JOIN users u2 ON u2.id = tm.user_id
                                     WHERE tm.team_id = t.id AND tm.left_at IS NULL
                                       AND u2.status = 'ACTIVE') m) AS member_count
                          FROM teams t
                         ORDER BY t.code
                        """)
                .query((rs, n) -> new TeamView(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("code"),
                        rs.getObject("supervisor_id", UUID.class),
                        rs.getObject("parent_team_id", UUID.class),
                        rs.getString("timezone"),
                        rs.getBoolean("active"),
                        rs.getInt("member_count"),
                        rs.getInt("version")))
                .list();
    }

    public Optional<Team> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM teams WHERE id = :id")
                .param("id", id)
                .query(TeamAdminRepository::team)
                .optional();
    }

    public Team create(String name, String code, String timezone, UUID parentTeamId, UUID supervisorId) {
        return jdbc.sql("INSERT INTO teams (name, code, timezone, parent_team_id, supervisor_id)"
                        + " VALUES (:name, :code, coalesce(:timezone, 'Europe/Warsaw'), :parent, :supervisor)"
                        + " RETURNING " + COLUMNS)
                .param("name", name)
                .param("code", code)
                .param("timezone", timezone)
                .param("parent", parentTeamId)
                .param("supervisor", supervisorId)
                .query(TeamAdminRepository::team)
                .single();
    }

    /** @return empty when the version did not match, which the caller turns into {@code 409} */
    public Optional<Team> update(
            UUID id,
            int expectedVersion,
            String name,
            UUID supervisorId,
            boolean clearSupervisor,
            UUID parentTeamId,
            boolean clearParent,
            String timezone,
            Boolean active) {
        return jdbc.sql("""
                        UPDATE teams
                           SET name = coalesce(:name, name),
                               supervisor_id = CASE WHEN :clearSupervisor THEN NULL
                                                    ELSE coalesce(:supervisor, supervisor_id) END,
                               parent_team_id = CASE WHEN :clearParent THEN NULL
                                                     ELSE coalesce(:parent, parent_team_id) END,
                               timezone = coalesce(:timezone, timezone),
                               active = coalesce(:active, active),
                               updated_at = now(),
                               version = version + 1
                         WHERE id = :id AND version = :expectedVersion
                        RETURNING """ + " " + COLUMNS)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .param("name", name)
                .param("supervisor", supervisorId)
                .param("clearSupervisor", clearSupervisor)
                .param("parent", parentTeamId)
                .param("clearParent", clearParent)
                .param("timezone", timezone)
                .param("active", active)
                .query(TeamAdminRepository::team)
                .optional();
    }

    /**
     * Whether making {@code parentTeamId} the parent of {@code teamId} would close a cycle (§9.3's
     * {@code 422}).
     *
     * <p>Walked in SQL with a recursive CTE rather than in a loop of queries: a cycle that already
     * exists in the data would make a naive walk in Java run forever, and {@code CYCLE} makes
     * PostgreSQL stop at the repeat. {@code ck_teams_no_self_parent} covers the one-hop case only.
     */
    public boolean wouldCycle(UUID teamId, UUID parentTeamId) {
        if (parentTeamId == null) {
            return false;
        }
        if (parentTeamId.equals(teamId)) {
            return true;
        }
        return jdbc.sql("""
                        WITH RECURSIVE ancestors AS (
                            SELECT id, parent_team_id FROM teams WHERE id = :parent
                            UNION ALL
                            SELECT t.id, t.parent_team_id
                              FROM teams t JOIN ancestors a ON t.id = a.parent_team_id
                        ) CYCLE id SET is_cycle USING path
                        SELECT count(*) FROM ancestors WHERE id = :team
                        """)
                        .param("parent", parentTeamId)
                        .param("team", teamId)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    /** Idempotent: re-adding an active member changes nothing, so a retry converges (RB-EC-06). */
    public boolean addMember(UUID teamId, UUID userId, boolean primary) {
        return jdbc.sql("""
                        INSERT INTO team_members (team_id, user_id, is_primary)
                        VALUES (:team, :user, :primary)
                        ON CONFLICT (team_id, user_id) DO UPDATE
                            SET left_at = NULL, is_primary = EXCLUDED.is_primary
                          WHERE team_members.left_at IS NOT NULL
                             OR team_members.is_primary <> EXCLUDED.is_primary
                        """)
                        .param("team", teamId)
                        .param("user", userId)
                        .param("primary", primary)
                        .update()
                == 1;
    }

    /** §9.3: "sets {@code left_at}" — a membership that ended, not a row that never existed. */
    public boolean removeMember(UUID teamId, UUID userId) {
        return jdbc.sql("""
                        UPDATE team_members SET left_at = now()
                         WHERE team_id = :team AND user_id = :user AND left_at IS NULL
                        """).param("team", teamId).param("user", userId).update() == 1;
    }

    /**
     * Active memberships other than this one, counting the primary team on {@code users} as a
     * membership — §9.3 refuses the removal when it would be the user's only one, and a user who
     * belongs to no team at all has no {@code TEAM} scope and cannot own a client (CP-BR-03).
     */
    public int otherMemberships(UUID userId, UUID excludingTeamId) {
        return jdbc.sql("""
                        SELECT count(*) FROM (
                            SELECT tm.team_id FROM team_members tm
                             WHERE tm.user_id = :user AND tm.left_at IS NULL AND tm.team_id <> :team
                            UNION
                            SELECT u.primary_team_id FROM users u
                             WHERE u.id = :user AND u.primary_team_id IS NOT NULL
                               AND u.primary_team_id <> :team) m
                        """)
                .param("user", userId)
                .param("team", excludingTeamId)
                .query(Integer.class)
                .single();
    }

    public boolean isMember(UUID teamId, UUID userId) {
        return jdbc.sql("""
                        SELECT count(*) FROM team_members
                         WHERE team_id = :team AND user_id = :user AND left_at IS NULL
                        """)
                        .param("team", teamId)
                        .param("user", userId)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    private static Team team(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Team(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("code"),
                rs.getObject("supervisor_id", UUID.class),
                rs.getObject("parent_team_id", UUID.class),
                rs.getString("timezone"),
                rs.getBoolean("active"),
                rs.getInt("version"));
    }

    public record Team(
            UUID id,
            String name,
            String code,
            UUID supervisorId,
            UUID parentTeamId,
            String timezone,
            boolean active,
            int version) {}

    public record TeamView(
            UUID id,
            String name,
            String code,
            UUID supervisorId,
            UUID parentTeamId,
            String timezone,
            boolean active,
            int memberCount,
            int version) {}
}
