-- Client360 / interaction-service — V8: tasks, reminders and task history.
--
-- SPEC.md §7.2.1 (enums), §7.2.2 (tasks), §7.2.3 (task_reminders), §7.2.4 (task_history).
--
-- Cross-schema FK note: the REFERENCES into client.clients and client.users are correct for the
-- SHARED development database only. In production these services own separate database instances
-- and the columns carry plain UUIDs, validated by a REST call to client-service. Do not add these
-- FKs to production config (.claude/rules/interaction-service.md).
--
-- The one thing this schema deliberately does not have is an `is_overdue` column. Overdue is
-- `due_at < now() AND status IN ('OPEN','IN_PROGRESS')`, computed at query time: now() is not
-- IMMUTABLE so PostgreSQL cannot put it in a generated column, and a materialized boolean would
-- need a sweeper to stay honest — which means a window in which the database disagrees with itself
-- about what is late (TR-BR-02). The partial indexes at the bottom are what make computing it cheap.

SET LOCAL search_path = interaction, public;

-- ----------------------------------------------------------------- enums
CREATE TYPE interaction.task_status      AS ENUM ('OPEN','IN_PROGRESS','DONE','CANCELLED');
CREATE TYPE interaction.task_priority    AS ENUM ('LOW','MEDIUM','HIGH','URGENT');
CREATE TYPE interaction.task_type        AS ENUM ('CALLBACK','DOCUMENT_REQUEST','KYC_REFRESH',
                                                  'FOLLOW_UP','MEETING_PREP','COMPLIANCE','OTHER');
CREATE TYPE interaction.reminder_channel AS ENUM ('IN_APP','EMAIL');
CREATE TYPE interaction.reminder_status  AS ENUM ('SCHEDULED','SENT','FAILED','CANCELLED');

