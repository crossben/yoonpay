-- Hosted checkout (ADR-0023): the customer picks the method on a Yoon page.
ALTER TABLE payments ADD COLUMN checkout TEXT NOT NULL DEFAULT 'direct'
    CHECK (checkout IN ('direct', 'hosted'));
-- Capability for this payment's checkout page only (it must be returned on every GET, so stored as is).
ALTER TABLE payments ADD COLUMN checkout_token TEXT;
ALTER TABLE payments ADD COLUMN hosted_checkout_url TEXT;
ALTER TABLE payments ADD COLUMN checkout_expires_at TIMESTAMPTZ;
-- The method the application restricted the page to, if any.
ALTER TABLE payments ADD COLUMN checkout_method TEXT;
-- True while one attempt round may be in flight: claimed before any provider call.
ALTER TABLE payments ADD COLUMN checkout_busy BOOLEAN NOT NULL DEFAULT false;

-- The method is chosen later for hosted payments.
ALTER TABLE payments ALTER COLUMN method DROP NOT NULL;
ALTER TABLE payments ADD CONSTRAINT payments_method_present CHECK (method IS NOT NULL OR checkout = 'hosted');
ALTER TABLE payments ADD CONSTRAINT payments_hosted_token CHECK (checkout = 'direct' OR checkout_token IS NOT NULL);
