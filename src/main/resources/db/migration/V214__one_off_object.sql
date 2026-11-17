-- One-off paid object (2026-11-17): a FREE master at the object cap may buy ONE object for a fixed price
-- instead of a subscription. The payment buys a credit; the next object created at the cap spends it
-- and is stamped paid. A paid object gets PRO's per-object limits and the logo on its PDFs, never the
-- AI features — those stay PRO.
ALTER TABLE users ADD COLUMN object_credits INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD CONSTRAINT users_object_credits_chk CHECK (object_credits >= 0);

ALTER TABLE projects ADD COLUMN paid_at TIMESTAMPTZ;

COMMENT ON COLUMN users.object_credits IS 'One-off objects bought and not created yet (V214); spent by the next create at the FREE cap.';
COMMENT ON COLUMN projects.paid_at IS 'Created by spending a one-off object credit (V214); NULL = an ordinary object.';
