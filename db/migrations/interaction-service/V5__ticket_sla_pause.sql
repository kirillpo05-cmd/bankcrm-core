-- Client360 / interaction-service — V5: SLA pause accounting for tickets.
--
-- SPEC.md §6.4 IL-BR-08: "WAITING_CLIENT pauses the SLA clock; the paused duration is
-- recorded and excluded from breach calculation." §6.2.2 gave the ticket its status,
-- priority, assignee and sla_due_at but nowhere to record the pause, so the rule had
-- no column to live in. This adds it.
--
-- How the pause works, so the columns read as a decision rather than two spare fields:
--
--   * Entering WAITING_CLIENT stamps waiting_since.
--   * Leaving it adds the BUSINESS hours elapsed since then (BusinessHours.between,
--     the team's timezone per TR-BR-14) to BOTH sla_due_at and sla_paused_seconds,
--     and clears waiting_since.
--
-- Shifting sla_due_at rather than subtracting at read time is what keeps "breached"
-- a plain comparison against now(), so ix_int_ticket_queue still serves the queue and
-- the ?slaBreached=true filter of §6.3 stays an index scan. sla_paused_seconds is the
-- audit of how much was given back — without it a shifted deadline would be
-- indistinguishable from one that had simply been generous.
--
-- Lock behaviour: ADD COLUMN with a non-volatile DEFAULT and a nullable column are both
-- metadata-only in PostgreSQL 16; no table rewrite. The table is empty in every
-- environment this has reached, so the CHECKs are added validated rather than NOT VALID.

SET LOCAL search_path = interaction, public;

ALTER TABLE interaction.interactions
    ADD COLUMN sla_paused_seconds BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN waiting_since      TIMESTAMPTZ;

COMMENT ON COLUMN interaction.interactions.sla_paused_seconds IS
    'Total business seconds the SLA has been paused in WAITING_CLIENT (IL-BR-08). '
    'Reporting only: sla_due_at has already been shifted by the same amount.';

COMMENT ON COLUMN interaction.interactions.waiting_since IS
    'When the current WAITING_CLIENT period began; NULL at every other status. '
    'Also what the IL-EC-10 staleness sweep measures against.';

-- The pair is exact in both directions. Written with IS DISTINCT FROM because
-- ticket_status is NULL on every non-ticket row, and `ticket_status = 'WAITING_CLIENT'`
-- would then be NULL — which a CHECK treats as satisfied, letting a plain NOTE carry a
-- waiting_since nothing would ever clear.
ALTER TABLE interaction.interactions
    ADD CONSTRAINT ck_interactions_waiting_pair CHECK (
        CASE WHEN ticket_status IS DISTINCT FROM 'WAITING_CLIENT'
             THEN waiting_since IS NULL
             ELSE waiting_since IS NOT NULL
        END
    );

-- Time cannot be given back that was never taken, and only a ticket has an SLA to pause.
ALTER TABLE interaction.interactions
    ADD CONSTRAINT ck_interactions_sla_paused CHECK (
        sla_paused_seconds >= 0
        AND (ticket_status IS NOT NULL OR sla_paused_seconds = 0)
    );

-- IL-EC-10: "Ticket sits in WAITING_CLIENT for 3 weeks" — a pause must not become a hiding place.
-- now() is not IMMUTABLE and cannot appear in the predicate, so the age is compared at query time
-- against this partial index (the same shape TR-BR-02 uses for overdue).
--
-- What this index does NOT serve: GET /tickets?stale=true. That query is always anchored by an
-- assignee or a client, so the planner drives it from ix_int_ticket_queue and applies staleness as
-- a filter — verified with EXPLAIN on 300k rows. It is also worth knowing why it could not serve
-- it: the optional filters there are `NOT :param OR (...)`, and under a generic plan PostgreSQL
-- will not fold a bind parameter, so that predicate is never an index condition.
--
-- This index exists for the global sweep the rule actually describes — "flag every ticket paused
-- beyond 14 days, across everyone" — which has no caller yet, because the cross-team view it feeds
-- needs client ownership denormalized onto this table first (see GET /tickets in SPEC.md §6.3).
CREATE INDEX ix_int_ticket_waiting_since ON interaction.interactions (waiting_since)
    WHERE ticket_status = 'WAITING_CLIENT' AND deleted_at IS NULL;
