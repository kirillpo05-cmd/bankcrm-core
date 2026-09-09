-- Client360 / client-service — V3: client products.
--
-- SPEC.md §5.2.3. Products are a READ-ONLY projection of core banking — this
-- service never opens an account or moves a balance. That is why ON DELETE
-- CASCADE is safe here and RESTRICT is used for interactions and tasks: losing a
-- projection on client delete loses nothing authoritative (§5.2.3).

SET LOCAL search_path = client, public;

CREATE TYPE client.product_type AS ENUM (
    'CURRENT_ACCOUNT','SAVINGS_ACCOUNT','DEBIT_CARD','CREDIT_CARD',
    'MORTGAGE','CONSUMER_LOAN','TERM_DEPOSIT','INVESTMENT_ACCOUNT','INSURANCE');
CREATE TYPE client.product_status AS ENUM ('PENDING','ACTIVE','SUSPENDED','CLOSED');

CREATE TABLE client.client_products (
    id                  UUID                  PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id           UUID                  NOT NULL,
    product_type        client.product_type   NOT NULL,
    external_product_id VARCHAR(64)           NOT NULL,
    masked_number       VARCHAR(32),                       -- '**** **** **** 4417'
    status              client.product_status NOT NULL,
    currency            CHAR(3)               NOT NULL,
    -- Minor units, integer. No floating point for money, anywhere (§4.1).
    balance_minor       BIGINT,
    opened_on           DATE                  NOT NULL,
    closed_on           DATE,
    synced_at           TIMESTAMPTZ           NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ           NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ           NOT NULL DEFAULT now(),
    version             INTEGER               NOT NULL DEFAULT 0,

    CONSTRAINT fk_products_client FOREIGN KEY (client_id)
        REFERENCES client.clients (id) ON DELETE CASCADE,
    CONSTRAINT uq_products_external UNIQUE (client_id, external_product_id),
    CONSTRAINT ck_products_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_products_close_order CHECK (closed_on IS NULL OR closed_on >= opened_on),
    CONSTRAINT ck_products_closed_has_date
        CHECK (status <> 'CLOSED' OR closed_on IS NOT NULL),
    CONSTRAINT ck_products_open_has_no_close
        CHECK (status = 'CLOSED' OR closed_on IS NULL)
    -- "opened_on not in the future" is trg_client_products_validate below:
    -- CURRENT_DATE is STABLE and cannot appear in a CHECK (§4.12).
);

COMMENT ON TABLE client.client_products IS
    'Read-only projection of core banking. Never the system of record.';

CREATE FUNCTION client.client_products_validate() RETURNS trigger
LANGUAGE plpgsql AS $fn$
BEGIN
    IF NEW.opened_on > CURRENT_DATE THEN
        RAISE EXCEPTION
            'ck_products_opened_not_future: opened_on may not be in the future (opened_on=%)',
            NEW.opened_on
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$fn$;

CREATE TRIGGER trg_client_products_validate
    BEFORE INSERT OR UPDATE OF opened_on ON client.client_products
    FOR EACH ROW EXECUTE FUNCTION client.client_products_validate();

-- Products panel on the client card (CP-US-07)
CREATE INDEX ix_products_client_status ON client.client_products (client_id, status);
