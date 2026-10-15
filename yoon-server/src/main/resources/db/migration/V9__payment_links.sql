-- Payment links (ADR-0030): a shareable entry point that creates an ordinary hosted payment.

CREATE TABLE payment_links (
    id             TEXT        PRIMARY KEY,
    application_id UUID        NOT NULL REFERENCES applications (id),
    code           TEXT        NOT NULL UNIQUE CHECK (code ~ '^[A-Za-z0-9_-]{22}$'),  -- in the public URL
    amount         BIGINT      CHECK (amount > 0),                -- null: the customer chooses
    min_amount     BIGINT      CHECK (min_amount > 0),
    max_amount     BIGINT      CHECK (max_amount > 0),
    currency       CHAR(3)     NOT NULL,
    country        CHAR(2)     NOT NULL,
    description    TEXT        NOT NULL CHECK (length(description) BETWEEN 1 AND 500),
    method         TEXT,                                          -- null: any method
    max_payments   INT         CHECK (max_payments > 0),          -- null: reusable
    expires_at     TIMESTAMPTZ,
    return_url     TEXT,
    active         BOOLEAN     NOT NULL DEFAULT true,
    metadata       JSONB       NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Either a fixed amount, or bounds for the customer's amount (both optional).
    CHECK (amount IS NULL OR (min_amount IS NULL AND max_amount IS NULL)),
    CHECK (min_amount IS NULL OR max_amount IS NULL OR min_amount <= max_amount)
);

CREATE INDEX payment_links_app_id_idx ON payment_links (application_id, id DESC);

-- The amount, currency and country never change: create a new link instead.
CREATE FUNCTION payment_links_immutable() RETURNS trigger AS $$
BEGIN
    IF (NEW.id, NEW.application_id, NEW.code, NEW.amount, NEW.min_amount, NEW.max_amount, NEW.currency,
        NEW.country, NEW.method, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.application_id, OLD.code, OLD.amount, OLD.min_amount, OLD.max_amount, OLD.currency,
        OLD.country, OLD.method, OLD.created_at) THEN
        RAISE EXCEPTION 'payment link amounts never change: create a new link';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER payment_links_immutable BEFORE UPDATE ON payment_links FOR EACH ROW EXECUTE FUNCTION payment_links_immutable();
CREATE TRIGGER payment_links_no_delete BEFORE DELETE ON payment_links FOR EACH ROW EXECUTE FUNCTION forbid_change();

ALTER TABLE payments ADD COLUMN payment_link_id TEXT REFERENCES payment_links (id);
CREATE INDEX payments_payment_link_idx ON payments (payment_link_id) WHERE payment_link_id IS NOT NULL;
