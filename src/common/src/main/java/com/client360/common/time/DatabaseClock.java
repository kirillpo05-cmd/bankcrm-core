package com.client360.common.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The one clock the system trusts: PostgreSQL {@code now()} (CLAUDE.md rule 7, TR-EC-15).
 *
 * <p>Inside a transaction {@code now()} is the transaction's start time — the same instant every
 * {@code DEFAULT now()} column written in that transaction receives — so an event envelope's
 * {@code occurredAt} and the row's {@code created_at} agree exactly.
 */
@Component
public class DatabaseClock {

    private final JdbcClient jdbc;

    public DatabaseClock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Instant now() {
        return jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
    }

    /** {@code CURRENT_DATE} in the session time zone, which is UTC for this database. */
    public LocalDate today() {
        return jdbc.sql("SELECT CURRENT_DATE").query(LocalDate.class).single();
    }
}
