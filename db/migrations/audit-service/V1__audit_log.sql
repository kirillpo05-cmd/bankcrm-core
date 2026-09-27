-- Client360 / audit-service — V1: the audit log.
--
-- SPEC.md §8.2. This schema is the compliance evidence store, and its correctness matters more
-- than its throughput (.claude/rules/audit-service.md). Everything here exists to make one
-- sentence true: nothing in this table can be changed or removed after it is written.
--
-- Two independent layers enforce that, because one is a single point of failure (§8.2.3):
--
--   1. Privilege. db/init/01_bootstrap.sql grants only SELECT and INSERT in this schema, by
--      default, for every table Flyway creates here. No migration may ever widen that.
--   2. A statement-level trigger, below, which refuses UPDATE, DELETE and TRUNCATE even for a
--      superuser, whose privileges the first layer cannot restrain.
--
-- The trigger is not inheritable: PostgreSQL does not cascade a trigger from a partitioned table
-- to its partitions for statement-level events, so **every partition needs its own**, attached in
-- the same transaction that creates it. A partition without its trigger is a silent hole in the
-- append-only guarantee, which is why create_audit_partition() below does both or neither.

SET LOCAL search_path = audit, public;

-- ------------------------------------------------------------------- enums
CREATE TYPE audit.audit_action AS ENUM (
    'CREATE','UPDATE','DELETE','MERGE',
    'READ_SENSITIVE','EXPORT',
    'LOGIN_SUCCESS','LOGIN_FAILURE','LOGOUT','TOKEN_REFRESH',
    'PERMISSION_DENIED','ROLE_CHANGE','ACCESS_GRANT','ACCESS_REVOKE');

CREATE TYPE audit.export_status AS ENUM ('QUEUED','RUNNING','COMPLETED','FAILED','EXPIRED');

-- --------------------------------------------------------------- audit_log
CREATE TABLE audit.audit_log (
    id             UUID               NOT NULL DEFAULT gen_random_uuid(),
    -- Producer-assigned, and the idempotency key: at-least-once delivery from Kafka becomes
    -- exactly-once storage because a redelivery collides with ux_audit_event (AT-BR-03).
    event_id       UUID               NOT NULL,
    -- One hash chain per Kafka partition, not one global chain. A global chain would force a
    -- single-threaded consumer and make audit the throughput ceiling of the whole system.
    chain_id       SMALLINT           NOT NULL,
    chain_seq      BIGINT             NOT NULL,
    -- Producer clock, and the partition key.
    occurred_at    TIMESTAMPTZ        NOT NULL,
    -- Consumer clock. Both are kept so pipeline lag is visible per row as lagMs, and a delayed
    -- audit entry reads as delayed rather than silently back-dated (AT-BR-07).
    recorded_at    TIMESTAMPTZ        NOT NULL DEFAULT now(),

    -- Actor, denormalized and never a foreign key: this service owns no user table, and an audit
    -- row must stay readable after the user it names is gone. actor_email is why user email is
    -- deliberately plaintext in client-service (§4.7) — it is corporate directory data.
    actor_id       UUID,
    actor_email    VARCHAR(255),
    actor_role     VARCHAR(32),
    actor_ip       INET,
    user_agent     VARCHAR(512),

    service        VARCHAR(32)        NOT NULL,
    entity_type    VARCHAR(48)        NOT NULL,
    entity_id      UUID               NOT NULL,
    client_id      UUID,
    action         audit.audit_action NOT NULL,
    -- Sensitive values are already the literal ***MASKED*** when they arrive (AR-01). This column
    -- records THAT a field changed, never what it became (AT-BR-04) — which is what makes 7-year
    -- retention and GDPR erasure compatible: an erased client leaves rows holding only a UUID.
    changed_fields JSONB,
    context        JSONB,

    request_id     UUID,
    correlation_id UUID,
    kafka_offset   BIGINT             NOT NULL,

    -- Tamper evidence. row_hash covers prev_hash, so altering or removing any row breaks every
    -- subsequent hash in its chain (§8.2.4).
    prev_hash      BYTEA,
    row_hash       BYTEA              NOT NULL,

    -- The partition key has to be in the primary key of a partitioned table.
    CONSTRAINT pk_audit_log PRIMARY KEY (occurred_at, id),
    CONSTRAINT ck_audit_chain_seq CHECK (chain_seq >= 0),
    -- Guards the FUTURE direction: a row cannot claim to have been recorded more than an hour
    -- before it happened, which is what a producer with a fast clock would produce.
    --
    -- It deliberately does NOT reject back-dated events, even though AT-EC-04 rejects them: that
    -- rejection belongs to the consumer, which sends them to the DLT as CLOCK_SKEW_SUSPECTED. A
    -- CHECK here would also block the operator-approved backfill after a long outage, which
    -- legitimately inserts old events stamped `context.backfill: true`. The rule and the
    -- constraint guard opposite ends on purpose.
    CONSTRAINT ck_audit_recorded_after CHECK (recorded_at >= occurred_at - INTERVAL '1 hour'),
    -- A failed login is the one action with no authenticated actor to name.
    CONSTRAINT ck_audit_actor_present CHECK (
        actor_id IS NOT NULL OR action = 'LOGIN_FAILURE'),
    CONSTRAINT ck_audit_hash_length CHECK (
        length(row_hash) = 32 AND (prev_hash IS NULL OR length(prev_hash) = 32))
) PARTITION BY RANGE (occurred_at);

