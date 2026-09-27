-- Client360 / client-service — V7: rate limit counters.
--
-- SPEC.md §4.10. The limits were specified from the start and §5.3 already promises
-- `429 RATE_LIMIT_EXCEEDED` on `GET /clients/lookup` as a PII-enumeration guard, but nothing
-- counted anything, so the guard did not exist. §4.10 gained this DDL in the same change.
--
-- Why a table and not an in-process counter: the limit that matters here is the lookup guard,
-- and a per-instance counter multiplies by the number of replicas — two instances behind a load
-- balancer would let 120 probes a minute through a limit of 60, while reporting that the limit
-- was enforced. The stack has no Redis (CLAUDE.md → Stack), so the shared store is the database
-- the service already has a connection to. Lookup is a human typing into a search box; one
-- upsert per request is affordable at that rate.
--
-- Fixed windows, not sliding. A fixed window allows a burst of up to 2× the limit across a
-- boundary — 60 in the last second of one minute and 60 in the first second of the next. That is
-- a known and accepted weakness: it bounds sustained enumeration, which is the threat, and a
-- sliding window would mean storing a timestamp per request rather than a count per window.
-- Stated here so the next person reads it as a trade-off rather than an oversight.
--
-- interaction-service needs its own copy in schema `interaction` when the §4.10 attachment limit
-- lands — the same arrangement as idempotency_keys, for the same reason (V5 header).

SET LOCAL search_path = client, public;

CREATE TABLE client.rate_limit_counters (
    -- Whoever the limit is per: a user id today; §4.10 also limits login by IP, which arrives
    -- with auth in v2 and is why this is not a UUID column.
    subject      VARCHAR(128) NOT NULL,
    -- Which limit, e.g. 'clients.lookup'. Named rather than derived from the endpoint, because
    -- one endpoint may carry several limits and a limit may cover several endpoints.
    bucket       VARCHAR(64)  NOT NULL,
    -- Start of the fixed window, always floored from now() in PostgreSQL and never the JVM
    -- clock (CLAUDE.md rule 7): two instances must agree on where a window begins.
    window_start TIMESTAMPTZ  NOT NULL,
    hits         INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT pk_rate_limit_counters PRIMARY KEY (subject, bucket, window_start),
    CONSTRAINT ck_rate_limit_hits CHECK (hits > 0)
);

-- No foreign key to users, deliberately, exactly as V5 argues for idempotency_keys: throttling
-- is infrastructure and must keep working while a user row is being deactivated or replaced.

COMMENT ON TABLE client.rate_limit_counters IS
    'Fixed-window request counters for SPEC.md §4.10. Not audit data: rows outside their '
    'window are deleted by RateLimiter.purgeExpired().';

-- Serves the purge, which is the only query that does not know the primary key.
CREATE INDEX ix_rate_limit_window_start ON client.rate_limit_counters (window_start);
