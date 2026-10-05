-- =================================================================================================
-- Review round 2, §3 (the norm data corrections) and B-51 (primer bought several times).
--
-- Evidence: `datasheets/NORMS-SUMMARY.md` and the CSVs the V133/V137/V138 survey was built from, as
-- cited per block below. V133's drywall figures are correct and are not touched.
--
-- Every UPDATE and DELETE here covers the masters' own FORKS as well, which is the B-35 rule: V126
-- put `owner_id` INSIDE `ux_material_norm` so a fork sits beside the default it hides, and a
-- migration that moves a shipped norm without moving the forks leaves a pair that is no longer a
-- pair. Where a shipped row is DELETED its forks go with it — a fork hides a default, and a fork
-- with no default left is a coefficient still being applied for a reason nobody can read.
--
-- Self-checks are RAISE WARNING, not EXCEPTION (review B-50): these statements match on library
-- names an admin can rename between two deploys, and a deploy that fails on a tidy-up is worse than
-- a warning in the log. The catalog-coverage integration tests are what keep the names honest in CI.
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 1. PRIMER IS BOUGHT ONCE (review B-51, owner's option (a))
--
-- Laying, painting, putty, waterproofing and self-levelling positions each carried their own primer
-- row, and the shipped bundles ALSO contain a standalone «Грунтування» step. «Підлога плиткою»
-- bought primer on 4 lines, «Санвузол під ключ» on 5, «Малярні роботи» on 3. The owner's ruling:
-- primer belongs to the standalone position only — «майстер і так окремо прайсить Грунтування як
-- етап» — so the per-work rows come off.
--
-- The rule is DATA-DRIVEN, not a list of names: a position keeps its primer row when primer is the
-- ONLY thing it consumes (that position IS the priming step), and loses it when the position also
-- consumes something else. That is exactly «only on the standalone position», and it needs no
-- maintenance when a later migration adds a work position that follows the same pattern.
--
-- IT APPLIES TO M2 POSITIONS ONLY, and the bound is the ruling itself: the standalone «Грунтування»
-- step is priced per square metre, so it can only stand in for work measured the same way. A door
-- priced per LEAF is not covered by any m² of priming the master also prices, so V139's hidden-mount
-- door — filled flush and painted with the wall's own paint — keeps its primer. §3's moulding ruling
-- is separate and has a PRODUCT reason rather than this one: nobody primes a foam or PU baguette,
-- which is why V139 already left primer off an ordinary door, and those rows are dropped below.
--
-- It also settles §3's plaster item for free: the gypsum-plaster rows carried PRIMER_CONTACT, which
-- is the wrong product (the sheets call for a deep primer), and they simply go.
-- -------------------------------------------------------------------------------------------------
-- LEFT JOIN, so a «consumes nothing» verdict row (V127's 11, `material_id IS NULL`) counts as
-- something other than primer: a sanding position that also carried a primer row is a work position
-- like any other, and an inner join would have hidden that by dropping the verdict row entirely.
CREATE TEMP TABLE primer_only_positions AS
SELECT n.name_key, n.unit
  FROM material_norm n
  LEFT JOIN material m ON m.id = n.material_id
 WHERE n.owner_id IS NULL
 GROUP BY n.name_key, n.unit
HAVING bool_and(m.code IS NOT NULL AND m.code LIKE 'PRIMER%');

DO $$
DECLARE doomed INT;
BEGIN
    SELECT count(*) INTO doomed
      FROM material_norm n
      JOIN material m ON m.id = n.material_id
     WHERE m.code LIKE 'PRIMER%'
       AND n.unit = 'M2'
       AND NOT EXISTS (SELECT 1 FROM primer_only_positions p
                        WHERE p.name_key = n.name_key AND p.unit = n.unit);
    RAISE NOTICE 'V145: dropping % primer norm row(s) that rode on a work position', doomed;
    IF doomed = 0 THEN
        RAISE WARNING 'V145: no per-work primer rows found — has the library been rebuilt?';
    END IF;
END $$;

-- Owned forks FIRST: once the shipped row is gone there is nothing left to tell us the fork hid it.
DELETE FROM material_norm n
 USING material m
 WHERE m.id = n.material_id
   AND n.owner_id IS NOT NULL
   AND m.code LIKE 'PRIMER%'
   AND n.unit = 'M2'
   AND NOT EXISTS (SELECT 1 FROM primer_only_positions p
                    WHERE p.name_key = n.name_key AND p.unit = n.unit);

DELETE FROM material_norm n
 USING material m
 WHERE m.id = n.material_id
   AND n.owner_id IS NULL
   AND m.code LIKE 'PRIMER%'
   AND n.unit = 'M2'
   AND NOT EXISTS (SELECT 1 FROM primer_only_positions p
                    WHERE p.name_key = n.name_key AND p.unit = n.unit);

-- §3, row 11: a foam or polyurethane baguette is not primed with a deep wall primer — V139 itself
-- left primer off an ordinary door for exactly this reason and then put it on the mouldings. These
-- are LINEAR_METER rows, so the m² rule above does not reach them; the reason here is the product,
-- not the pricing.
DELETE FROM material_norm n
 USING material m
 WHERE m.id = n.material_id
   AND m.code = 'PRIMER_DEEP'
   AND n.unit = 'LINEAR_METER'
   AND (n.name_key LIKE 'фарбування молдинга%' OR n.name_key LIKE 'фарбування стельових багет%');

DROP TABLE primer_only_positions;

-- -------------------------------------------------------------------------------------------------
-- 2. TILE ADHESIVE: the notch the format actually needs
--
-- §3, rows 1-4. No C1 sheet allows a format this size (CM 11 and Kreisel 101/102/111 cap at 40×40;
-- CM 11 Plus/Pro, CM 12 and P-12 at 60×60), and V137's own «longer side ≥ 40 cm → 12 mm notch» rule
-- was applied to some of these and not others.
-- -------------------------------------------------------------------------------------------------

-- Мозаїка sits on a 3-4 mm notch, not a 6 mm one: Kreisel ≤5 cm 1,95 kg/m², 5-10 cm 2,6;
-- CM 117 Pro 1,9; CM 11/117 at 4 mm 2,5-2,6. 3,9 was a wide-notch figure on the smallest tile there
-- is.
UPDATE material_norm n
   SET qty_per_unit = 2.6
 WHERE n.name_key IN ('укладання мозаїки', 'облицювання мозаїкою обсягів',
                      'облицювання радіусних поверхонь мозаїкою', 'ремонт облицювання з мозаїки')
   AND n.unit = 'M2'
   AND n.qty_per_unit = 3.9
   AND n.material_id = (SELECT id FROM material WHERE code = 'TILE_ADHESIVE_C2');

-- 300×900 and a 1200 mm plank are C2 territory at 8,5 kg/m², like every other format that size.
UPDATE material_norm n
   SET material_id = (SELECT id FROM material WHERE code = 'TILE_ADHESIVE_C2'),
       qty_per_unit = 8.5
 WHERE n.name_key IN ('укладання плитки 300х900', 'укладання плитки дошка до 1200 мм',
                      'укладання плитки дошка до 900 мм')
   AND n.unit = 'M2'
   AND n.material_id = (SELECT id FROM material WHERE code = 'TILE_ADHESIVE_C1');

-- A ceramic parquet / small plank is a 12 mm notch by the same rule: 7,8, not 6,5.
UPDATE material_norm n
   SET qty_per_unit = 7.8
 WHERE n.name_key = 'укладання керамічного паркету, дрібної дошки'
   AND n.unit = 'M2'
   AND n.material_id = (SELECT id FROM material WHERE code = 'TILE_ADHESIVE_C1');

-- A bed deeper than 10 mm is outside what a C1 sheet allows at all.
UPDATE material_norm n
   SET material_id = (SELECT id FROM material WHERE code = 'TILE_ADHESIVE_C2')
 WHERE n.name_key = 'укладання плитки на шар клею більше 1 см'
   AND n.unit = 'M2'
   AND n.material_id = (SELECT id FROM material WHERE code = 'TILE_ADHESIVE_C1');

-- -------------------------------------------------------------------------------------------------
-- 3. GROUT: 0,4 kg/m² is a 10-30 cm geometry, and it was applied to everything
--
-- §3, rows 5-7. CE 33/40's own table is geometric — a bigger tile has fewer metres of joint per m²,
-- so the figure FALLS as the format grows, and clinker «під цеглу» goes the other way entirely.
-- -------------------------------------------------------------------------------------------------

-- 600-800 mm formats: ≈0,15 kg/m².
UPDATE material_norm n
   SET qty_per_unit = 0.15
 WHERE n.unit = 'M2'
   AND n.qty_per_unit = 0.4
   AND n.material_id = (SELECT id FROM material WHERE code = 'TILE_GROUT')
   AND n.name_key IN ('укладання плитки 600х600', 'укладання плитки 600х1200',
                      'укладання плитки 800х800', 'укладання плитки 800х1600',
                      'укладання плитки дошка до 900 мм', 'укладання плитки дошка до 1200 мм',
                      'укладання плитки дошка до 1800 мм',
                      'облицювання широкоформатною плиткою обсягів');

-- 1000 mm and up, керамограніт and slabs: ≈0,1 kg/m².
UPDATE material_norm n
   SET qty_per_unit = 0.1
 WHERE n.unit = 'M2'
   AND n.qty_per_unit = 0.4
   AND n.material_id = (SELECT id FROM material WHERE code = 'TILE_GROUT')
   AND n.name_key IN ('укладання плитки 1000х1000', 'укладання плитки 1000х2000 мм',
                      'укладання плитки 1000х3000 мм', 'укладання плитки 1200х1200 мм',
                      'укладання плитки 1200х2400 мм', 'укладання плитки 1200х3200 мм',
                      'укладання плитки 1500х3000 мм', 'укладання плитки 1600х1600 мм',
                      'укладання плитки 1600х3200 мм', 'укладання плитки більше 3200 мм',
                      'укладання керамограніту 20 мм', 'укладання керамограніту на вулиці');
-- «укладання великих слябів з каменю» has no grout row at all and needs none: a slab wall is laid
-- edge to edge. Listing it here would have been a no-op either way, and saying so is cheaper than
-- someone re-deriving it.

-- Клінкер «під цеглу» is the opposite case: CE 43 gives 1,2 kg/m² for 10×10 at a 5 mm joint, and a
-- brick slip at 10 mm is about three by geometry. 1,2 is the floor, and it is the honest floor.
UPDATE material_norm n
   SET qty_per_unit = 1.2,
       baseline_param = 5
 WHERE n.unit = 'M2'
   AND n.material_id = (SELECT id FROM material WHERE code = 'TILE_GROUT')
   AND n.name_key = 'облицювання будинків клінкером «під цеглу»';

-- A clinker FLOOR tile at 30×30 with a 10 mm joint: CE 43 gives 0,8.
UPDATE material_norm n
   SET qty_per_unit = 0.8,
       baseline_param = 10
 WHERE n.unit = 'M2'
   AND n.material_id = (SELECT id FROM material WHERE code = 'TILE_GROUT')
   AND n.name_key = 'укладання клінкерної підлогової плитки';

-- A thick bed needs grouting too, and the position had adhesive only.
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), n.trade, n.name_key, n.unit,
       (SELECT id FROM material WHERE code = 'TILE_GROUT'), 0.4, 'QUANTITY', 2
  FROM material_norm n
 WHERE n.owner_id IS NULL
   AND n.name_key = 'укладання плитки на шар клею більше 1 см'
   AND n.unit = 'M2'
   AND n.material_id = (SELECT id FROM material WHERE code = 'TILE_ADHESIVE_C2')
   AND NOT EXISTS (SELECT 1 FROM material_norm g
                    WHERE g.owner_id IS NULL AND g.name_key = n.name_key AND g.unit = n.unit
                      AND g.material_id = (SELECT id FROM material WHERE code = 'TILE_GROUT'));

-- -------------------------------------------------------------------------------------------------
-- 4. JOINT PUTTY: Uniflott, Siniat and Rigips all state 0,25-0,3 kg per m² of board
-- §3, row 9. 0,4 was above every sheet in the survey.
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm n
   SET qty_per_unit = 0.3
 WHERE n.name_key = 'шпаклювання швів гкл та шурупів зі шліфуванням'
   AND n.unit = 'M2'
   AND n.qty_per_unit = 0.4
   AND n.material_id = (SELECT id FROM material WHERE code = 'PUTTY_JOINT');

-- -------------------------------------------------------------------------------------------------
-- 5. SEALANT: a movement joint and an acrylic join are not acoustic sealant
--
-- §3, row 10. `ACOUSTIC_SEALANT` is the drywall product V127 shipped for a partition's perimeter; a
-- tile movement joint takes silicone and «акрилення примикань» takes acrylic. Same 0,025 l/m, so no
-- figure moves — what moves is WHICH TUBE the master is sent to buy, and the cartridge is 0,28-0,31
-- l rather than the acoustic tube's 0,6.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name)
VALUES
    (gen_random_uuid(), 'SEALANT_SILICONE', 'Герметик силіконовий', NULL, 'LITRE', 0.3, 'LITRE', 'картридж'),
    (gen_random_uuid(), 'SEALANT_ACRYLIC',  'Герметик акриловий',   NULL, 'LITRE', 0.3, 'LITRE', 'картридж')
ON CONFLICT (code) DO NOTHING;

UPDATE material_norm n
   SET material_id = (SELECT id FROM material WHERE code = 'SEALANT_SILICONE')
 WHERE n.name_key = 'заповнення швів герметиком'
   AND n.unit = 'LINEAR_METER'
   AND n.material_id = (SELECT id FROM material WHERE code = 'ACOUSTIC_SEALANT');

UPDATE material_norm n
   SET material_id = (SELECT id FROM material WHERE code = 'SEALANT_ACRYLIC')
 WHERE n.name_key IN ('акрилення примикань',
                      'герметизація швів, стиків акрилом, спеціальною мастикою',
                      'герметизація швів стиків акрилом мастикою')
   AND n.unit = 'LINEAR_METER'
   AND n.material_id = (SELECT id FROM material WHERE code = 'ACOUSTIC_SEALANT');

-- -------------------------------------------------------------------------------------------------
-- 6. WALLPAPER GLUE: 0,01 kg/m² was a fleece-adhesive figure on a dispersive job
--
-- §3, last row. Quelyd's fleece figure is «apply to the wall», which is where 0,01 came from;
-- Capacoll gives 0,15-0,3 kg/m² for a dispersive glue on paper. 0,2 is the middle of that, and it is
-- what a paper or photo wallpaper actually takes.
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm n
   SET qty_per_unit = 0.2
 WHERE n.unit = 'M2'
   AND n.qty_per_unit = 0.01
   AND n.material_id = (SELECT id FROM material WHERE code = 'WALLPAPER_GLUE');

-- -------------------------------------------------------------------------------------------------
-- 7. PACKAGES: the size the shop actually sells
--
-- §3, «Packages». Ceresit CT 19 and CT 16 sell in 7,5 kg buckets, and V137's own rule is that the
-- SMALLER package wins — rounding up to a 15 kg bucket for 3 kg of contact primer is a bucket the
-- master does not need. Deep primer went to 5 l for the same reason: it is now on almost every
-- standalone priming position, where a 10 l canister is over half a job.
-- -------------------------------------------------------------------------------------------------
UPDATE material SET package_size = 7.5 WHERE code IN ('PRIMER_CONTACT', 'PRIMER_QUARTZ')
   AND package_size = 15;
UPDATE material SET package_size = 5 WHERE code = 'PRIMER_DEEP' AND package_size = 10;

-- -------------------------------------------------------------------------------------------------
-- 8. SELF-CHECK: every shipped norm still points at a material, and no position lost everything
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE orphans INT; emptied INT;
BEGIN
    SELECT count(*) INTO orphans FROM material_norm WHERE material_id IS NULL AND owner_id IS NULL
       AND basis <> 'QUANTITY';
    IF orphans > 0 THEN
        RAISE WARNING 'V145: % shipped norm(s) point at no material and are not a «consumes nothing» verdict', orphans;
    END IF;

    -- A work position that consumed ONLY primer would now consume nothing at all. By construction
    -- the primer rule above cannot produce one (such a position is primer-only and was kept), so
    -- this is the assertion that the rule did what it says.
    SELECT count(*) INTO emptied FROM (
        SELECT 1 FROM material_norm WHERE owner_id IS NULL GROUP BY name_key, unit HAVING count(*) = 0
    ) d;
    IF emptied > 0 THEN
        RAISE WARNING 'V145: % position(s) were left with no norm at all', emptied;
    END IF;
END $$;
