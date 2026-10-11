-- Billing (ADR-0025, ADR-0026, ADR-0027): customers, plans, prices, subscriptions, invoices.
-- Ids are prefixed UUIDv7 hex, like payments: they sort by creation time.

-- ---------------------------------------------------------------------------
-- Customers
-- ---------------------------------------------------------------------------
CREATE TABLE customers (
    id             TEXT        PRIMARY KEY,
    application_id UUID        NOT NULL REFERENCES applications (id),
    external_id    TEXT        NOT NULL,          -- the application's own user id
    email          TEXT,
    phone          TEXT,                          -- E.164
    pi_alias       TEXT,
    country        CHAR(2)     NOT NULL,
    name           TEXT,
    metadata       JSONB       NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (application_id, external_id)
);

CREATE INDEX customers_app_id_idx ON customers (application_id, id DESC);

-- ---------------------------------------------------------------------------
-- Catalog: plans and their prices. Nothing is deleted; prices are immutable.
-- ---------------------------------------------------------------------------
CREATE TABLE plans (
    id             TEXT        PRIMARY KEY,
    application_id UUID        NOT NULL REFERENCES applications (id),
    slug           TEXT        NOT NULL CHECK (slug ~ '^[a-z0-9][a-z0-9_-]{0,62}$'),
    name           TEXT        NOT NULL,
    active         BOOLEAN     NOT NULL DEFAULT true,
    features       JSONB       NOT NULL DEFAULT '{}',
    metadata       JSONB       NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (application_id, slug)
);

CREATE INDEX plans_app_id_idx ON plans (application_id, id DESC);

