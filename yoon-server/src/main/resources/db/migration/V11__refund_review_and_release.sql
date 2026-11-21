-- Money-safety fixes, November 2026 (docs/plans/money-safety-2026-11.md).
-- needs_review: a refund a human must settle (amount mismatch, Wave refunds whose outcome is
-- unknown). amount_released_at: an operator released the amount of a refund that failed after
-- the provider accepted it; until then that amount stays committed (decision D1).
ALTER TABLE refunds
    ADD COLUMN needs_review BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN amount_released_at TIMESTAMPTZ;
