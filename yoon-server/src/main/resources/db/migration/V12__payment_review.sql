-- Money-safety fixes, November 2026 (docs/plans/money-safety-2026-11.md, M1): a payment the
-- provider confirmed at another amount is flagged once for a human instead of being re-checked
-- and re-alerted on every sweep.
ALTER TABLE payments ADD COLUMN needs_review BOOLEAN NOT NULL DEFAULT false;
