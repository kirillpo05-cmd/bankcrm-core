-- Client360 / audit-service — V2: the outbox this service needs to audit itself, and retention.
--
-- SPEC.md §8.4 (AT-BR-09, AT-BR-10), §3.2.
--
-- An outbox in audit-service looks at first like a contradiction. AT-BR-02 says audit entries are
-- created only by consuming Kafka, and this service is a consumer only — so why does it need to
-- produce anything?
--
-- Because AT-BR-10 says reading the audit log is itself audited, and the obvious implementation of
-- that is the one thing AT-BR-02 forbids: the read handler inserting its own row. That would be
-- exactly the direct write path the rule exists to deny, and a compromised audit API could then
-- forge entries without touching the broker. So a read writes an outbox row, the relay publishes it
-- to `audit.events`, and the same consumer that stores everyone else's events stores this one. The
-- round trip is the point: this service has no more privilege over audit_log than any other
-- producer, including over its own events.
--
-- Nothing here grants UPDATE or DELETE on audit_log, and nothing ever will (AT-BR-01).

SET LOCAL search_path = audit, public;

-- ------------------------------------------------------------ outbox_events
-- Same shape as client.outbox_events and interaction.outbox_events, deliberately duplicated rather
-- than shared: each service owns its own tables, and a shared outbox would couple independently
-- deployable services at the storage layer.
CREATE TABLE audit.outbox_events (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       UUID        NOT NULL,
    aggregate_type VARCHAR(48) NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    topic          VARCHAR(64) NOT NULL,
    partition_key  VARCHAR(64) NOT NULL,
    -- Nothing sensitive reaches here either (AT-BR-04): a self-audit event records that someone
    -- searched the log and what they filtered on, never a row the search returned.
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       SMALLINT    NOT NULL DEFAULT 0,
    last_error     TEXT,

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_attempts CHECK (attempts BETWEEN 0 AND 100)
);

CREATE INDEX ix_outbox_unpublished ON audit.outbox_events (created_at)
    WHERE published_at IS NULL;

COMMENT ON TABLE audit.outbox_events IS
    'Self-audit only (AT-BR-10). This service publishes its own reads to audit.events and consumes '
    'them back, so it never inserts into audit_log directly (AT-BR-02).';

-- --------------------------------------------------------- partition archive
-- Where a detached partition went, so "the log has a seven-year memory" is checkable rather than
-- asserted. AT-BR-09: expired partitions are detached and archived, never dropped in place, and the
-- archival is itself audited — this table is the record the audit event points at.
CREATE TABLE audit.audit_partition_archive (
    partition_name VARCHAR(64) NOT NULL,
    range_start    DATE        NOT NULL,
    range_end      DATE        NOT NULL,
    row_count      BIGINT      NOT NULL,
    detached_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- NULL until the archival job has copied it out. A detached partition that has not reached
    -- cold storage yet is still in the database under its own name, so nothing is lost in between.
    archived_at    TIMESTAMPTZ,
    storage_key    VARCHAR(512),

    -- Named rather than left to the auto-generated pkey, both because db-migrations.md asks for
    -- it and because detach_expired_partitions needs a name to put in ON CONFLICT: its OUT
    -- parameters are called partition_name and friends, and naming the column there instead made
    -- plpgsql read the variable, which fails as "column reference is ambiguous" at call time.
    CONSTRAINT pk_audit_partition_archive PRIMARY KEY (partition_name),
    CONSTRAINT ck_archive_range CHECK (range_end > range_start),
    CONSTRAINT ck_archive_rows CHECK (row_count >= 0),
    CONSTRAINT ck_archive_storage_pair CHECK ((archived_at IS NULL) = (storage_key IS NULL))
);

-- ------------------------------------------------------- retention (AT-BR-09)
-- Detaches every partition whose whole range is older than the retention window.
--
-- DETACH, never DROP. A dropped partition is gone, and "we deleted the evidence because a job said
-- it was seven years old" is not a position a bank can defend if the job was wrong. Detached, the
-- rows are still in the database under the partition's own name until the archival step has copied
-- them out and someone has verified it.
--
-- Returns the partitions it detached, so the caller can audit each one by name (AT-BR-09).
CREATE OR REPLACE FUNCTION audit.detach_expired_partitions(retain_years INTEGER DEFAULT 7)
RETURNS TABLE (partition_name TEXT, range_start DATE, range_end DATE, row_count BIGINT)
LANGUAGE plpgsql AS $$
DECLARE
    cutoff     DATE := (date_trunc('month', now()) - make_interval(years => retain_years))::date;
    part       RECORD;
    part_rows  BIGINT;
    part_start DATE;
    part_end   DATE;