CREATE TABLE prices (
    id             TEXT        PRIMARY KEY,
    application_id UUID        NOT NULL REFERENCES applications (id),
    plan_id        TEXT        NOT NULL REFERENCES plans (id),
    currency       CHAR(3)     NOT NULL,
    amount         BIGINT      NOT NULL CHECK (amount > 0),
    interval       TEXT        NOT NULL CHECK (interval IN ('DAY', 'WEEK', 'MONTH', 'YEAR')),
    interval_count INT         NOT NULL CHECK (interval_count BETWEEN 1 AND 366),
    active         BOOLEAN     NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX prices_plan_idx ON prices (plan_id, id);

-- Prices never change: only `active` may be updated.
CREATE FUNCTION prices_immutable() RETURNS trigger AS $$
BEGIN
    IF (NEW.id, NEW.application_id, NEW.plan_id, NEW.currency, NEW.amount, NEW.interval, NEW.interval_count, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.application_id, OLD.plan_id, OLD.currency, OLD.amount, OLD.interval, OLD.interval_count, OLD.created_at) THEN
        RAISE EXCEPTION 'prices are immutable: create a new price and archive this one';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER prices_immutable BEFORE UPDATE ON prices FOR EACH ROW EXECUTE FUNCTION prices_immutable();
CREATE TRIGGER prices_no_delete BEFORE DELETE ON prices FOR EACH ROW EXECUTE FUNCTION forbid_change();

-- ---------------------------------------------------------------------------
-- Subscriptions (ADR-0026)
-- ---------------------------------------------------------------------------
CREATE TABLE subscriptions (
    id                   TEXT        PRIMARY KEY,
    application_id       UUID        NOT NULL REFERENCES applications (id),
    customer_id          TEXT        NOT NULL REFERENCES customers (id),
    price_id             TEXT        NOT NULL REFERENCES prices (id),
    status               TEXT        NOT NULL CHECK (status IN ('INCOMPLETE', 'ACTIVE', 'PAST_DUE', 'EXPIRED', 'CANCELED')),
    collection_method    TEXT        NOT NULL DEFAULT 'PREPAID_MANUAL' CHECK (collection_method IN ('PREPAID_MANUAL')),
    billing_anchor       INT         CHECK (billing_anchor BETWEEN 1 AND 31),
    current_period_start TIMESTAMPTZ,
    current_period_end   TIMESTAMPTZ,              -- null: never paid, or an open-ended comp
    grace_period_days    INT         NOT NULL CHECK (grace_period_days BETWEEN 0 AND 90),
    cancel_at_period_end BOOLEAN     NOT NULL DEFAULT false,
    pending_price_id     TEXT        REFERENCES prices (id),
    canceled_at          TIMESTAMPTZ,
    comp                 BOOLEAN     NOT NULL DEFAULT false,
    reminded_period_end  TIMESTAMPTZ,              -- renewal_upcoming sent for this period end
    metadata             JSONB       NOT NULL DEFAULT '{}',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (current_period_end IS NULL OR current_period_start IS NULL OR current_period_end > current_period_start)
);

CREATE INDEX subscriptions_app_id_idx ON subscriptions (application_id, id DESC);
CREATE INDEX subscriptions_customer_idx ON subscriptions (customer_id, id DESC);
-- The billing sweep looks for subscriptions whose period (or grace) has ended.
CREATE INDEX subscriptions_due_idx ON subscriptions (current_period_end)
    WHERE status IN ('ACTIVE', 'PAST_DUE') AND current_period_end IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Invoices (ADR-0027): billing records, numbered gap-free per application.
-- ---------------------------------------------------------------------------
CREATE TABLE invoice_counters (
    application_id UUID   PRIMARY KEY REFERENCES applications (id),
    year           INT    NOT NULL,
    last_number    BIGINT NOT NULL
);

CREATE TABLE invoices (
    id              TEXT        PRIMARY KEY,
    application_id  UUID        NOT NULL REFERENCES applications (id),
    number          TEXT        NOT NULL,
    customer_id     TEXT        NOT NULL REFERENCES customers (id),
    subscription_id TEXT        NOT NULL REFERENCES subscriptions (id),
    price_id        TEXT        NOT NULL REFERENCES prices (id),
    kind            TEXT        NOT NULL CHECK (kind IN ('FIRST', 'RENEWAL', 'UPGRADE')),
    status          TEXT        NOT NULL CHECK (status IN ('OPEN', 'PAID', 'VOID', 'UNCOLLECTIBLE')),
    amount_due      BIGINT      NOT NULL CHECK (amount_due > 0),
    currency        CHAR(3)     NOT NULL,
    period_start    TIMESTAMPTZ,                  -- set when paid (stacking decides it)
    period_end      TIMESTAMPTZ,
    payment_id      TEXT        REFERENCES payments (id),
    receipt_token   TEXT        NOT NULL,
    paid_at         TIMESTAMPTZ,
    voided_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (application_id, number)
);

CREATE INDEX invoices_app_id_idx ON invoices (application_id, id DESC);
CREATE INDEX invoices_subscription_idx ON invoices (subscription_id, id DESC);
-- At most one open invoice per subscription: concurrent renewals cannot create two.
CREATE UNIQUE INDEX invoices_one_open_per_subscription ON invoices (subscription_id) WHERE status = 'OPEN';

CREATE TABLE invoice_lines (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_id   TEXT        NOT NULL REFERENCES invoices (id),
    description  TEXT        NOT NULL,
    price_id     TEXT        NOT NULL REFERENCES prices (id),
    amount       BIGINT      NOT NULL CHECK (amount > 0)
);

CREATE INDEX invoice_lines_invoice_idx ON invoice_lines (invoice_id, id);

-- ---------------------------------------------------------------------------
-- Payments may belong to a customer, a subscription and an invoice (additive).
-- ---------------------------------------------------------------------------
ALTER TABLE payments ADD COLUMN customer_id TEXT REFERENCES customers (id);
ALTER TABLE payments ADD COLUMN subscription_id TEXT REFERENCES subscriptions (id);
ALTER TABLE payments ADD COLUMN invoice_id TEXT REFERENCES invoices (id);
CREATE INDEX payments_invoice_idx ON payments (invoice_id) WHERE invoice_id IS NOT NULL;

-- Status history covers the new resources.
ALTER TABLE status_events DROP CONSTRAINT status_events_resource_type_check;
ALTER TABLE status_events ADD CONSTRAINT status_events_resource_type_check
    CHECK (resource_type IN ('payment', 'refund', 'payout', 'subscription', 'invoice'));
