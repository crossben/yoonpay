-- ---------------------------------------------------------------------------
-- Inbound provider webhooks: stored raw BEFORE processing, processed by a worker.
-- ---------------------------------------------------------------------------
CREATE TABLE inbound_webhooks (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id  UUID        NOT NULL REFERENCES applications (id),
    provider        TEXT        NOT NULL,
    headers         TEXT        NOT NULL,          -- JSON object of header name -> values
    body            BYTEA       NOT NULL,          -- raw bytes: signatures are computed over them
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until    TIMESTAMPTZ,
    processed_at    TIMESTAMPTZ,
    result          TEXT                           -- e.g. applied, ignored, invalid_signature, not_found
);

CREATE INDEX inbound_webhooks_unprocessed_idx ON inbound_webhooks (id) WHERE processed_at IS NULL;
CREATE INDEX inbound_webhooks_received_idx ON inbound_webhooks (received_at);

-- ---------------------------------------------------------------------------
-- Outbound events (transactional outbox): written in the same transaction as the
-- state change, delivered to the application's endpoint by a worker.
-- ---------------------------------------------------------------------------
CREATE TABLE outbound_events (
    id               TEXT        PRIMARY KEY,      -- evt_<uuidv7>
    application_id   UUID        NOT NULL REFERENCES applications (id),
    type             TEXT        NOT NULL,         -- payment.succeeded, payout.paid, ...
    resource_type    TEXT        NOT NULL,
    resource_id      TEXT        NOT NULL,
    payload          TEXT        NOT NULL,         -- the exact JSON body delivered
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivery_status  TEXT        NOT NULL CHECK (delivery_status IN ('PENDING', 'DELIVERED', 'DEAD', 'NO_ENDPOINT')),
    attempts         INT         NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until     TIMESTAMPTZ,
    last_error       TEXT,
    last_status_code INT,
    delivered_at     TIMESTAMPTZ
);

CREATE INDEX outbound_events_due_idx ON outbound_events (next_attempt_at) WHERE delivery_status = 'PENDING';
CREATE INDEX outbound_events_app_idx ON outbound_events (application_id, id DESC);
CREATE INDEX outbound_events_dead_idx ON outbound_events (application_id, id DESC) WHERE delivery_status = 'DEAD';

-- ---------------------------------------------------------------------------
-- Sweep bookkeeping.
-- ---------------------------------------------------------------------------
ALTER TABLE payments ADD COLUMN status_checks INT NOT NULL DEFAULT 0;          -- status queries with no answer
ALTER TABLE payments ADD COLUMN late_check_done BOOLEAN NOT NULL DEFAULT false; -- final-state re-check done
ALTER TABLE payouts  ADD COLUMN status_checks INT NOT NULL DEFAULT 0;
ALTER TABLE refunds  ADD COLUMN status_checks INT NOT NULL DEFAULT 0;

CREATE INDEX payments_pending_idx ON payments (updated_at) WHERE status IN ('CREATED', 'PENDING');
CREATE INDEX payouts_open_idx ON payouts (updated_at) WHERE status IN ('PROCESSING', 'UNKNOWN');
CREATE INDEX refunds_open_idx ON refunds (updated_at) WHERE status IN ('PENDING', 'UNKNOWN');

-- ShedLock: a scheduled sweep runs on one node at a time.
CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
