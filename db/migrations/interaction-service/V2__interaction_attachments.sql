-- Client360 / interaction-service — V2: interaction attachments.
--
-- SPEC.md §6.2.3, IL-US-06.

SET LOCAL search_path = interaction, public;

CREATE TYPE interaction.scan_status AS ENUM ('PENDING','CLEAN','INFECTED','FAILED');

CREATE TABLE interaction.interaction_attachments (
    id              UUID                    PRIMARY KEY DEFAULT gen_random_uuid(),
    interaction_id  UUID                    NOT NULL,
    filename        VARCHAR(255)            NOT NULL,
    content_type    VARCHAR(128)            NOT NULL,
    size_bytes      BIGINT                  NOT NULL,
    -- Object-store key, never a public URL. Downloads go through a 60-second
    -- pre-signed link issued by the API, so access stays auditable.
    storage_key     VARCHAR(512)            NOT NULL,
    checksum_sha256 BYTEA                   NOT NULL,
    scan            interaction.scan_status NOT NULL DEFAULT 'PENDING',
    scanned_at      TIMESTAMPTZ,
    uploaded_by     UUID                    NOT NULL,
    uploaded_at     TIMESTAMPTZ             NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT uq_attachments_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_att_interaction FOREIGN KEY (interaction_id)
        REFERENCES interaction.interactions (id) ON DELETE CASCADE,
    CONSTRAINT fk_att_uploader FOREIGN KEY (uploaded_by)
        REFERENCES client.users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_att_size CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    CONSTRAINT ck_att_scanned_pair CHECK ((scan = 'PENDING') = (scanned_at IS NULL)),
    CONSTRAINT ck_att_type CHECK (content_type IN (
        'application/pdf','image/jpeg','image/png','image/tiff',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'))
);

COMMENT ON TABLE interaction.interaction_attachments IS
    'Max 5 attachments per interaction is enforced in the service layer inside '
    'the upload transaction (SELECT ... FOR UPDATE on the parent row), not by a '
    'constraint: a per-parent count cannot be expressed as a CHECK (§6.2.3).';

CREATE INDEX ix_att_interaction ON interaction.interaction_attachments (interaction_id)
    WHERE deleted_at IS NULL;
