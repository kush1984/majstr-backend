-- Add LINEAR_METER ("м.п.") to the allowed unit values — contractors measure
-- skirting, window slopes, etc. in running metres. Old migrations are
-- immutable, so drop and recreate each unit CHECK with the extended set.
-- Purely additive: existing rows keep their current units.

ALTER TABLE catalog_items     DROP CONSTRAINT catalog_items_unit_check;
ALTER TABLE catalog_items     ADD  CONSTRAINT catalog_items_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET'));

ALTER TABLE estimate_items    DROP CONSTRAINT estimate_items_unit_check;
ALTER TABLE estimate_items    ADD  CONSTRAINT estimate_items_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET'));

ALTER TABLE catalog_templates DROP CONSTRAINT catalog_templates_unit_check;
ALTER TABLE catalog_templates ADD  CONSTRAINT catalog_templates_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET'));
