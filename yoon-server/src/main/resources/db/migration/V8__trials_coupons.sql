-- Trials (ADR-0028) and coupons (ADR-0029). Additive: existing rows keep their meaning.

-- ---------------------------------------------------------------------------
-- Trials
-- ---------------------------------------------------------------------------
ALTER TABLE plans ADD COLUMN trial_days INT NOT NULL DEFAULT 0 CHECK (trial_days BETWEEN 0 AND 90);

ALTER TABLE subscriptions DROP CONSTRAINT subscriptions_status_check;
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_status_check
    CHECK (status IN ('INCOMPLETE', 'ACTIVE', 'PAST_DUE', 'EXPIRED', 'CANCELED', 'TRIALING'));
ALTER TABLE subscriptions ADD COLUMN trial_end TIMESTAMPTZ;   -- set once, when created with a trial

-- One trial per customer and plan is checked by looking for an earlier trial.
CREATE INDEX subscriptions_trials_idx ON subscriptions (customer_id, price_id) WHERE trial_end IS NOT NULL;
-- The sweep also ends trials.
DROP INDEX subscriptions_due_idx;
CREATE INDEX subscriptions_due_idx ON subscriptions (current_period_end)
    WHERE status IN ('ACTIVE', 'PAST_DUE', 'TRIALING') AND current_period_end IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Coupons: terms are immutable; archived, never deleted.
-- ---------------------------------------------------------------------------
CREATE TABLE coupons (
    id                  TEXT        PRIMARY KEY,
    application_id      UUID        NOT NULL REFERENCES applications (id),
    code                TEXT        NOT NULL CHECK (code ~ '^[A-Za-z0-9_-]{3,32}$'),
    percent_off         INT         CHECK (percent_off BETWEEN 1 AND 100),
    amount_off          BIGINT      CHECK (amount_off > 0),
    currency            CHAR(3),
    duration            TEXT        NOT NULL CHECK (duration IN ('ONCE', 'REPEATING', 'FOREVER')),
    duration_in_periods INT         CHECK (duration_in_periods BETWEEN 1 AND 120),
    max_redemptions     INT         CHECK (max_redemptions > 0),
    times_redeemed      INT         NOT NULL DEFAULT 0,
    redeem_by           TIMESTAMPTZ,
    plan_ids            TEXT[],                        -- null: every plan
    active              BOOLEAN     NOT NULL DEFAULT true,
    metadata            JSONB       NOT NULL DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((percent_off IS NULL) <> (amount_off IS NULL)),
    CHECK ((amount_off IS NULL) = (currency IS NULL)),
    CHECK ((duration = 'REPEATING') = (duration_in_periods IS NOT NULL)),
    CHECK (max_redemptions IS NULL OR times_redeemed <= max_redemptions)
);

-- Codes are matched case-insensitively.
CREATE UNIQUE INDEX coupons_app_code_idx ON coupons (application_id, upper(code));
CREATE INDEX coupons_app_id_idx ON coupons (application_id, id DESC);

CREATE FUNCTION coupons_immutable() RETURNS trigger AS $$
BEGIN
    IF (NEW.id, NEW.application_id, NEW.code, NEW.percent_off, NEW.amount_off, NEW.currency, NEW.duration,
        NEW.duration_in_periods, NEW.redeem_by, NEW.plan_ids, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.application_id, OLD.code, OLD.percent_off, OLD.amount_off, OLD.currency, OLD.duration,
        OLD.duration_in_periods, OLD.redeem_by, OLD.plan_ids, OLD.created_at) THEN
        RAISE EXCEPTION 'coupon terms are immutable: create a new coupon and archive this one';
    END IF;
    -- The cap may be raised or removed (null = no cap), never lowered or added.
    IF NEW.max_redemptions IS NOT NULL
       AND (OLD.max_redemptions IS NULL OR NEW.max_redemptions < OLD.max_redemptions) THEN
        RAISE EXCEPTION 'max_redemptions can only be raised or removed';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER coupons_immutable BEFORE UPDATE ON coupons FOR EACH ROW EXECUTE FUNCTION coupons_immutable();
CREATE TRIGGER coupons_no_delete BEFORE DELETE ON coupons FOR EACH ROW EXECUTE FUNCTION forbid_change();

CREATE TABLE coupon_redemptions (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id  UUID        NOT NULL REFERENCES applications (id),
    coupon_id       TEXT        NOT NULL REFERENCES coupons (id),
    subscription_id TEXT        NOT NULL REFERENCES subscriptions (id),
    customer_id     TEXT        NOT NULL REFERENCES customers (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX coupon_redemptions_coupon_idx ON coupon_redemptions (coupon_id, id);

-- ---------------------------------------------------------------------------
-- Discounts on subscriptions and invoices
-- ---------------------------------------------------------------------------
ALTER TABLE subscriptions ADD COLUMN coupon_id TEXT REFERENCES coupons (id);
ALTER TABLE subscriptions ADD COLUMN discount_periods_remaining INT CHECK (discount_periods_remaining >= 0);  -- null: forever

ALTER TABLE invoices ADD COLUMN subtotal BIGINT;
UPDATE invoices SET subtotal = amount_due;
ALTER TABLE invoices ALTER COLUMN subtotal SET NOT NULL;
ALTER TABLE invoices ADD CONSTRAINT invoices_subtotal_check CHECK (subtotal > 0);
ALTER TABLE invoices ADD COLUMN discount_amount BIGINT NOT NULL DEFAULT 0 CHECK (discount_amount >= 0);
ALTER TABLE invoices ADD COLUMN coupon_id TEXT REFERENCES coupons (id);
-- A 100% discount leaves nothing to pay: such an invoice is paid at creation, without a payment.
ALTER TABLE invoices DROP CONSTRAINT invoices_amount_due_check;
ALTER TABLE invoices ADD CONSTRAINT invoices_amount_due_check
    CHECK (amount_due >= 0 AND amount_due = subtotal - discount_amount);
