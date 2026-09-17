-- =================================================================================================
-- V134 — the same paper, filed in two places (review item B-04).
--
-- THE HOLE
--   A master photographs a receipt at the till: it lands in `project_receipt` (V129). Later the
--   same paper is attached to an act and lands in `work_act_receipt` (V110) as well. Nothing could
--   notice, because only the object table carried the printed fiscal identity — `work_act_receipt`
--   has no `fiscal_fn`/`fiscal_id` at all, so the two tables had no comparable key and the duplicate
--   warning V129 introduced could only ever look at ONE of them.
--
--   Worse than a missing warning: when the object copy was flipped to «це моя витрата» it posted a
--   MATERIALS/RECEIPT `object_expenses` row, and signing the act posts ANOTHER one for the same
--   money (`ActAddendumCreator.postReceiptExpenses`, when `receipts_to_expenses` is on). One paper,
--   two costs, and «Прибуток» understated by its full amount.
--
-- WHAT THIS MIGRATION ADDS
--   1. `work_act_receipt.fiscal_fn` + `fiscal_id` — the same two nullable columns V129 gave the
--      object receipt, filled by the same QR path. NOT a unique index, for V129's reason verbatim:
--      the photo is saved BEFORE anything is read off it, so the identity only arrives on a later
--      PATCH and a unique index would turn a duplicate into a failed save of a photo already taken.
--   2. `project_receipt.billed_on_act_id` — stamped when a SIGNED act bills the same paper. The row
--      then leaves the «клієнт відшкодовує» receivable axis, because the act's ADDENDUM has already
--      moved that money into «За договором»; leaving it in both would show the same receivable
--      twice. ON DELETE SET NULL, so deleting an act cannot take a master's receipt with it.
--
-- WHY THIS CANNOT MOVE A SINGLE FIGURE IN PRODUCTION TODAY
--   Every new column is nullable with no default, and the reconciliation that writes them runs only
--   at SIGN time, going forward. No existing `work_act_receipt` row can carry a fiscal identity (the
--   column did not exist a moment ago), so no already-signed act can match anything, and
--   `billed_on_act_id` is NULL on every existing row — which is exactly the state the read queries
--   treat as «unchanged». Historical acts are frozen documents and are deliberately NOT rescanned:
--   silently restating a master's past profit is worse than the gap it would close.
-- =================================================================================================

ALTER TABLE work_act_receipt ADD COLUMN fiscal_fn varchar(64);
ALTER TABLE work_act_receipt ADD COLUMN fiscal_id varchar(64);

-- Partial, like V129's twin index: the vast majority of rows carry no identity at all (a
-- hand-written товарний чек has none, and a vision read never produces one).
CREATE INDEX idx_work_act_receipt_fiscal ON work_act_receipt (fiscal_fn, fiscal_id)
    WHERE fiscal_fn IS NOT NULL AND fiscal_id IS NOT NULL;

ALTER TABLE project_receipt
    ADD COLUMN billed_on_act_id uuid REFERENCES work_act (id) ON DELETE SET NULL;

CREATE INDEX idx_project_receipt_billed_on_act ON project_receipt (billed_on_act_id)
    WHERE billed_on_act_id IS NOT NULL;

-- The promise this migration makes to production, asserted rather than claimed: it introduces the
-- ABILITY to link the two tables and links nothing. If either count is ever non-zero here, the
-- migration has done something to live money and must not be allowed to finish quietly.
DO $$
DECLARE
    stamped   bigint;
    identified bigint;
BEGIN
    SELECT count(*) INTO stamped FROM project_receipt WHERE billed_on_act_id IS NOT NULL;
    IF stamped <> 0 THEN
        RAISE EXCEPTION 'V134 must not bill any existing object receipt onto an act, but % row(s) '
                        'came out stamped', stamped;
    END IF;

    SELECT count(*) INTO identified FROM work_act_receipt
     WHERE fiscal_fn IS NOT NULL OR fiscal_id IS NOT NULL;
    IF identified <> 0 THEN
        RAISE EXCEPTION 'V134 must leave every existing act receipt unidentified, but % row(s) '
                        'carry a fiscal identity', identified;
    END IF;
END $$;
