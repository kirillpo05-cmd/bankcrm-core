-- Client360 / client-service — V2: clients.
--
-- SPEC.md §5.2.1 (enums), §5.2.2 (table, constraints, indexes), §4.7 (encryption),
-- §4.12 (why the age rule is a trigger and not a CHECK).

SET LOCAL search_path = client, public;

-- ----------------------------------------------------------------- enums
CREATE TYPE client.kyc_status      AS ENUM ('NOT_STARTED','PENDING','VERIFIED','REJECTED','EXPIRED');
CREATE TYPE client.client_status   AS ENUM ('ACTIVE','DORMANT','BLOCKED','CLOSED');
CREATE TYPE client.client_segment  AS ENUM ('RETAIL','PREMIUM','SME','PRIVATE');
CREATE TYPE client.risk_rating     AS ENUM ('LOW','MEDIUM','HIGH');
CREATE TYPE client.contact_channel AS ENUM ('PHONE','EMAIL','SMS','IN_APP','BRANCH');

-- ---------------------------------------------------------------- clients
CREATE TABLE client.clients (
    id                   UUID                   PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Immutable after creation: it is the join key to core banking, and changing
    -- it would silently re-point history (CP-BR-01).
    external_ref         VARCHAR(32)            NOT NULL,
    first_name           VARCHAR(100)           NOT NULL,
    last_name            VARCHAR(100)           NOT NULL,
    middle_name          VARCHAR(100),
    date_of_birth        DATE                   NOT NULL,

    -- Sensitive fields (§4.7): AES-256-GCM ciphertext + HMAC of the normalized
    -- value. The hash is what makes exact-match lookup work without decrypting
    -- the table; there is deliberately no prefix or LIKE search on these.
    email_enc            BYTEA,
    email_hash           BYTEA,
    phone_enc            BYTEA                  NOT NULL,
    phone_hash           BYTEA                  NOT NULL,
    tax_id_enc           BYTEA,
    tax_id_hash          BYTEA,
    address_enc          BYTEA,
    key_version          SMALLINT               NOT NULL DEFAULT 1,

    preferred_channel    client.contact_channel NOT NULL DEFAULT 'PHONE',
    segment              client.client_segment  NOT NULL DEFAULT 'RETAIL',
    status               client.client_status   NOT NULL DEFAULT 'ACTIVE',
    risk                 client.risk_rating     NOT NULL DEFAULT 'LOW',

    kyc_status           client.kyc_status      NOT NULL DEFAULT 'NOT_STARTED',
    kyc_verified_at      TIMESTAMPTZ,
    kyc_expires_at       TIMESTAMPTZ,
    kyc_rejection_reason TEXT,

    owner_manager_id     UUID                   NOT NULL,
    team_id              UUID                   NOT NULL,

    -- Display-only counters fed by the interaction/task event consumer.
    -- No business decision may read these (CP-BR-11).
    last_interaction_at  TIMESTAMPTZ,
    open_task_count      INTEGER                NOT NULL DEFAULT 0,

    merged_into_id       UUID,
    merged_at            TIMESTAMPTZ,

    version              INTEGER                NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ            NOT NULL DEFAULT now(),
    created_by           UUID                   NOT NULL,
    updated_at           TIMESTAMPTZ            NOT NULL DEFAULT now(),
    updated_by           UUID                   NOT NULL,
    deleted_at           TIMESTAMPTZ,
    deleted_by           UUID,
    deletion_reason      TEXT,

    CONSTRAINT fk_clients_owner FOREIGN KEY (owner_manager_id)
        REFERENCES client.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_clients_team FOREIGN KEY (team_id)
        REFERENCES client.teams (id) ON DELETE RESTRICT,
    CONSTRAINT fk_clients_merged FOREIGN KEY (merged_into_id)
        REFERENCES client.clients (id) ON DELETE RESTRICT,
    CONSTRAINT fk_clients_creator FOREIGN KEY (created_by)
        REFERENCES client.users (id) ON DELETE RESTRICT,

    -- Minimum age 18 (CP-BR-14) is enforced by trg_clients_validate below:
    -- CURRENT_DATE is STABLE, and PostgreSQL rejects non-IMMUTABLE functions
    -- in a CHECK constraint (§4.12).
    CONSTRAINT ck_clients_dob_sane
        CHECK (date_of_birth >= DATE '1900-01-01'),
    CONSTRAINT ck_clients_kyc_verified_fields
        CHECK (kyc_status <> 'VERIFIED'
               OR (kyc_verified_at IS NOT NULL AND kyc_expires_at IS NOT NULL)),
    CONSTRAINT ck_clients_kyc_rejected_reason
        CHECK (kyc_status <> 'REJECTED' OR kyc_rejection_reason IS NOT NULL),
    CONSTRAINT ck_clients_kyc_expiry_after_verify
        CHECK (kyc_expires_at IS NULL OR kyc_verified_at IS NULL
               OR kyc_expires_at > kyc_verified_at),
    CONSTRAINT ck_clients_email_pair
        CHECK ((email_enc IS NULL) = (email_hash IS NULL)),
    CONSTRAINT ck_clients_tax_pair
        CHECK ((tax_id_enc IS NULL) = (tax_id_hash IS NULL)),
    CONSTRAINT ck_clients_merge_pair
        CHECK ((merged_into_id IS NULL) = (merged_at IS NULL)),
    CONSTRAINT ck_clients_no_self_merge
        CHECK (merged_into_id IS NULL OR merged_into_id <> id),
    CONSTRAINT ck_clients_delete_reason
        CHECK (deleted_at IS NULL OR deletion_reason IS NOT NULL),
    CONSTRAINT ck_clients_open_task_count
        CHECK (open_task_count >= 0)
);

