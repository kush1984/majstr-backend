-- Receipts & invoices on a work act (act-receipts iteration).
-- A master buys materials against his own money and re-bills them on the act: the client sees
-- «чек 1 — сума, чек 2 — сума, разом». Deliberately NOT a line-item import — the amount is typed
-- once and the photo is the proof.
--
-- Act-OWNED storage key, not a reference to project_photo: everything an act holds is a frozen
-- copy, and deleting an object photo must never change (or break the doc_hash of) a signed act.

CREATE TABLE work_act_receipt (
    id           uuid PRIMARY KEY,
    work_act_id  uuid NOT NULL REFERENCES work_act(id) ON DELETE CASCADE,
    label        varchar(160) NOT NULL,          -- «Епіцентр, клей + грунтовка»
    amount       numeric(15, 2) NOT NULL,
    issued_at    date,                           -- the receipt's own date, optional
    storage_key  varchar(255),                   -- act-owned photo copy, optional
    sort_order   int NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL,
    CONSTRAINT work_act_receipt_amount_chk CHECK (amount >= 0)
);

CREATE INDEX idx_work_act_receipt_act ON work_act_receipt(work_act_id, sort_order, id);

-- On signing, each receipt is posted as a MATERIALS object expense so «Прибуток» is not inflated by
-- money that is a pass-through. A master who already logs his receipts in the expense journal turns
-- this off, so the same receipt is never counted twice.
ALTER TABLE work_act
    ADD COLUMN receipts_to_expenses boolean NOT NULL DEFAULT true;