COMMENT ON TABLE audit.audit_log IS
    'Append-only, 7-year retention (SPEC.md §8.2). Enforced by revoked privileges AND a '
    'per-partition statement trigger. Never grant UPDATE or DELETE on this table to any role.';

-- A unique index on a partitioned table must include the partition key. Producers always supply
-- occurred_at, so the redelivery lookup stays an exact match rather than a scan.
--
-- One caveat these two names cannot escape: PostgreSQL implements a unique index on a partitioned
-- parent as one index per partition, each with a generated name, and a violation reports the
-- partition's name — `audit_log_2026_09_occurred_at_event_id_idx`, never `ux_audit_event`. The
-- naming convention of db-migrations.md therefore cannot hold here, and the consumer has to
-- recognise the redelivery collision by its key columns rather than by a constraint name.
CREATE UNIQUE INDEX ux_audit_event ON audit.audit_log (occurred_at, event_id);
CREATE UNIQUE INDEX ux_audit_chain ON audit.audit_log (occurred_at, chain_id, chain_seq);

-- AT-US-01: everything that ever happened to one client.
CREATE INDEX ix_audit_client ON audit.audit_log (client_id, occurred_at DESC) WHERE client_id IS NOT NULL;
-- AT-US-02: everything one member of staff did.
CREATE INDEX ix_audit_actor ON audit.audit_log (actor_id, occurred_at DESC);
CREATE INDEX ix_audit_entity ON audit.audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_audit_action ON audit.audit_log (action, occurred_at DESC);
-- "Which changes touched this field", without a scan. jsonb_path_ops is smaller and faster than
-- the default opclass for existence queries, which is all this index is asked.
CREATE INDEX ix_audit_changed ON audit.audit_log USING gin (changed_fields jsonb_path_ops);

-- ------------------------------------------------- append-only enforcement
CREATE OR REPLACE FUNCTION audit.audit_log_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only (attempted % on %)', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

COMMENT ON FUNCTION audit.audit_log_immutable() IS
    'Refuses every UPDATE, DELETE and TRUNCATE on an audit_log partition (AT-BR-01). Attached by '
    'audit.create_audit_partition() in the same transaction that creates the partition.';

-- On the partitioned parent, which is what catches a statement aimed at the table by name:
-- `UPDATE audit.audit_log SET ...` fires the parent's statement triggers and none of its
-- partitions'. The per-partition triggers below catch the other direction — a statement aimed at a
-- partition directly. Both are needed, and a schema with only one of them looks protected while
-- half of the cases go through silently.
CREATE TRIGGER trg_audit_log_no_change
    BEFORE UPDATE OR DELETE OR TRUNCATE ON audit.audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit.audit_log_immutable();

-- Creates one monthly partition and arms it, or does neither.
--
-- Both statements are in one function so they cannot be separated by a mistake or a failure: a
-- partition that exists without its trigger would accept writes the log is supposed to refuse,
-- and nothing would report it (AT-EC-13). Called by the scheduled job that keeps three months
-- ahead of now, and by this migration for the current month.
CREATE OR REPLACE FUNCTION audit.create_audit_partition(month_start DATE) RETURNS TEXT
LANGUAGE plpgsql AS $$
DECLARE
    partition_name TEXT := 'audit_log_' || to_char(month_start, 'YYYY_MM');
    range_end      DATE := (month_start + INTERVAL '1 month')::date;
