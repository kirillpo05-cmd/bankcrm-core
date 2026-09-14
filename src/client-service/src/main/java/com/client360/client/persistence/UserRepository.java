package com.client360.client.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads of {@code client.users} that the Client Profile needs: the owner's display name for a
 * card, and the primary team {@code team_id} is derived from (CP-BR-03).
 *
 * <p>User email is a plaintext column on purpose — corporate directory data, not client PII
 * (SPEC.md §9.2.3). Do not "fix" it.
 */
@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRef> findById(UUID id) {
        return jdbc.sql("""
                        SELECT id, full_name, email, primary_team_id, status::text AS status
                          FROM users
                         WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new UserRef(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getObject("primary_team_id", UUID.class),
                        rs.getString("status")))
                .optional();
    }

    /** Enough of a user to own a client and to render {@code owner} on the card. */
    public record UserRef(UUID id, String fullName, String email, UUID primaryTeamId, String status) {

        public boolean isActive() {
            return "ACTIVE".equals(status);
        }
    }
}
