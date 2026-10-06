-- Client360 / audit-service — V3: what an export request needs.
--
-- SPEC.md §8.3 (AT-US-04), §4.6, §4.10.
--
-- Two things, and the first is a deliberate departure from how the other services do idempotency.
--
-- §8.3 puts `Idempotency-Key` on POST /audit/exports, and `common`'s IdempotencyService already
-- implements that header everywhere else. It cannot be used here: it stores the replayed response
-- body encrypted, so it needs FieldCipher, and wiring that into audit-service would hand this
-- service an AES data key. AT-BR-04 says sensitive values are never stored here, even encrypted —
-- there is nothing to decrypt and no reason for a key to be reachable from the machine whose job is
-- to keep evidence.
--
-- The encryption exists because a create response elsewhere echoes decrypted PII. An export
-- acknowledgement is { jobId, status, estimatedRows, expiresAt }: no PII, nothing to protect. So
-- the job row carries the key itself. One column and one index instead of a second table, and the
-- claim cannot drift from the job it protects, because it is the same row.

SET LOCAL search_path = audit, public;

-- ------------------------------------ bringing the table to its specced shape
-- V1 created audit_export_jobs before anything used it, and it drifted from §8.2.5 in four ways:
-- no `format`, no `checksum_sha256`, and `error` / `finished_at` where the spec says
-- `error_message` / `completed_at`. CLAUDE.md is explicit that a schema contradicting SPEC.md is a
-- bug in the schema, so this fixes the schema rather than teaching the code two sets of names.
--
-- Safe without the three-step dance db-migrations.md asks for on a populated table: nothing has
-- ever written here, because the endpoint that would is what this migration is for.
ALTER TABLE audit.audit_export_jobs RENAME COLUMN error TO error_message;
ALTER TABLE audit.audit_export_jobs RENAME COLUMN finished_at TO completed_at;

-- The default is for a job created by some future scheduled path with no format to state. Every
-- request names one, and ck_export_format is what keeps the set closed.
ALTER TABLE audit.audit_export_jobs
    ADD COLUMN format VARCHAR(8) NOT NULL DEFAULT 'CSV',
    -- §8.3: the checksum ties a file somebody is holding to a row in this table. Computed over the
    -- compressed bytes as they are written, so it is reproducible by whoever receives the file.
    ADD COLUMN checksum_sha256 BYTEA,
    ADD CONSTRAINT ck_export_format CHECK (format IN ('CSV', 'JSONL')),
    ADD CONSTRAINT ck_export_checksum_length CHECK (checksum_sha256 IS NULL OR length(checksum_sha256) = 32);

-- §8.2.5 has expires_at NOT NULL with a forward-moving window. V1 left it nullable, which would
-- let a job exist that never expires — and an export of the audit log that never expires is a copy
-- of the audit log sitting in a bucket forever.
ALTER TABLE audit.audit_export_jobs
    ALTER COLUMN expires_at SET NOT NULL,
    ADD CONSTRAINT ck_export_expiry CHECK (expires_at > requested_at);

-- ---------------------------------------------- idempotency on the job itself
ALTER TABLE audit.audit_export_jobs
    ADD COLUMN idempotency_key UUID;

COMMENT ON COLUMN audit.audit_export_jobs.idempotency_key IS
    'The client-generated key from the Idempotency-Key header (§4.6). A replay returns this same '
    'job rather than starting a second export of the same slice.';

-- Per requester, not global: two auditors generating the same UUID is vanishingly unlikely and
-- would be somebody else's export if it happened. Partial, so the column stays optional for a job
-- created by a future scheduled path that has no request and therefore no header.
CREATE UNIQUE INDEX ux_export_jobs_idempotency
    ON audit.audit_export_jobs (requested_by, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- ix_export_jobs_requester already serves "this requester's exports, newest first" — V1 created it.
--
-- Serves the sweeper that expires completed exports and deletes their objects.
CREATE INDEX ix_export_jobs_expiring ON audit.audit_export_jobs (expires_at)
    WHERE status = 'COMPLETED';

-- --------------------------------------------------------- rate_limit_counters
-- §8.3: 5 exports an hour. The same table and the same reasoning as client.rate_limit_counters —
-- counted in the database because a per-instance counter multiplies by the number of replicas, and
-- the window is floored from now() in PostgreSQL so two instances agree on where it begins
-- (CLAUDE.md rule 7).
CREATE TABLE audit.rate_limit_counters (
    subject      VARCHAR(128) NOT NULL,
    bucket       VARCHAR(64)  NOT NULL,
    window_start TIMESTAMPTZ  NOT NULL,
    hits         INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT pk_rate_limit_counters PRIMARY KEY (subject, bucket, window_start),
    CONSTRAINT ck_rate_limit_hits CHECK (hits > 0)
);

COMMENT ON TABLE audit.rate_limit_counters IS
    'Fixed-window request counters (§4.10). Not audit data: rows outside their window may be '
    'deleted, unlike anything in audit_log.';
