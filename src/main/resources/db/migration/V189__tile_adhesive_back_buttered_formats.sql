-- =================================================================================================
-- V189 — tile adhesive for 600×600, 800×800 and 1000×1000 back to 8,5 kg/m² (C2), reversing that
-- half of V175.
--
-- LIBRARY ROWS ONLY (`owner_id IS NULL`), the shape of V175. A master's own norm is his answer and is
-- never rewritten: since V126 put `owner_id` inside `ux_material_norm` his fork sits beside the row
-- changed here and keeps hiding it for him (MaterialNormService.saveOwn, §21). No `catalog_templates`
-- row is touched, so V118's ranking need not be re-run; no name/unit/trade moves, so no fork needs
-- re-filing.
--
-- Why V175 was wrong here: it read the figure off Ceresit CM 17 Aero, an AERATED and deliberately
-- light C2 (5,8 kg/m² at a 12 mm notch), the lightest C2 in NORMS-SUMMARY §2.2. The ordinary C2
-- products in the same table give 7,6-7,8 at 12 mm (CM 14, CM 117, Kreisel 104-108); the layer
-- back-buttered on the tile adds ≈1, which is V137's 8,5. At 7,0 the library put LESS adhesive under
-- a back-buttered 600×600 than under a 300×600 on the same 12 mm notch with nothing on the tile (C1,
-- 7,8), while the public tile page explains that from 600×600 up the consumption per m² is higher.
--
-- Precondition (review B-50, the V176 lesson): only a row still at V175's 7,0 is rewritten. A row
-- that has moved since is reported with a WARNING and left as it is.
-- =================================================================================================

DO $$
DECLARE
    moved integer;
BEGIN
    SELECT count(*) INTO moved
      FROM material_norm n JOIN material m ON m.id = n.material_id
     WHERE m.code = 'TILE_ADHESIVE_C2'
       AND n.owner_id IS NULL
       AND n.qty_per_unit NOT IN (7.0, 8.5)
       AND n.name_key IN ('укладання плитки 600х600', 'укладання плитки 800х800',
                          'укладання плитки 1000х1000', 'укладання плитки 1000х1000 мм');
    IF moved > 0 THEN
        RAISE WARNING 'V189: % library adhesive norm(s) for 600×600 / 800×800 / 1000×1000 are no longer at 7,0; left as they are', moved;
    END IF;
END $$;

UPDATE material_norm n
SET qty_per_unit = 8.5
FROM material m
WHERE m.id = n.material_id
  AND m.code = 'TILE_ADHESIVE_C2'
  AND n.owner_id IS NULL
  AND n.qty_per_unit = 7.0
  AND n.name_key IN ('укладання плитки 600х600',
                     'укладання плитки 800х800',
                     'укладання плитки 1000х1000',
                     'укладання плитки 1000х1000 мм');

-- Self-check about this migration's OWN work only (review B-50).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM material_norm n JOIN material m ON m.id = n.material_id
               WHERE m.code = 'TILE_ADHESIVE_C2' AND n.owner_id IS NULL AND n.qty_per_unit = 7.0
                 AND n.name_key IN ('укладання плитки 600х600', 'укладання плитки 800х800',
                                    'укладання плитки 1000х1000', 'укладання плитки 1000х1000 мм')) THEN
        RAISE EXCEPTION 'V189: a library adhesive norm was left at V175''s 7,0';
    END IF;
END $$;
