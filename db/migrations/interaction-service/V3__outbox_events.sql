-- Client360 / interaction-service — V3: transactional outbox.
--
-- SPEC.md §3.4. Same shape as client.outbox_events, deliberately duplicated
-- rather than shared: each service owns its own tables, and a shared outbox
-- would couple two independently deployable services at the storage layer.

SET LOCAL search_path = interaction, public;

CREATE TABLE interaction.outbox_events (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       UUID        NOT NULL,
    aggregate_type VARCHAR(48) NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    topic          VARCHAR(64) NOT NULL,
    partition_key  VARCHAR(64) NOT NULL,
    -- subject and body are masked here per AR-01.
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       SMALLINT    NOT NULL DEFAULT 0,
    last_error     TEXT,

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_attempts CHECK (attempts BETWEEN 0 AND 100)
);

CREATE INDEX ix_outbox_unpublished ON interaction.outbox_events (created_at)
    WHERE published_at IS NULL;
