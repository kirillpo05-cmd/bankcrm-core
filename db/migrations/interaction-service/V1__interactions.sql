-- Client360 / interaction-service — V1: interactions.
--
-- SPEC.md §6.2.1 (enums), §6.2.2 (table, constraints, indexes), §4.12 (why the
-- clock-skew rule is a trigger).
--
-- Cross-schema FK note: the REFERENCES into client.clients and client.users are
-- correct for the SHARED development database only. In production these services
-- own separate database instances and the columns carry plain UUIDs, validated
-- by a REST call to client-service. Do not add these FKs to production config
-- (.claude/rules/interaction-service.md).

SET LOCAL search_path = interaction, public;

-- ----------------------------------------------------------------- enums
CREATE TYPE interaction.interaction_type       AS ENUM ('CALL','MEETING','EMAIL','CHAT','NOTE','TICKET');
CREATE TYPE interaction.interaction_direction  AS ENUM ('INBOUND','OUTBOUND','INTERNAL');
CREATE TYPE interaction.interaction_visibility AS ENUM ('TEAM','PRIVATE');
CREATE TYPE interaction.interaction_outcome    AS ENUM ('SUCCESSFUL','NO_ANSWER','CALLBACK_REQUESTED',
                                                        'ESCALATED','REFUSED','NOT_APPLICABLE');
CREATE TYPE interaction.interaction_source     AS ENUM ('WEB','API','TELEPHONY_IMPORT','EMAIL_IMPORT','MIGRATION');
CREATE TYPE interaction.ticket_status          AS ENUM ('NEW','IN_PROGRESS','WAITING_CLIENT','RESOLVED','CLOSED','REJECTED');
CREATE TYPE interaction.ticket_priority        AS ENUM ('LOW','MEDIUM','HIGH','CRITICAL');

-- ---------------------------------------------------------- interactions
CREATE TABLE interaction.interactions (
    id                 UUID                              PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id          UUID                              NOT NULL,
    type               interaction.interaction_type      NOT NULL,
    direction          interaction.interaction_direction NOT NULL,
    -- Plaintext and searchable. The body is encrypted, so there is no full-text
    -- search over note bodies — a deliberate trade-off (§4.7), not a gap.
    subject            VARCHAR(200)                      NOT NULL,
    body_enc           BYTEA                             NOT NULL,
    key_version        SMALLINT                          NOT NULL DEFAULT 1,
    occurred_at        TIMESTAMPTZ                       NOT NULL,
    duration_seconds   INTEGER,
    outcome            interaction.interaction_outcome   NOT NULL DEFAULT 'NOT_APPLICABLE',
    visibility         interaction.interaction_visibility NOT NULL DEFAULT 'TEAM',
    source             interaction.interaction_source    NOT NULL DEFAULT 'WEB',
    external_ref       VARCHAR(64),                      -- PBX call id, email message-id
    author_id          UUID                              NOT NULL,

    -- Ticket subtype. Present if and only if type = 'TICKET'.
    ticket_status      interaction.ticket_status,
    ticket_priority    interaction.ticket_priority,
    ticket_assignee_id UUID,
    sla_due_at         TIMESTAMPTZ,
    resolved_at        TIMESTAMPTZ,
    closed_at          TIMESTAMPTZ,
    resolution_note    TEXT,

    -- Append-only correction chain (IL-BR-03). The original is never rewritten.
    corrects_id        UUID,
    edited_at          TIMESTAMPTZ,
    edit_count         SMALLINT                          NOT NULL DEFAULT 0,

    version            INTEGER                           NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ                       NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ                       NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ,
    deleted_by         UUID,
    deletion_reason    TEXT,

    CONSTRAINT fk_int_client FOREIGN KEY (client_id)
        REFERENCES client.clients (id) ON DELETE RESTRICT,
    CONSTRAINT fk_int_author FOREIGN KEY (author_id)
        REFERENCES client.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_int_assignee FOREIGN KEY (ticket_assignee_id)
        REFERENCES client.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_int_corrects FOREIGN KEY (corrects_id)
        REFERENCES interaction.interactions (id) ON DELETE RESTRICT,

    CONSTRAINT ck_interactions_ticket_fields CHECK (
        (type = 'TICKET') = (ticket_status IS NOT NULL)
        AND (type = 'TICKET') = (ticket_priority IS NOT NULL)
        AND (type = 'TICKET') = (sla_due_at IS NOT NULL)
    ),
    CONSTRAINT ck_interactions_resolved_pair CHECK (
        ticket_status IS NULL
        OR ((ticket_status IN ('RESOLVED','CLOSED')) = (resolved_at IS NOT NULL))
    ),
    CONSTRAINT ck_interactions_resolution_note CHECK (
        ticket_status IS NULL
        OR ticket_status NOT IN ('RESOLVED','REJECTED')
        OR resolution_note IS NOT NULL
    ),
    CONSTRAINT ck_interactions_closed_after_resolved CHECK (
        closed_at IS NULL OR resolved_at IS NULL OR closed_at >= resolved_at
    ),
    -- Duration only makes sense for synchronous contact.
    CONSTRAINT ck_interactions_duration CHECK (
        duration_seconds IS NULL
        OR (type IN ('CALL','MEETING') AND duration_seconds BETWEEN 0 AND 86400)
    ),
    CONSTRAINT ck_interactions_direction CHECK (
        type NOT IN ('NOTE','TICKET') OR direction = 'INTERNAL'
    ),
    CONSTRAINT ck_interactions_delete_reason CHECK (
        deleted_at IS NULL OR deletion_reason IS NOT NULL
    ),
    CONSTRAINT ck_interactions_no_self_correct CHECK (
        corrects_id IS NULL OR corrects_id <> id
    ),
    -- Cap flags automation misuse, not human editing (IL-BR-04).
    CONSTRAINT ck_interactions_edit_count CHECK (edit_count BETWEEN 0 AND 100)
    -- "occurred_at at most 5 min ahead" is trg_interactions_validate below:
    -- now() is STABLE and cannot appear in a CHECK (§4.12, IL-EC-02).
);

