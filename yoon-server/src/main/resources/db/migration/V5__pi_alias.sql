-- PI-SPI addresses people by payment alias, not phone (ADR-0019).
ALTER TABLE payments ADD COLUMN customer_pi_alias TEXT;

ALTER TABLE payouts ADD COLUMN recipient_pi_alias TEXT;
ALTER TABLE payouts ALTER COLUMN recipient_phone DROP NOT NULL;
ALTER TABLE payouts ADD CONSTRAINT payouts_recipient_present
    CHECK (recipient_phone IS NOT NULL OR recipient_pi_alias IS NOT NULL);