BEGIN
    IF retain_years < 1 THEN
        -- A guard, not a validation: a caller passing 0 would detach the whole log in one call.
        RAISE EXCEPTION 'retain_years must be at least 1, got %', retain_years;
    END IF;

    FOR part IN
        SELECT c.relname AS name,
               pg_get_expr(c.relpartbound, c.oid) AS bound
          FROM pg_class c
          JOIN pg_inherits i ON i.inhrelid = c.oid
          JOIN pg_class p ON p.oid = i.inhparent
          JOIN pg_namespace n ON n.oid = p.relnamespace
         WHERE n.nspname = 'audit' AND p.relname = 'audit_log'
         ORDER BY c.relname
    LOOP
        -- The bound is the authority on what the partition holds, rather than the name, which is
        -- only a label. It renders as
        --     FOR VALUES FROM ('2019-01-01 00:00:00+00') TO ('2019-02-01 00:00:00+00')
        -- so the two dates are pulled out by shape and taken in order. Matching a closing quote
        -- straight after the date read more tidily and matched nothing at all, because the bound
        -- renders a timestamptz with its time and zone — and a loop that finds no dates detaches
        -- nothing, which is the kind of failure that does not announce itself.
        SELECT max(CASE WHEN ord = 1 THEN m[1] END)::date,
               max(CASE WHEN ord = 2 THEN m[1] END)::date
          INTO part_start, part_end
          FROM regexp_matches(part.bound, '(\d{4}-\d{2}-\d{2})', 'g') WITH ORDINALITY AS t(m, ord);
        CONTINUE WHEN part_start IS NULL OR part_end IS NULL;
        -- The whole range must be expired. A partition straddling the cutoff keeps rows that are
        -- still inside retention, and detaching it would take them with it.
        CONTINUE WHEN part_end > cutoff;

        EXECUTE format('SELECT count(*) FROM audit.%I', part.name) INTO part_rows;
        EXECUTE format('ALTER TABLE audit.audit_log DETACH PARTITION audit.%I', part.name);

        INSERT INTO audit.audit_partition_archive (partition_name, range_start, range_end, row_count)
        VALUES (part.name, part_start, part_end, part_rows)
        ON CONFLICT ON CONSTRAINT pk_audit_partition_archive DO NOTHING;

        partition_name := part.name;
        range_start := part_start;
        range_end := part_end;
        row_count := part_rows;
        RETURN NEXT;
    END LOOP;
END;
$$;

COMMENT ON FUNCTION audit.detach_expired_partitions(INTEGER) IS
    'AT-BR-09: detaches partitions past the retention window and records them in '
    'audit_partition_archive. Never DROPs. The caller audits each returned row.';

-- A detached partition keeps its immutability trigger, because the trigger was created on the
-- partition itself rather than inherited (see V1). So rows that have left the parent are still
-- unmodifiable while they wait for the archival step, which is the whole reason DETACH is safe.

-- ----------------------------------------------- a system action has no actor
-- V1 allowed a null actor_id for LOGIN_FAILURE alone, which was right while every other event came
-- from a request someone had made. Retention does not: a scheduled job detaches a partition, and
-- there is nobody to name. The alternatives were both worse than widening the rule — inventing a
-- synthetic "system" user id would make the log claim a person did it, and skipping the event would
-- leave the one operation that removes evidence as the one operation with no record.
--
-- Added NOT VALID deliberately, and that is safe here rather than deferred work: the new predicate
-- is the old one with another OR branch, so it is strictly weaker and no existing row can violate
-- it. Validating would scan seven years of partitions to confirm something that is true by
-- construction, holding an ACCESS EXCLUSIVE lock while it did.
ALTER TABLE audit.audit_log DROP CONSTRAINT ck_audit_actor_present;

ALTER TABLE audit.audit_log
    ADD CONSTRAINT ck_audit_actor_present CHECK (
        actor_id IS NOT NULL
        -- A failed login has no authenticated actor to name.
        OR action = 'LOGIN_FAILURE'
        -- A scheduled operation on the log's own storage has no human actor at all. Narrowed to
        -- this service and this entity type so it cannot become a way for an application event to
        -- arrive anonymously.
        OR (service = 'audit-service' AND entity_type = 'AUDIT_PARTITION')) NOT VALID;
