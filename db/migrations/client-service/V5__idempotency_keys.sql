-- Client360 / client-service — V5: idempotency keys.
--
-- SPEC.md §4.6. The behaviour was specified from the start and `common`'s
-- IdempotencyService was written against this table, but no migration created
-- it: every POST would have failed on `relation "idempotency_keys" does not
-- exist`. §4.6 gained the DDL in the same change that added this file.
--
-- interaction-service needs its own copy in schema `interaction` when its
-- creates land — the service reads the table unqualified and resolves it
-- through search_path, so each schema carries one.

SET LOCAL search_path = client, public;

CREATE TABLE client.idempotency_keys (
    user_id           UUID         NOT NULL,
    -- "POST /api/v1/clients" — method and path, never the query string
    -- (RequestMetadata.endpoint()).
    endpoint          VARCHAR(512) NOT NULL,
    idempotency_key   UUID         NOT NULL,
    -- HMAC of the canonical request body, not a bare digest: the body holds PII
    -- and a peppered hash cannot be confirmed by guessing candidate inputs.
    payload_hash      BYTEA        NOT NULL,

    -- The stored response, replayed verbatim on a duplicate. Encrypted because a
    -- create response echoes decrypted PII, and this table must not become a
    -- plaintext copy of it (CLAUDE.md rule 8).
    response_status   INTEGER,
    response_headers  JSONB,
    response_body_enc BYTEA,
    key_version       SMALLINT     NOT NULL DEFAULT 1,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,

    -- The claim is taken by INSERT ... ON CONFLICT on exactly this triple, so it
    -- is the primary key rather than a surrogate id plus a redundant unique index.
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (user_id, endpoint, idempotency_key),
    CONSTRAINT ck_idempotency_completed_pair
        CHECK ((completed_at IS NULL) = (response_status IS NULL)),
    CONSTRAINT ck_idempotency_status_range
        CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599)
);

-- No FK to client.users. Every other user reference in this schema is RESTRICT
-- because it records something that happened; these rows are a 24-hour retry
-- cache that records nothing. A FK here would also let a transient row block the
-- deactivation flow of RB-BR-07, which is the opposite of what CP-EC-06 wants.
COMMENT ON TABLE client.idempotency_keys IS
    'At-most-once execution of POST creates (SPEC.md §4.6). Purged after 24 h; '
    'not audit data and safe to delete.';

-- IdempotencyService.purgeExpired(): created_at < now() - 24h, 1000 rows a batch.
CREATE INDEX ix_idempotency_keys_created_at ON client.idempotency_keys (created_at);
