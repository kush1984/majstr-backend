-- Electrical core: two new measurement element kinds riding the existing rails.
--   ELECTRICAL_POINTS (шт)      — discrete points read off a plan (sockets, switches,
--                                 luminaires, power outlets). Counted, never measured.
--   SHTROBA           (м.пог)   — chasing length: one horizontal bus per room + a vertical
--                                 drop per point. Deterministic calc, never LLM-estimated.
-- Both are ordinary measurement_item rows, so substitution into estimate lines keeps
-- working purely by unit (PIECE → "шт" lines, LINEAR_METER → "м.пог" lines).
--
-- Both CHECK constraints must be widened: `type` for the new kinds and `unit` for PIECE
-- (the table only allowed M2 / LINEAR_METER until now).

ALTER TABLE measurement_item DROP CONSTRAINT measurement_item_type_check;
ALTER TABLE measurement_item ADD CONSTRAINT measurement_item_type_check
    CHECK (type IN ('SURFACE', 'PARTITION', 'LINEAR', 'ELECTRICAL_POINTS', 'SHTROBA'));

ALTER TABLE measurement_item DROP CONSTRAINT measurement_item_unit_check;
ALTER TABLE measurement_item ADD CONSTRAINT measurement_item_unit_check
    CHECK (unit IN ('M2', 'LINEAR_METER', 'PIECE'));
