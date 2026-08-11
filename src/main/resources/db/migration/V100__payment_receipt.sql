-- Payments PLAN/FACT split. `project_payment` (V93) used to mix plan (amount) and fact
-- (paid_amount/paid_at) on one row — exactly one fact per plan row. A plan stage can now collect
-- SEVERAL partial payments (2 000, then 3 000 = closed), so the fact moves into its own table.
CREATE TABLE payment_receipt (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    -- The plan stage this receipt closes (partially or fully). NULL = a payment with no matching
    -- plan ("Своє") — its own free-form label instead. ON DELETE SET NULL: deleting a plan stage
    -- must never destroy money that was actually received; the receipt survives as unplanned.
    plan_payment_id UUID REFERENCES project_payment(id) ON DELETE SET NULL,
    amount NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    received_at DATE NOT NULL,
    label VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_receipt_project_id ON payment_receipt (project_id);
CREATE INDEX idx_payment_receipt_plan_payment_id ON payment_receipt (plan_payment_id);

-- Data migration: every existing project_payment.paid_amount > 0 becomes one payment_receipt row
-- linked back to the plan it came from. Covers both shapes the old model produced: a "Вже
-- отримано" row (amount == paid_amount, already a closed stage) and a partially-received planned
-- row (paid_amount < amount) — both just become one receipt against their own plan row.
INSERT INTO payment_receipt (id, project_id, plan_payment_id, amount, received_at, created_at)
SELECT gen_random_uuid(),
       p.project_id,
       p.id,
       p.paid_amount,
       COALESCE(p.paid_at::date, p.created_at::date),
       COALESCE(p.paid_at, p.created_at)
FROM project_payment p
WHERE p.paid_amount IS NOT NULL AND p.paid_amount > 0;

COMMENT ON COLUMN project_payment.paid_amount IS
    'DEPRECATED (V100) — fact moved to payment_receipt, a stage can now collect several. Column left unread, drop deferred to open-questions.';
COMMENT ON COLUMN project_payment.paid_at IS
    'DEPRECATED (V100) — see paid_amount comment.';
