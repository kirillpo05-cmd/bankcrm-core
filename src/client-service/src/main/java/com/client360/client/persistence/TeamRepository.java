package com.client360.client.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code client.teams}. Small on purpose — team administration is §9.3 and arrives with RBAC
 * in v2; this exists for the one field another service needs today.
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
}
