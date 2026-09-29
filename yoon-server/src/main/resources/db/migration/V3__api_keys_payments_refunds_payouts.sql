-- API keys: only a SHA-256 hash is stored; the key is shown once, at creation.
CREATE TABLE api_keys (
    id             UUID        PRIMARY KEY,
    application_id UUID        NOT NULL REFERENCES applications (id),
    prefix         TEXT        NOT NULL,          -- first characters, to recognise a key in lists
    key_hash       TEXT        NOT NULL UNIQUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at     TIMESTAMPTZ
);

CREATE INDEX api_keys_application_idx ON api_keys (application_id);

-- ---------------------------------------------------------------------------
-- Payments (collections). Ids are prefixed UUIDv7 hex: they sort by creation time.
-- ---------------------------------------------------------------------------
CREATE TABLE payments (
    id                 TEXT        PRIMARY KEY,
    application_id     UUID        NOT NULL REFERENCES applications (id),
    status             TEXT        NOT NULL CHECK (status IN ('CREATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'EXPIRED')),
    amount             BIGINT      NOT NULL CHECK (amount > 0),
    currency           CHAR(3)     NOT NULL,
    country            CHAR(2)     NOT NULL,
    method             TEXT        NOT NULL,
    reference          TEXT,                       -- the application's own order id
    description        TEXT,
    customer_phone     TEXT,                       -- E.164
    return_url         TEXT,
    provider           TEXT,
    provider_reference TEXT,
    checkout_url       TEXT,
    instructions       TEXT,
    routing_reason     TEXT,
    failure_code       TEXT,
    failure_message    TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX payments_app_id_idx ON payments (application_id, id DESC);
CREATE INDEX payments_app_reference_idx ON payments (application_id, reference);
CREATE INDEX payments_app_status_idx ON payments (application_id, status, id DESC);
CREATE INDEX payments_provider_reference_idx ON payments (provider, provider_reference);

-- Every call to a provider for a payment. More than one only after a definite rejection.
CREATE TABLE payment_attempts (
    id                 TEXT        PRIMARY KEY,    -- sent to the provider as Yoon's reference
    payment_id         TEXT        NOT NULL REFERENCES payments (id),
    provider           TEXT        NOT NULL,
    outcome            TEXT        CHECK (outcome IN ('ACCEPTED', 'REJECTED', 'UNKNOWN')),
    provider_reference TEXT,
    outcome_code       TEXT,
    outcome_message    TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ
);

CREATE INDEX payment_attempts_payment_idx ON payment_attempts (payment_id);

-- ---------------------------------------------------------------------------
-- Refunds: many per payment, always on the payment's provider.
-- ---------------------------------------------------------------------------
CREATE TABLE refunds (
    id                 TEXT        PRIMARY KEY,
    application_id     UUID        NOT NULL REFERENCES applications (id),
    payment_id         TEXT        NOT NULL REFERENCES payments (id),
    status             TEXT        NOT NULL CHECK (status IN ('CREATED', 'PENDING', 'UNKNOWN', 'REFUNDED', 'FAILED')),
    amount             BIGINT      NOT NULL CHECK (amount > 0),
    currency           CHAR(3)     NOT NULL,
    reason             TEXT,
    provider           TEXT        NOT NULL,
    provider_reference TEXT,
    failure_code       TEXT,
    failure_message    TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX refunds_app_id_idx ON refunds (application_id, id DESC);
CREATE INDEX refunds_payment_idx ON refunds (payment_id);

-- ---------------------------------------------------------------------------
-- Payouts: never retried, never failed over.
-- ---------------------------------------------------------------------------
CREATE TABLE payouts (
    id                 TEXT        PRIMARY KEY,
    application_id     UUID        NOT NULL REFERENCES applications (id),
    status             TEXT        NOT NULL CHECK (status IN ('CREATED', 'PROCESSING', 'UNKNOWN', 'PAID', 'FAILED')),
    amount             BIGINT      NOT NULL CHECK (amount > 0),
    currency           CHAR(3)     NOT NULL,
    country            CHAR(2)     NOT NULL,
    method             TEXT        NOT NULL,
    reference          TEXT,
    recipient_phone    TEXT        NOT NULL,
    provider           TEXT        NOT NULL,
    provider_reference TEXT,
    routing_reason     TEXT,
    needs_review       BOOLEAN     NOT NULL DEFAULT false,
    failure_code       TEXT,
    failure_message    TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX payouts_app_id_idx ON payouts (application_id, id DESC);
CREATE INDEX payouts_app_reference_idx ON payouts (application_id, reference);

-- ---------------------------------------------------------------------------
-- Status history for payments, refunds and payouts. Append-only: the status columns
-- above are projections of this log.
-- ---------------------------------------------------------------------------
CREATE TABLE status_events (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id UUID        NOT NULL REFERENCES applications (id),
    resource_type  TEXT        NOT NULL CHECK (resource_type IN ('payment', 'refund', 'payout')),
    resource_id    TEXT        NOT NULL,
    from_status    TEXT,
    to_status      TEXT        NOT NULL,
    decision       TEXT        NOT NULL CHECK (decision IN ('APPLY', 'IGNORE')),
    cause          TEXT        NOT NULL CHECK (cause IN ('api', 'provider_call', 'webhook', 'sweep', 'admin')),
    raw_status     TEXT,
    detail         TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX status_events_resource_idx ON status_events (resource_type, resource_id, id);

CREATE FUNCTION forbid_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only (%)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER status_events_append_only
    BEFORE UPDATE OR DELETE ON status_events
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
