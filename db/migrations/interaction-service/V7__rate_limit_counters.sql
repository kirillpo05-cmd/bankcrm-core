-- Client360 / interaction-service — V7: rate limit counters.
--
-- SPEC.md §4.10: "Attachment upload per user — 20 per hour, 10 MB each". The size half was
-- enforced from the start by ck_att_size and the service; the rate half counted nothing.
--
-- The same table as client-service's V7, in this service's own schema. Not shared and not a
-- cross-schema read: each service owns its schema (§3.1), and the arrangement is the one
-- idempotency_keys already uses for the same reason.
--
-- Why a table and not an in-process counter: a per-instance counter multiplies by the number of
-- replicas, so two instances would pass 40 uploads an hour through a limit of 20 while reporting
-- the limit as enforced. The stack has no Redis (CLAUDE.md → Stack), so the shared store is the
-- database the service already has a connection to. An upload is a human attaching a document;
-- one upsert per request is nothing beside the 10 MB it accompanies.
--
-- Fixed windows, not sliding: a burst of up to 2× the limit is possible across a boundary — 20
-- uploads in the last minute of one hour and 20 in the first minute of the next. Accepted, because
-- what this limit protects is the bucket's growth over time rather than any single moment, and a
-- sliding window would mean storing a timestamp per upload instead of a count per window.
--
SET LOCAL search_path = interaction, public;

CREATE TABLE interaction.rate_limit_counters (
    -- Whoever the limit is per: a user id here. VARCHAR rather than UUID to match the shape
    -- client-service uses, where §4.10 also limits login by IP.
    subject      VARCHAR(128) NOT NULL,
    -- Which limit, e.g. 'interactions.attachment_upload'. Named rather than derived from the
    -- endpoint, because one endpoint may carry several limits and a limit several endpoints.
    bucket       VARCHAR(64)  NOT NULL,
    -- Start of the fixed window, always floored from now() in PostgreSQL and never the JVM
    -- clock (CLAUDE.md rule 7): two instances must agree on where a window begins.
    window_start TIMESTAMPTZ  NOT NULL,
    hits         INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT pk_rate_limit_counters PRIMARY KEY (subject, bucket, window_start),
    CONSTRAINT ck_rate_limit_hits CHECK (hits > 0)
);

-- No foreign key anywhere. This service holds no user table at all (§3.1), and throttling is
-- infrastructure that must keep working regardless of what client-service knows about a user.

COMMENT ON TABLE interaction.rate_limit_counters IS
    'Fixed-window request counters for SPEC.md §4.10. Not audit data: rows outside their '
    'window are deleted by RateLimiter.purgeExpired().';

-- Serves the purge, which is the only query that does not know the primary key.
CREATE INDEX ix_rate_limit_window_start ON interaction.rate_limit_counters (window_start);
