-- Customer portal (ADR-0031): short-lived sessions that open one customer's portal.

CREATE TABLE portal_sessions (
    id             TEXT        PRIMARY KEY,
    application_id UUID        NOT NULL REFERENCES applications (id),
    customer_id    TEXT        NOT NULL REFERENCES customers (id),
    token_hash     TEXT        NOT NULL,                 -- SHA-256 of the URL token; the token is never stored
    allow_cancel   BOOLEAN     NOT NULL DEFAULT false,
    change_prices  TEXT[],                               -- prices the customer may switch to; null: none
    allow_coupons  BOOLEAN     NOT NULL DEFAULT false,
    return_url     TEXT,
    expires_at     TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX portal_sessions_customer_idx ON portal_sessions (customer_id, id DESC);

-- Actions taken by a customer from the portal are recorded as such.
ALTER TABLE status_events DROP CONSTRAINT status_events_cause_check;
ALTER TABLE status_events ADD CONSTRAINT status_events_cause_check
    CHECK (cause IN ('api', 'provider_call', 'webhook', 'sweep', 'admin', 'portal'));
