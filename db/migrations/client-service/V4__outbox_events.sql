-- Client360 / client-service — V4: transactional outbox.
--
-- SPEC.md §3.4. Ships in the MVP even though audit-service arrives in v2
-- (§11.1): events accumulate here and publish to Kafka from day one, so v2 only
-- adds the consumer. Retrofitting an outbox later means touching every mutation
-- again.
--
-- The rule this table exists to enforce: a business change and its event row
-- commit in the SAME transaction. Never call KafkaTemplate.send() from a service
-- method — the relay poller publishes and stamps published_at.

SET LOCAL search_path = client, public;

CREATE TABLE client.outbox_events (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       UUID        NOT NULL,
    aggregate_type VARCHAR(48) NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    topic          VARCHAR(64) NOT NULL,
    partition_key  VARCHAR(64) NOT NULL,
    -- Sensitive values are already '***MASKED***' here (AR-01). Plaintext PII
    -- must never reach this table, because from here it reaches the broker.
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       SMALLINT    NOT NULL DEFAULT 0,
    last_error     TEXT,

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_attempts CHECK (attempts BETWEEN 0 AND 100)
);

COMMENT ON TABLE client.outbox_events IS
    'Transactional outbox. Guarantees "audited or not committed" without a '
    'distributed transaction (SPEC.md §3.4, risk mitigation for PROJECT_IDEA §9).';

-- Relay poller: FOR UPDATE SKIP LOCKED, batch 100, ordered by created_at.
-- Partial index keeps recovery fast even with a large backlog (AT-EC-06).
CREATE INDEX ix_outbox_unpublished ON client.outbox_events (created_at)
    WHERE published_at IS NULL;
