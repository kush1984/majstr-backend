-- A «%» line may now be NEGATIVE — a discount off a position or off the whole estimate.
--
-- «%» stopped being a unit of measure in V88: it is a share OF something, and quantity holds the
-- percent (10 = 10 %). A discount is simply that percent with a minus (-15 = −15 %). The line amount
-- then comes out negative (base × percent / 100), which EstimateMath already computes with a plain
-- multiply — no clamps — and the object-economy SUM(line_total) already lets it lower the income:
-- «дав знижку — менше заробив». Nothing downstream needed changing; only this guard stood in the way.
--
-- The V29 CHECK «quantity >= 0» was written when quantity always meant a count, where a negative is
-- nonsense. It still is for every unit EXCEPT percent — a WORK line of «-3 шт» is a data-entry bug,
-- not a discount — so the rule is relaxed for PERCENT alone and kept intact for the rest.
ALTER TABLE estimate_items DROP CONSTRAINT estimate_items_quantity_check;
ALTER TABLE estimate_items ADD CONSTRAINT estimate_items_quantity_check
    CHECK (quantity >= 0 OR unit = 'PERCENT');
