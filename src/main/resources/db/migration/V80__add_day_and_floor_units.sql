-- Two units the tiling catalog needs and we did not have.
--
-- DAY («День роботи майстра») is how a tiler bills small or open-ended jobs — a whole
-- category of work is quoted this way, and without the unit those positions simply cannot be
-- expressed. HOUR is not a substitute: nobody quotes a day of tiling as 8 hours, and a master
-- forced to convert would enter a number that means something else.
--
-- FLOOR («Підняття матеріалу по сходах», грн./поверх) is the standard way carrying charges are
-- quoted in a building with no lift. PIECE would technically hold the number and destroy the
-- meaning on the client's estimate.
--
-- Old migrations are immutable, so each unit CHECK is dropped and recreated with the extended
-- set — same pattern as V18/V26/V27/V45. Purely additive: no existing row changes, and the four
-- tables that carry a unit column are all listed (measurement_item has its own, much smaller
-- CHECK and is deliberately NOT touched — a measurement is never billed per day).

ALTER TABLE catalog_items           DROP CONSTRAINT catalog_items_unit_check;
ALTER TABLE catalog_items           ADD  CONSTRAINT catalog_items_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR'));

ALTER TABLE estimate_items          DROP CONSTRAINT estimate_items_unit_check;
ALTER TABLE estimate_items          ADD  CONSTRAINT estimate_items_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR'));

ALTER TABLE catalog_templates       DROP CONSTRAINT catalog_templates_unit_check;
ALTER TABLE catalog_templates       ADD  CONSTRAINT catalog_templates_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR'));

ALTER TABLE estimate_template_items DROP CONSTRAINT estimate_template_items_unit_check;
ALTER TABLE estimate_template_items ADD  CONSTRAINT estimate_template_items_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR'));