BEGIN
    IF to_regclass('audit.' || partition_name) IS NOT NULL THEN
        RETURN partition_name;
    END IF;

    EXECUTE format(
        'CREATE TABLE audit.%I PARTITION OF audit.audit_log FOR VALUES FROM (%L) TO (%L)',
        partition_name, month_start, range_end);

    -- Statement-level triggers are not inherited from the partitioned parent, so each partition
    -- carries its own. This is the line that makes the append-only guarantee real.
    EXECUTE format(
        'CREATE TRIGGER trg_%I_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON audit.%I'
        ' FOR EACH STATEMENT EXECUTE FUNCTION audit.audit_log_immutable()',
        partition_name, partition_name);

    RETURN partition_name;
END;
$$;

-- The current month, so the log can accept a write the moment the service starts. The scheduled
-- job creates the next three; retention detaches and archives expired ones (AT-BR-09) and never
-- drops them in place.
SELECT audit.create_audit_partition(date_trunc('month', now())::date);

-- --------------------------------------------------------- audit_chain_head
-- Sealed every 1 000 rows and mirrored nightly to external WORM storage, so recomputing a
-- rewritten chain locally still cannot match the head held elsewhere (AT-BR-06).
--
-- Never detached, unlike a partition: ingestion reads the previous head on every batch, and a
-- head in cold storage would make the write path depend on an archived table (AT-EC-03).
CREATE TABLE audit.audit_chain_head (
    chain_id  SMALLINT    NOT NULL,
    chain_seq BIGINT      NOT NULL,
    head_hash BYTEA       NOT NULL,
    sealed_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_audit_chain_head PRIMARY KEY (chain_id, chain_seq),
    CONSTRAINT ck_chain_head_hash CHECK (length(head_hash) = 32),
    CONSTRAINT ck_chain_head_seq CHECK (chain_seq >= 0)
);

CREATE TRIGGER trg_audit_chain_head_no_change
    BEFORE UPDATE OR DELETE OR TRUNCATE ON audit.audit_chain_head
    FOR EACH STATEMENT EXECUTE FUNCTION audit.audit_log_immutable();

-- ----------------------------------------------------- audit_consumer_state
-- Per-partition ingestion progress, and the only table in this schema that is updated rather than
-- appended: it is bookkeeping about the pipeline, not evidence about anything that happened. It is
-- therefore deliberately NOT covered by the immutability trigger, and bootstrap's SELECT/INSERT
-- default is not enough for it — hence the explicit grant.
CREATE TABLE audit.audit_consumer_state (
    topic             VARCHAR(64) NOT NULL,
    partition_no      SMALLINT    NOT NULL,
    last_offset       BIGINT      NOT NULL,
    last_event_at     TIMESTAMPTZ NOT NULL,
    last_processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_audit_consumer_state PRIMARY KEY (topic, partition_no),
    CONSTRAINT ck_consumer_offset CHECK (last_offset >= 0)
);

-- §8.2.6 specifies `lag_seconds GENERATED ALWAYS AS (0) STORED` here and then says lag is computed
-- at read time as now() - last_event_at. A column that is always zero and never read is not a
-- placeholder, it is a field that will eventually be mistaken for the answer. Omitted; the spec
-- was amended in the same change.

GRANT SELECT, INSERT, UPDATE ON audit.audit_consumer_state TO client360_app;

-- ------------------------------------------------------- audit_export_jobs
-- §8.2.5. Also mutable — a job moves QUEUED → RUNNING → COMPLETED — and for the same reason as
-- consumer state: it records work about the log, not an event in it.
CREATE TABLE audit.audit_export_jobs (
    id           UUID                PRIMARY KEY DEFAULT gen_random_uuid(),
    requested_by UUID                NOT NULL,
    filter       JSONB               NOT NULL,
    status       audit.export_status NOT NULL DEFAULT 'QUEUED',
    row_count    BIGINT,
    storage_key  VARCHAR(512),
    error        TEXT,
    requested_at TIMESTAMPTZ         NOT NULL DEFAULT now(),
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,
    -- A download link is valid for 15 minutes (AT-US-04); the job itself expires so the object can
    -- be collected rather than left in the bucket indefinitely.
    expires_at   TIMESTAMPTZ,

    CONSTRAINT ck_export_terminal_pair CHECK (
        (status IN ('COMPLETED','FAILED')) = (finished_at IS NOT NULL)),
    CONSTRAINT ck_export_failure_reason CHECK (status <> 'FAILED' OR error IS NOT NULL),
    CONSTRAINT ck_export_result CHECK (status <> 'COMPLETED' OR storage_key IS NOT NULL)
);

GRANT SELECT, INSERT, UPDATE ON audit.audit_export_jobs TO client360_app;

CREATE INDEX ix_export_jobs_requester ON audit.audit_export_jobs (requested_by, requested_at DESC);
