-- New unit KM (км, kilometres) — cable runs and similar are quoted per km.
-- Old migrations are immutable, so each unit CHECK is dropped and recreated with the
-- extended value set (same pattern as V18/V26/V27). Purely additive — existing rows
-- are untouched. Four tables carry a unit column with a CHECK.

ALTER TABLE catalog_items          DROP CONSTRAINT catalog_items_unit_check;
ALTER TABLE catalog_items          ADD  CONSTRAINT catalog_items_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM'));

ALTER TABLE estimate_items         DROP CONSTRAINT estimate_items_unit_check;
ALTER TABLE estimate_items         ADD  CONSTRAINT estimate_items_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM'));

ALTER TABLE catalog_templates      DROP CONSTRAINT catalog_templates_unit_check;
ALTER TABLE catalog_templates      ADD  CONSTRAINT catalog_templates_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM'));

ALTER TABLE estimate_template_items DROP CONSTRAINT estimate_template_items_unit_check;
ALTER TABLE estimate_template_items ADD  CONSTRAINT estimate_template_items_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM'));
