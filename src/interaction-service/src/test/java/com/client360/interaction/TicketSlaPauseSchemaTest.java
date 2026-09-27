package com.client360.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * V5 pause accounting, tested the way {@code .claude/rules/db-migrations.md} asks: by trying to
 * violate every constraint it adds and asserting the database refuses.
 *
 * <p>These rows are inserted directly rather than through the API, because the point is what the
 * schema guarantees when something bypasses the service — a repair script, a future endpoint, a
 * bug. A constraint only tested through the code that already respects it is untested.
 */
class TicketSlaPauseSchemaTest extends AbstractInteractionIntegrationTest {

    /** A ticket must carry waiting_since exactly while it is WAITING_CLIENT. */
    @Test
    void waitingClientWithoutATimestampIsRefused_IL_BR_08() {
        assertThatThrownBy(() -> insertTicket("WAITING_CLIENT", null, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_interactions_waiting_pair");
    }

    @Test
    void aTimestampAtAnyOtherStatusIsRefused_IL_BR_08() {
        assertThatThrownBy(() -> insertTicket("IN_PROGRESS", Instant.parse("2026-09-07T09:00:00Z"), 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_interactions_waiting_pair");
    }

    @Test
    void waitingClientWithATimestampIsAccepted_IL_BR_08() {
        UUID id = insertTicket("WAITING_CLIENT", Instant.parse("2026-09-07T09:00:00Z"), 3600);
        assertThat(jdbc.sql("SELECT sla_paused_seconds FROM interaction.interactions WHERE id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isEqualTo(3600L);
    }

    /**
     * The NULL trap this constraint was written around: {@code ticket_status} is NULL on a note, so
     * a naive {@code ticket_status = 'WAITING_CLIENT'} would evaluate to NULL, which a CHECK treats
     * as satisfied — and a note would be able to carry a waiting_since nothing would ever clear.
     */
    @Test
    void aNoteCannotCarryAWaitingTimestamp_IL_BR_08() {
        assertThatThrownBy(() -> insertNote(Instant.parse("2026-09-07T09:00:00Z"), 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_interactions_waiting_pair");
    }

    @Test
    void aNoteCannotCarryPausedTime_IL_BR_08() {
        assertThatThrownBy(() -> insertNote(null, 60))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_interactions_sla_paused");
    }

    /** Time cannot be given back that was never taken. */
    @Test
    void negativePausedTimeIsRefused() {
        assertThatThrownBy(() -> insertTicket("IN_PROGRESS", null, -1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_interactions_sla_paused");
    }

    /** IL-EC-10's staleness sweep reads this index; without it the query is a scan of every row. */
    @Test
    void theStalenessIndexExists_IL_EC_10() {
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pg_indexes
                        WHERE schemaname = 'interaction' AND indexname = 'ix_int_ticket_waiting_since'
                        """).query(Integer.class).single()).isEqualTo(1);
    }

    private UUID insertTicket(String status, Instant waitingSince, long pausedSeconds) {
        return insert("TICKET", status, "MEDIUM", Instant.parse("2026-09-30T09:00:00Z"), waitingSince, pausedSeconds);
    }

    private UUID insertNote(Instant waitingSince, long pausedSeconds) {
        return insert("NOTE", null, null, null, waitingSince, pausedSeconds);
    }

    private UUID insert(
            String type, String status, String priority, Instant slaDueAt, Instant waitingSince, long pausedSeconds) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO interaction.interactions
                    (id, client_id, type, direction, subject, body_enc, occurred_at, author_id,
                     ticket_status, ticket_priority, sla_due_at, waiting_since, sla_paused_seconds)
                VALUES (:id, :clientId, CAST(:type AS interaction.interaction_type), 'INTERNAL',
                        'Card blocked abroad', '\\x00'::bytea, now() - INTERVAL '1 hour', :authorId,
                        CAST(:status AS interaction.ticket_status),
                        CAST(:priority AS interaction.ticket_priority),
                        :slaDueAt, :waitingSince, :paused)
                """)
                .param("id", id)
                .param("clientId", CLIENT_ID)
                .param("type", type)
                .param("authorId", ADAM_NOWAK)
                .param("status", status)
                .param("priority", priority)
                .param("slaDueAt", timestamp(slaDueAt))
                .param("waitingSince", timestamp(waitingSince))
                .param("paused", pausedSeconds)
                .update();
        return id;
    }

    /** The driver cannot infer a SQL type for {@link Instant}; TIMESTAMPTZ wants an offset. */
    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
