-- Client360 / client-service — V6: KYC note.
--
-- SPEC.md §5.3 `POST /clients/{id}/kyc` accepts a `note` ("Passport + utility bill
-- on file"), but §5.2.2 gave it no column, so the only honest options were to drop
-- what the approver typed or to store it. A KYC decision without its evidence note
-- is a weaker compliance record, so it is stored. §5.2.2 gained the column in the
-- same change.
--
-- Encrypted, because it is free text written about identity documents and will
-- sooner or later hold a passport or ID number. There is no `_hash` sibling: rule 8
-- pairs a hash with a column only where exact-match lookup is needed, and nobody
-- searches clients by the wording of a KYC note — address_enc is the precedent.
-- It shares the row's key_version like every other ciphertext column.
--
-- Lock and duration: a nullable column with no default is a catalog-only change in
-- PostgreSQL 11+. No table rewrite; ACCESS EXCLUSIVE is held for milliseconds.

SET LOCAL search_path = client, public;

ALTER TABLE client.clients ADD COLUMN kyc_note_enc BYTEA;

COMMENT ON COLUMN client.clients.kyc_note_enc IS
    'AES-256-GCM. Evidence note recorded with the latest KYC decision (SPEC §5.3).';
