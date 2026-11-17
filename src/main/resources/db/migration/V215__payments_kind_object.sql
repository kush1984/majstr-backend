-- A one-off object (V214) is a payment of kind OBJECT. V40 limited payments.kind to CHECKOUT and
-- AUTO_RENEW with an inline CHECK, so the first object purchase was refused by the database.
ALTER TABLE payments DROP CONSTRAINT payments_kind_check;
ALTER TABLE payments ADD CONSTRAINT payments_kind_check CHECK (kind IN ('CHECKOUT', 'AUTO_RENEW', 'OBJECT'));
