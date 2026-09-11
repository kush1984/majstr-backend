-- =================================================================================================
-- V129 — «Чеки обʼєкта»: the receipt a master photographs at the till (object-receipts iteration).
--
-- WHY A SECOND RECEIPT TABLE
--   `work_act_receipt` (V110) is a receipt ON A DOCUMENT: it exists to be re-billed on one act and
--   is frozen into that act's `doc_hash`. The paper in the master's hand at the builders' merchant
--   belongs to no act yet — he buys before he signs, and often before an act exists at all. Filing
--   it against the OBJECT is what lets the till be two taps (camera → сума → зберегти) with no
--   document chosen and no economic decision made.
--
-- THE ONE DECISION THIS TABLE ENCODES (master's ruling)
--   `reimbursable` defaults to TRUE. In this master's world material money is mostly the CLIENT's,
--   so a receipt is a RECEIVABLE by default — «клієнт відшкодовує» — and NOT an expense. It becomes
--   an expense only when he says so with one tap, and only then does `expense_id` point at the
--   `object_expenses` row this table created. Flipping back deletes that row again. Two consequences
--   worth keeping:
--     * a reimbursable receipt writes NOTHING to `object_expenses`, so `Прибуток` is not quietly
--       wrong for every master who buys with the client's money (the V126 rule — «the list never
--       touches money» — still holds: money enters only by a receipt, and now by an EXPLICIT one);
--     * `expense_id` is ON DELETE SET NULL, so deleting the expense by hand from the expense
--       journal leaves the receipt intact and simply un-links it.
--
-- FISCAL IDENTITY IS A WARNING, NEVER A LOCK
--   `fiscal_fn` + `fiscal_id` come off the printed fiscal QR and identify the physical paper
--   exactly, so the same slip photographed twice can be SPOTTED. It is deliberately NOT a unique
--   index: the receipts-batch rule is that the photo is saved FIRST and read afterwards, so the
--   identity is only known on a later PATCH, and a unique index there would turn a duplicate into a
--   failed save of a photo already taken. A duplicate is surfaced on the read path and the master
--   decides. A hand-written товарний чек carries no such identity at all and is not covered.
-- =================================================================================================

CREATE TABLE project_receipt (
    id           uuid PRIMARY KEY,
    project_id   uuid NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- Blank on arrival is normal — the server names it «Чек №N», same as an act receipt.
    label        varchar(160) NOT NULL,
    -- Zero is a legal intermediate state: the paper is safe before any recognition runs.
    amount       numeric(15, 2) NOT NULL DEFAULT 0 CHECK (amount >= 0),
    issued_at    date,
    -- The photo is mandatory in the service; the column is nullable only because storage is
    -- pluggable and a future import path may have none.
    storage_key  varchar(255),
    reimbursable boolean NOT NULL DEFAULT true,
    expense_id   uuid REFERENCES object_expenses (id) ON DELETE SET NULL,
    fiscal_fn    varchar(64),
    fiscal_id    varchar(64),
    sort_order   integer NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    -- An expense may only exist for a receipt that is the master's own cost. The pair is written
    -- together in one transaction; the CHECK is what stops a later edit leaving a dangling bill.
    CONSTRAINT project_receipt_expense_only_when_own CHECK (expense_id IS NULL OR reimbursable = false)
);

CREATE INDEX idx_project_receipt_project ON project_receipt (project_id, sort_order);
-- Duplicate detection reads by fiscal identity within one object; partial, because the vast
-- majority of rows have none.
CREATE INDEX idx_project_receipt_fiscal ON project_receipt (project_id, fiscal_fn, fiscal_id)
    WHERE fiscal_fn IS NOT NULL AND fiscal_id IS NOT NULL;
