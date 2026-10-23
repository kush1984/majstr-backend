-- =================================================================================================
-- V175 — two library coefficients re-read against the manufacturers' technical sheets
-- (calc-norms review, before the plaster and screed pages).
--
-- LIBRARY ROWS ONLY (`owner_id IS NULL`). A master's own norm is his answer and is never rewritten;
-- since V126 put `owner_id` inside `ux_material_norm` his fork sits beside the row changed here and
-- keeps hiding it for him (MaterialNormService.saveOwn, §21). No `catalog_templates` row is touched,
-- so V118's ranking need not be re-run, and no name/unit/trade moves, so no fork needs re-filing.
--
-- 1. TILE_ADHESIVE_C2 for 600×600, 800×800 and 1000×1000: 8,5 -> 7,0 kg/m².
--    Ceresit CM 17 technical sheet: ≈5,8 kg/m² with a 12 mm notched trowel, plus ≈1 kg/m² for the
--    layer on the tile itself — about 7 kg/m². From 600×1200 up, and the slabs, stone and outdoor
--    positions, 8,5 stays (owner's ruling after the same review). Both 1000×1000 keys are changed — the live
--    «…1000х1000 мм» and the «…1000х1000» V159 renamed it from, which kept its norm rows for an
--    estimate line written before the rename.
--
-- 2. PLASTER_GYPSUM (hand-applied gypsum plaster): 1,0 -> 0,9 kg/m² per mm, in EVERY position that
--    buys it. Knauf Rotband technical sheet: ≈8,5 kg/m² at a 10 mm layer, i.e. 0,85 per mm, rounded
--    to 0,9.
--
--    NOT changed, recorded so nobody repeats the search:
--    - PLASTER_GYPSUM_MACHINE 0,95 — Knauf MP 75 technical sheet: 8,5-10 kg/m² at 10 mm, i.e.
--      0,85-1,0 per mm; 0,95 sits inside it.
--    - PLASTER_CEMENT_LIME 1,4 (PAINTER walls, facade) beside 1,6 (TILING «Штукатурка маякова
--      цементна», the pool) — not an error: Kreisel's sheets give 1,1-1,4 kg/m² per mm for the
--      machine-applied cement-lime plaster and 1,5-1,7 for the hand-applied one.
--
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

UPDATE material_norm n
SET qty_per_unit = 7.0
FROM material m
WHERE m.id = n.material_id
  AND m.code = 'TILE_ADHESIVE_C2'
  AND n.owner_id IS NULL
  AND n.name_key IN ('укладання плитки 600х600',
                     'укладання плитки 800х800',
                     'укладання плитки 1000х1000',
                     'укладання плитки 1000х1000 мм');

UPDATE material_norm n
SET qty_per_unit = 0.9
FROM material m
WHERE m.id = n.material_id
  AND m.code = 'PLASTER_GYPSUM'
  AND n.owner_id IS NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM material_norm n JOIN material m ON m.id = n.material_id
               WHERE m.code = 'PLASTER_GYPSUM' AND n.owner_id IS NULL AND n.qty_per_unit <> 0.9) THEN
        RAISE EXCEPTION 'V175: a library PLASTER_GYPSUM norm was left at its old coefficient';
    END IF;
    IF EXISTS (SELECT 1 FROM material_norm n JOIN material m ON m.id = n.material_id
               WHERE m.code = 'TILE_ADHESIVE_C2' AND n.owner_id IS NULL AND n.qty_per_unit <> 7.0
                 AND n.name_key IN ('укладання плитки 600х600', 'укладання плитки 800х800',
                                    'укладання плитки 1000х1000', 'укладання плитки 1000х1000 мм')) THEN
        RAISE EXCEPTION 'V175: a re-read adhesive norm was left at its old coefficient';
    END IF;
END $$;
