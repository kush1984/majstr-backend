-- Partial returns of material re-billed on an act (act-receipt-returns iteration).
--
-- The master buys nails for 2000, uses part of them and takes the leftovers back to the shop for
-- 500. There is no return document worth photographing and no relation to any single purchase —
-- what he needs is one number on the receipt he already has: «повернуто 500 з 2000», billed as 1500.
--
-- Deliberately NOT a second, negative receipt row: capping the return at the receipt's own amount is
-- what keeps every downstream figure non-negative — the ADDENDUM line, the MATERIALS expense and
-- «Прийнято актами» all take (amount - returned_amount), so nothing anywhere needs a signed value.
ALTER TABLE work_act_receipt
    ADD COLUMN returned_amount numeric(15, 2) NOT NULL DEFAULT 0;

ALTER TABLE work_act_receipt
    ADD CONSTRAINT work_act_receipt_returned_chk
        CHECK (returned_amount >= 0 AND returned_amount <= amount);