-- ----------------------------------------------------------------- tasks
CREATE TABLE interaction.tasks (
    id                    UUID                      PRIMARY KEY DEFAULT gen_random_uuid(),
    -- TR-BR-01: NOT NULL and immutable. This is what lets task authorization derive from the
    -- client's scope with no second mechanism, and it matches the domain — a bank task without a
    -- customer is not follow-up work.
    client_id             UUID                      NOT NULL,
    source_interaction_id UUID,
    title                 VARCHAR(200)              NOT NULL,
    description           TEXT,
    task_type             interaction.task_type     NOT NULL DEFAULT 'FOLLOW_UP',
    priority              interaction.task_priority NOT NULL DEFAULT 'MEDIUM',
    status                interaction.task_status   NOT NULL DEFAULT 'OPEN',
    due_at                TIMESTAMPTZ               NOT NULL,
    -- Frozen at creation; snoozes never touch it. It is the record of what was originally
    -- promised, and ck_tasks_due_forward is what keeps that record from being rewritten.
    original_due_at       TIMESTAMPTZ               NOT NULL,
    assignee_id           UUID                      NOT NULL,
    created_by            UUID                      NOT NULL,

    -- snooze (TR-BR-06)
    snooze_count          SMALLINT                  NOT NULL DEFAULT 0,
    last_snoozed_at       TIMESTAMPTZ,
    last_snooze_reason    TEXT,

    -- completion / cancellation
    completed_at          TIMESTAMPTZ,
    completed_by          UUID,
    completion_note       TEXT,
    cancelled_at          TIMESTAMPTZ,
    cancelled_by          UUID,
    cancellation_reason   TEXT,

    -- escalation (TR-BR-08)
    escalation_level      SMALLINT                  NOT NULL DEFAULT 0,
    escalated_at          TIMESTAMPTZ,

    -- housekeeping
    version               INTEGER                   NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ               NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ               NOT NULL DEFAULT now(),

    CONSTRAINT fk_tasks_client FOREIGN KEY (client_id)
        REFERENCES client.clients (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tasks_assignee FOREIGN KEY (assignee_id)
        REFERENCES client.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tasks_creator FOREIGN KEY (created_by)
        REFERENCES client.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tasks_completer FOREIGN KEY (completed_by)
        REFERENCES client.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tasks_canceller FOREIGN KEY (cancelled_by)
        REFERENCES client.users (id) ON DELETE RESTRICT,
    -- SET NULL, not RESTRICT: an interaction can be soft-deleted, and a task that outlived the
    -- note it came from is still work somebody owes a customer. The link is provenance, not a
    -- dependency.
    CONSTRAINT fk_tasks_source FOREIGN KEY (source_interaction_id)
        REFERENCES interaction.interactions (id) ON DELETE SET NULL,

    -- A DONE task has both of its completion fields or neither. Two separate equalities rather
    -- than one over a conjunction, so the error message names which half is missing.
    CONSTRAINT ck_tasks_done_fields CHECK (
        (status = 'DONE') = (completed_at IS NOT NULL)
        AND (status = 'DONE') = (completed_by IS NOT NULL)),
    CONSTRAINT ck_tasks_cancelled_fields CHECK (
        (status = 'CANCELLED') = (cancelled_at IS NOT NULL)
        AND (status <> 'CANCELLED' OR cancellation_reason IS NOT NULL)),
    CONSTRAINT ck_tasks_snooze_cap CHECK (snooze_count BETWEEN 0 AND 3),
    CONSTRAINT ck_tasks_snooze_reason CHECK (snooze_count = 0 OR last_snooze_reason IS NOT NULL),
    -- TR-BR-04. Pulling a deadline earlier erases the record of the original commitment; the
    -- correct action is a new, more urgent task. A constraint rather than a service check, because
    -- this is the kind of rule a well-meaning bulk update talks its way around.
    CONSTRAINT ck_tasks_due_forward CHECK (due_at >= original_due_at),
    CONSTRAINT ck_tasks_escalation CHECK (escalation_level BETWEEN 0 AND 3),
    CONSTRAINT ck_tasks_escalated_pair CHECK ((escalation_level = 0) = (escalated_at IS NULL)),
    CONSTRAINT ck_tasks_title_not_blank CHECK (length(btrim(title)) >= 3)
);

COMMENT ON TABLE interaction.tasks IS
    'Follow-up work (SPEC.md §7). Overdue is computed, never stored (TR-BR-02).';

COMMENT ON COLUMN interaction.tasks.original_due_at IS
    'What was originally promised. Snoozes move due_at and never this (TR-BR-04), which is also '
    'how "completed late" is derived: completed_at > original_due_at.';

-- There is no CHECK that due_at is in the future. It cannot be one — now() is not IMMUTABLE — and
-- it would be wrong anyway: a task whose deadline has passed is the normal state of overdue work.
-- The API refuses a *new* task dated in the past (§7.3), which is the only place the rule applies.

-- ---------------------------------------------------------------- indexes
-- The dashboard query (TR-US-05). Partial, so the index holds only live work: a year of completed
-- tasks is the bulk of the table and none of it is ever overdue.
CREATE INDEX ix_tasks_open_due ON interaction.tasks (due_at, assignee_id)
    WHERE status IN ('OPEN', 'IN_PROGRESS');

-- "My Day" (TR-US-02): one manager's live work, soonest first.
CREATE INDEX ix_tasks_assignee_due ON interaction.tasks (assignee_id, due_at)
    WHERE status IN ('OPEN', 'IN_PROGRESS');

-- The client card's Tasks tab. Not partial: the card shows completed tasks too.
CREATE INDEX ix_tasks_client ON interaction.tasks (client_id, status, due_at);

-- Team analytics (TR-US-08): completed-on-time rates per manager.
CREATE INDEX ix_tasks_completed ON interaction.tasks (completed_by, completed_at)
    WHERE status = 'DONE';

-- The escalation sweep (TR-BR-08), every 15 minutes. Excludes level 3 because the ladder has no
-- rung above it, so those rows are work the sweep can never change.
CREATE INDEX ix_tasks_escalation_sweep ON interaction.tasks (due_at)
    WHERE status IN ('OPEN', 'IN_PROGRESS') AND escalation_level < 3;

-- ------------------------------------------------------- task_reminders
CREATE TABLE interaction.task_reminders (
    id         UUID                         PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id    UUID                         NOT NULL,
    remind_at  TIMESTAMPTZ                  NOT NULL,
    channel    interaction.reminder_channel NOT NULL,
    status     interaction.reminder_status  NOT NULL DEFAULT 'SCHEDULED',
    attempts   SMALLINT                     NOT NULL DEFAULT 0,
    sent_at    TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ                  NOT NULL DEFAULT now(),

    -- CASCADE: a reminder is a pure projection of its task with no independent authority, and it
    -- records nothing that happened — a sent one records that in the audit trail, not here.
    CONSTRAINT fk_reminders_task FOREIGN KEY (task_id)
        REFERENCES interaction.tasks (id) ON DELETE CASCADE,
    -- TR-BR-09: delivery is at-least-once, and this is what makes redelivery idempotent. The
    -- notifier suppresses a duplicate email on the same key; a duplicate in-app badge is harmless.
    CONSTRAINT uq_reminders UNIQUE (task_id, remind_at, channel),
    CONSTRAINT ck_reminders_attempts CHECK (attempts BETWEEN 0 AND 5),
    CONSTRAINT ck_reminders_sent_pair CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

CREATE INDEX ix_reminders_due ON interaction.task_reminders (remind_at)
    WHERE status = 'SCHEDULED';

-- ---------------------------------------------------------- task_history
-- A product feature, not a copy of the audit trail. "Who moved this task?" is rendered in the
-- drawer and has to be joinable; audit_log lives in another service with different retention,
-- different access rules and a different owner. Coupling them would compromise both (§7.2.4).
CREATE TABLE interaction.task_history (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id     UUID        NOT NULL,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    changed_by  UUID        NOT NULL,
    -- CREATED, REASSIGNED, SNOOZED, ESCALATED, STATUS_CHANGED. A VARCHAR rather than an enum: the
    -- set grows with product decisions rather than with schema ones, and a row here is read by
    -- people, not switched on by code.
    change_type VARCHAR(32) NOT NULL,
    from_value  TEXT,
    to_value    TEXT,
    reason      TEXT,

    CONSTRAINT fk_task_history_task FOREIGN KEY (task_id)
        REFERENCES interaction.tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_history_user FOREIGN KEY (changed_by)
        REFERENCES client.users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_task_history_type CHECK (length(btrim(change_type)) > 0)
);

CREATE INDEX ix_task_history_task ON interaction.task_history (task_id, changed_at DESC);

COMMENT ON TABLE interaction.task_history IS
    'Product history for the task drawer (§7.2.4). Deliberately duplicates part of the audit '
    'trail: different retention, different access, different owner.';
