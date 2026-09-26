-- Review round 3, B-55: an estimate's «%» lines (discounts and surcharges) never reached acts.
-- Acts billed the GROSS position prices, so «Прийнято актами» could pass «За договором» — on a
-- 20 000 ₴ estimate with «Знижка −10 %» the client was billed the 2 000 ₴ he had not agreed to.
-- The fix is an automatic ADJUSTMENT line per act, per estimate and per type, carrying that
-- estimate's percentages prorated by what THIS act closes.
--
-- Such a line has no estimate_item_id (it closes no position) but it does belong to an estimate,
-- and that combination used to mean exactly one thing: «an additional work not in any estimate»,
-- which ActAddendumCreator rolls into a SIGNED ADDENDUM. Rolling an adjustment up that way would
-- put the discount into «За договором» a second time, so the distinction has to be recorded rather
-- than inferred — hence a third value instead of a boolean.
ALTER TABLE work_act_item
    ADD COLUMN line_kind varchar(20) NOT NULL DEFAULT 'ESTIMATE';

-- Backfill by the rule the code used until now: no estimate item = an additional work.
UPDATE work_act_item SET line_kind = 'ADDITIONAL' WHERE estimate_item_id IS NULL;

ALTER TABLE work_act_item
    ADD CONSTRAINT work_act_item_line_kind_chk
        CHECK (line_kind IN ('ESTIMATE', 'ADDITIONAL', 'ADJUSTMENT'));
