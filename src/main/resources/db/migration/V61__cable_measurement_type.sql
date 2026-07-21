-- Electrical calculator, revised model: cable ≠ chase are two separate estimate entities
-- (a MATERIAL and a WORK), computed from ONE shared input.
--   CABLE (м)  — physical wire length = bus (магістраль) + every drop, + reserve %.
--   SHTROBA (м.пог) — chase length = only what is actually cut (bus + drops flagged «штробити»);
--                     a ceiling bus or an un-plastered wall is left out.
-- CABLE is an ordinary measurement_item too, so it substitutes into estimate lines by unit (м).
--
-- Widen both CHECKs: `type` for CABLE, and `unit` for M (the table only allowed
-- M2 / LINEAR_METER / PIECE until now).

ALTER TABLE measurement_item DROP CONSTRAINT measurement_item_type_check;
ALTER TABLE measurement_item ADD CONSTRAINT measurement_item_type_check
    CHECK (type IN ('SURFACE', 'PARTITION', 'LINEAR', 'ELECTRICAL_POINTS', 'SHTROBA', 'CABLE'));

ALTER TABLE measurement_item DROP CONSTRAINT measurement_item_unit_check;
ALTER TABLE measurement_item ADD CONSTRAINT measurement_item_unit_check
    CHECK (unit IN ('M2', 'LINEAR_METER', 'PIECE', 'M'));
