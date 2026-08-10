-- Object-level payments (завдаток + графік виплат). A project has ONE money relationship
-- with its client even when it carries several estimates (variants, consolidations, drafts)
-- — deposits/instalments belong to the OBJECT, not any single estimate. This table absorbs
-- the per-estimate `estimates.deposit_amount`, which stays in the schema (a back-compat
-- reader, see EstimateResponse/PublicEstimateView) but is no longer written by new code.
CREATE TABLE project_payment (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    -- Planned vs actual, kept separate: a client can pay 8 000 against a planned 10 000.
    amount NUMERIC(15,2) NOT NULL CHECK (amount >= 0),
    -- Not a debt reminder — the condition for starting the NEXT stage of work. See next_stage.
    due_date DATE,
    next_stage VARCHAR(255),
    purpose VARCHAR(255) NOT NULL,
    paid_amount NUMERIC(15,2) CHECK (paid_amount IS NULL OR paid_amount >= 0),
    paid_at TIMESTAMPTZ,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_project_payment_project_id ON project_payment (project_id);

-- Client-portal visibility for the payments card — off by default, the master opts in
-- (mirrors estimates.portal_visible, but this is one flag for the whole object).
ALTER TABLE project_share_links ADD COLUMN payments_visible BOOLEAN NOT NULL DEFAULT FALSE;

-- Data migration: absorb every existing deposit into a payment row, regardless of the
-- estimate's status or count_in_economy flag — nothing may be silently dropped. paid_at
-- uses the best available approximation (signed_at, else created_at); amount == paid_amount
-- since a deposit_amount always meant "already received" in the old model.
INSERT INTO project_payment (id, project_id, amount, purpose, paid_amount, paid_at, sort_order, created_at)
SELECT gen_random_uuid(),
       e.project_id,
       e.deposit_amount,
       'Завдаток',
       e.deposit_amount,
       COALESCE(e.signed_at, e.created_at),
       ROW_NUMBER() OVER (PARTITION BY e.project_id ORDER BY e.created_at) - 1,
       COALESCE(e.signed_at, e.created_at)
FROM estimates e
WHERE e.deposit_amount IS NOT NULL AND e.deposit_amount > 0;
