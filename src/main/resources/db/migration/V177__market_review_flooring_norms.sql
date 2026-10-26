-- =================================================================================================
-- V177 — material norms for V176's eleven new FLOORING positions
--
-- V176 shipped them priced but un-normed, and FLOORING is the trade pinned to «every position
-- answers». Owner ruling (2026-10-26) on the preview in docs/iteration-market-price-review-2026-10.md
-- §4:
--
--   * four VERDICTS «consumes nothing» — three demolitions (as DEMOLITION's own), and the radius
--     trimming of laminate (labour and blade wear; the sealant is «Підрізка ламінату… защільнення»);
--   * herringbone SPC: SPC 1,12 + SPC underlay 1,0 — «Укладання ламінату ялинкою»'s figures;
--   * laminate on a wall or ceiling, glued: laminate 1,05 (as diagonal) + mounting adhesive 0,2 l/m²
--     (beads along every panel, ≈ one 0,31 l cartridge per 1,5 m² — calculation), no underlay;
--   * polyurethane skirting for painting: a new material, 1,05 + mounting adhesive 0,04 l/м.п. — the
--     polystyrene skirting's figures; the painting is its own position;
--   * screed beacons: a new material, 0,85 м.п. per m² (a beacon every ~1,2 m — calculation). NO
--     fixing mortar: beacons under a cement screed sit on the screed mix itself (or a rapid cement
--     mortar), which is already inside the screed's volume; gypsum in a cement screed is bad practice
--     (owner ruling).
--
-- DELIBERATELY UNANSWERED (named in FlooringNormsOnLiveDataIntegrationTest):
--   * the electric warm floor (mat / cable) and its thermostat — ELECTRICAL's rule: mats and cables
--     are fixed-area kits and the device is the client's pick, so a per-m² figure buys the wrong thing;
--   * polystyrene-concrete screed — no figure for how much finished layer a bag of dry mix yields.
--
-- New norms only; no catalog row is touched, so V118's ranking is not re-run.
-- The self-checks only WARN: they read the live library, which V176 may have left short of a
-- position it skipped, and a refusal here would take the deploy down (V176's incident).
-- =================================================================================================

INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    (gen_random_uuid(), 'SKIRTING_PU',   'Плінтус поліуретановий (під фарбування)', NULL, 'LINEAR_METER', 2.000, 'LINEAR_METER', 'планка'),
    (gen_random_uuid(), 'SCREED_BEACON', 'Маяк для стяжки',                         NULL, 'LINEAR_METER', 3.000, 'LINEAR_METER', 'планка')
ON CONFLICT DO NOTHING;

-- «Consumes nothing» is a recorded verdict: both nulls together (V127's material_norm_qty_check).
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'FLOORING', v.k, v.unit, NULL, NULL, 'QUANTITY', 1
  FROM (VALUES ('демонтаж ламінату',                       'M2'),
               ('демонтаж паркету / дерев''яної підлоги',  'M2'),
               ('демонтаж пвх-плитки / кварцвінілу',       'M2'),
               ('радіусна (фігурна) підрізка ламінату',    'LINEAR_METER')) AS v(k, unit);

INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'FLOORING', v.k, v.unit, m.id, v.qty, 'QUANTITY', v.ord
  FROM (VALUES ('укладання кварцвінілу ялинкою',                    'M2',           'SPC_VINYL',         1.12, 1),
               ('укладання кварцвінілу ялинкою',                    'M2',           'UNDERLAY_SPC',      1.0,  2),
               ('укладання ламінату на стіну/стелю (на клей)',      'M2',           'LAMINATE',          1.05, 1),
               ('укладання ламінату на стіну/стелю (на клей)',      'M2',           'MOUNTING_ADHESIVE', 0.2,  2),
               ('монтаж плінтуса поліуретанового (під фарбування)', 'LINEAR_METER', 'SKIRTING_PU',       1.05, 1),
               ('монтаж плінтуса поліуретанового (під фарбування)', 'LINEAR_METER', 'MOUNTING_ADHESIVE', 0.04, 2),
               ('встановлення маяків для стяжки',                   'M2',           'SCREED_BEACON',     0.85, 1))
       AS v(k, unit, code, qty, ord)
  JOIN material m ON m.code = v.code;

DO $$
DECLARE v_bad text;
BEGIN
    -- Every key above names a live FLOORING position (a typo would answer nothing, silently).
    SELECT string_agg(DISTINCT n.name_key, '; ') INTO v_bad
      FROM material_norm n
     WHERE n.owner_id IS NULL AND n.trade = 'FLOORING'
       AND n.name_key IN ('демонтаж ламінату', 'демонтаж паркету / дерев''яної підлоги',
                          'демонтаж пвх-плитки / кварцвінілу', 'радіусна (фігурна) підрізка ламінату',
                          'укладання кварцвінілу ялинкою', 'укладання ламінату на стіну/стелю (на клей)',
                          'монтаж плінтуса поліуретанового (під фарбування)', 'встановлення маяків для стяжки')
       AND NOT EXISTS (SELECT 1 FROM catalog_templates t
                        WHERE t.trade = 'FLOORING' AND t.type = 'WORK' AND t.unit = n.unit
                          AND lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) = n.name_key);
    IF v_bad IS NOT NULL THEN
        RAISE WARNING 'V177: a norm names no FLOORING position: %', v_bad;
    END IF;

    IF (SELECT count(*) FROM material_norm n
         WHERE n.owner_id IS NULL AND n.trade = 'FLOORING'
           AND n.name_key IN ('демонтаж ламінату', 'демонтаж паркету / дерев''яної підлоги',
                              'демонтаж пвх-плитки / кварцвінілу', 'радіусна (фігурна) підрізка ламінату',
                              'укладання кварцвінілу ялинкою', 'укладання ламінату на стіну/стелю (на клей)',
                              'монтаж плінтуса поліуретанового (під фарбування)', 'встановлення маяків для стяжки')) <> 11 THEN
        RAISE WARNING 'V177: expected 11 norm rows (4 verdicts + 7 materials)';
    END IF;
END $$;
