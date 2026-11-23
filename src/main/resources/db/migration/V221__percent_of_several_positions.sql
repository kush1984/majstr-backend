-- «% від позицій»: a percentage line may be measured against SEVERAL lines of its estimate, as the sum
-- of their amounts (owner, 2026-11-23: «тут треба дозволяти вибирати з декількох позицій»).
--
-- The single percent_base_item_id (V88) becomes an ordered list. An array cannot carry a foreign key, so
-- the ON DELETE SET NULL backstop goes with the old column; every delete of a line goes through
-- EstimateService, which detaches the percentages pointing at it (detachPercentagesPointingAt), and
-- EstimateMath treats a base that is missing anyway as detached — the line keeps its last amount.

ALTER TABLE estimate_items
    ADD COLUMN percent_base_item_ids UUID[] NOT NULL DEFAULT '{}';

UPDATE estimate_items
SET percent_base_item_ids = ARRAY[percent_base_item_id]
WHERE percent_base_item_id IS NOT NULL;

DO $$
DECLARE
    missing INTEGER;
BEGIN
    SELECT count(*) INTO missing
    FROM estimate_items
    WHERE percent_base_item_id IS NOT NULL
      AND percent_base_item_ids <> ARRAY[percent_base_item_id];
    IF missing > 0 THEN
        RAISE EXCEPTION 'V221: % percentage lines did not carry their base over', missing;
    END IF;
END $$;

ALTER TABLE estimate_items
    DROP COLUMN percent_base_item_id;
