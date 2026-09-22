-- Client360 / interaction-service — V4: idempotency keys.
--
-- SPEC.md §4.6. The same table client-service got in its V5: `common`'s
-- IdempotencyService reads it unqualified and resolves it through search_path,
-- so every schema with POST creates carries its own copy. Without it the first
-- POST /clients/{clientId}/interactions fails on a missing relation.
--
-- Kept identical to the client-service copy on purpose. One shared definition
-- behaving differently per schema would be worse than two files that match.

SET LOCAL search_path = interaction, public;

CREATE TABLE interaction.idempotency_keys (
    user_id           UUID         NOT NULL,
    endpoint          VARCHAR(512) NOT NULL,
    idempotency_key   UUID         NOT NULL,
    payload_hash      BYTEA        NOT NULL,

    response_status   INTEGER,
    response_headers  JSONB,
    response_body_enc BYTEA,
    key_version       SMALLINT     NOT NULL DEFAULT 1,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,

    CONSTRAINT pk_idempotency_keys PRIMARY KEY (user_id, endpoint, idempotency_key),
    CONSTRAINT ck_idempotency_completed_pair
        CHECK ((completed_at IS NULL) = (response_status IS NULL)),
    CONSTRAINT ck_idempotency_status_range
        CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599)
);

-- No FK to client.users: these rows are a 24-hour retry cache, and this service
-- owns no user table at all in production (§3.1).
COMMENT ON TABLE interaction.idempotency_keys IS
    'At-most-once execution of POST creates (SPEC.md §4.6). Purged after 24 h.';

CREATE INDEX ix_idempotency_keys_created_at ON interaction.idempotency_keys (created_at);