COMMENT ON TABLE interaction.interactions IS
    'Immutable in substance: type, client_id, occurred_at and author_id never '
    'change. Only subject/body, only by the author, only within 15 min (IL-BR-01).';

-- ------------------------------------------------- temporal validation (§4.12)
CREATE FUNCTION interaction.interactions_validate() RETURNS trigger
LANGUAGE plpgsql AS $fn$
BEGIN
    -- 5 minutes of client/server clock skew is tolerated; the future is not
    -- loggable (IL-EC-02).
    IF NEW.occurred_at > now() + INTERVAL '5 minutes' THEN
        RAISE EXCEPTION
            'ck_interactions_not_future: occurred_at may be at most 5 minutes ahead of server time (occurred_at=%, now=%)',
            NEW.occurred_at, now()
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$fn$;

CREATE TRIGGER trg_interactions_validate
    BEFORE INSERT OR UPDATE OF occurred_at ON interaction.interactions
    FOR EACH ROW EXECUTE FUNCTION interaction.interactions_validate();

-- ---------------------------------------------------------------- indexes
-- Timeline (IL-US-01): keyset on (occurred_at DESC, id DESC). Never OFFSET.
CREATE INDEX ix_int_client_timeline ON interaction.interactions (client_id, occurred_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- Filtered timeline (IL-US-03)
CREATE INDEX ix_int_client_type_time ON interaction.interactions (client_id, type, occurred_at DESC)
    WHERE deleted_at IS NULL;

-- "My open tickets" queue and the SLA sweep (IL-US-05)
CREATE INDEX ix_int_ticket_queue ON interaction.interactions (ticket_assignee_id, sla_due_at)
    WHERE type = 'TICKET' AND ticket_status NOT IN ('CLOSED','REJECTED') AND deleted_at IS NULL;

-- Subject search (bodies are encrypted — §4.7)
CREATE INDEX ix_int_subject_trgm ON interaction.interactions
    USING gin (lower(subject) gin_trgm_ops)
    WHERE deleted_at IS NULL;

-- Telephony/email import idempotency (IL-BR-12, IL-EC-03)
CREATE UNIQUE INDEX ux_int_external_ref ON interaction.interactions (source, external_ref)
    WHERE external_ref IS NOT NULL AND deleted_at IS NULL;

-- Supervisor coaching feed (IL-US-07)
CREATE INDEX ix_int_author ON interaction.interactions (author_id, occurred_at DESC)
    WHERE deleted_at IS NULL;