COMMENT ON TABLE client.clients IS
    'Unified customer card. Mirrors a core-banking CIF via external_ref.';

-- ------------------------------------------------- temporal validation (§4.12)
CREATE FUNCTION client.clients_validate() RETURNS trigger
LANGUAGE plpgsql AS $fn$
BEGIN
    -- CP-BR-14. SME clients use the registration date as date_of_birth; the
    -- rule holds for them too.
    IF NEW.date_of_birth > (CURRENT_DATE - INTERVAL '18 years')::date THEN
        RAISE EXCEPTION
            'ck_clients_adult: client must be at least 18 years old (date_of_birth=%)',
            NEW.date_of_birth
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$fn$;

CREATE TRIGGER trg_clients_validate
    BEFORE INSERT OR UPDATE OF date_of_birth ON client.clients
    FOR EACH ROW EXECUTE FUNCTION client.clients_validate();

-- ---------------------------------------------------------------- indexes
-- Identity lookup (CP-US-01). Partial on deleted_at so an identifier is freed
-- for reuse after erasure (CP-EC-02).
CREATE UNIQUE INDEX ux_clients_external_ref ON client.clients (external_ref)
    WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_clients_email_hash ON client.clients (email_hash)
    WHERE deleted_at IS NULL AND email_hash IS NOT NULL;
CREATE UNIQUE INDEX ux_clients_tax_hash ON client.clients (tax_id_hash)
    WHERE deleted_at IS NULL AND tax_id_hash IS NOT NULL;

-- Phone is intentionally NON-unique: family members share a landline (CP-BR-02,
-- CP-EC-03). Lookup returns every match for disambiguation.
CREATE INDEX ix_clients_phone_hash ON client.clients (phone_hash)
    WHERE deleted_at IS NULL;

-- Scope filters (RB-BR-02)
CREATE INDEX ix_clients_owner ON client.clients (owner_manager_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_clients_team  ON client.clients (team_id)          WHERE deleted_at IS NULL;

-- Nightly KYC expiry sweep (CP-BR-06)
CREATE INDEX ix_clients_kyc_expiry ON client.clients (kyc_expires_at)
    WHERE kyc_status = 'VERIFIED' AND deleted_at IS NULL;

-- Name search, typo-tolerant (CP-US-01 free-text branch)
CREATE INDEX ix_clients_name_trgm ON client.clients
    USING gin ((lower(first_name || ' ' || last_name)) gin_trgm_ops)
    WHERE deleted_at IS NULL;
