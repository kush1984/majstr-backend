-- =================================================================================================
-- V146 — the trade-by-trade review, batch 1: what buys the WRONG QUANTITY today
--
-- Source: the nine per-trade reports behind `TRADES-REVIEW.md` §4.1, read against a clean V145 DB.
-- Everything here is a figure or a sequence that is wrong NOW; catalog additions, renames and the
-- trades that still have no norms at all are later batches and are deliberately absent.
--
-- THE RULE EVERY COEFFICIENT STATEMENT HONOURS (review B-108): a master's fork sits BESIDE the
-- shipped row it hides (`owner_id` is inside `ux_material_norm`, V126), so a figure is rewritten
-- only `WHERE owner_id IS NULL OR qty_per_unit = <the old shipped value>`. A fork that still carries
-- the shipped number is a fork of something else and follows; a fork carrying his own number is his
-- answer and is left alone. A MATERIAL move, by contrast, carries the forks unconditionally (review
-- B-35) — a norm is a (material, coefficient) PAIR, and stranding half of it is worse than moving
-- it. Forked BUNDLES are likewise untouched: every template statement filters the PARENT on
-- `is_default AND owner_id IS NULL`.
--
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50); anything about
-- data an admin can rename between two deploys is a `RAISE WARNING`.
--
-- CORRECTIONS TO SHIPPED COMMENTS (a migration is immutable once applied, so they live here):
--   * `V142:35-36` says the parameter's upper bound «is the same 1000 the service and the PWA's own
--     field guard use». That stopped being true in V145, which gave every QUESTION its own bound —
--     thickness ≤ 150 mm, section ≤ 5 m, perimeter ≤ 1000 m. Only the perimeter still sees 1000.
--   * `V145:69` says «Owned forks FIRST: once the shipped row is gone there is nothing left to tell
--     us the fork hid it.» The order was harmless but the reason was wrong: both DELETEs read
--     `primer_only_positions`, a TEMP TABLE already computed from `owner_id IS NULL` rows alone, so
--     deleting the shipped row could not have changed what the fork statement saw.
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 1. UD PROFILE ON A WALL IS NOT A PERIMETER (DRYWALL §4, item 1)
--
-- A wall lining and a two-layer sound-insulating wall both ask for 2,1 lm of UD per metre of
-- PERIMETER — the ceiling figure, where UD really does run the room's outline once. On a wall the
-- UD is the top and bottom track of the frame, so it scales with the AREA being clad: Knauf W623
-- surveys ~0,7 lm/m². Asked as a perimeter it is also asked ONCE for the whole estimate (the larger
-- per-metre figure wins), so a flat answering three walls bought one wall's track.
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm n
   SET basis = 'QUANTITY',
       qty_per_unit = 0.7,
       default_param = NULL
 WHERE n.name_key IN ('монтаж гіпсокартону на стіни',
                      'каркасна звукоізоляція (гкл в два слоя) стін')
   AND n.unit = 'M2'
   AND n.basis = 'PERIMETER'
   AND n.material_id = (SELECT id FROM material WHERE code = 'PROFILE_UD')
   AND (n.owner_id IS NULL OR n.qty_per_unit = 2.1);

DO $$
DECLARE left_over INT;
BEGIN
    SELECT count(*) INTO left_over
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.name_key IN ('монтаж гіпсокартону на стіни',
                          'каркасна звукоізоляція (гкл в два слоя) стін')
       AND n.unit = 'M2'
       AND n.basis = 'PERIMETER'
       AND n.material_id = (SELECT id FROM material WHERE code = 'PROFILE_UD');
    IF left_over <> 0 THEN
        RAISE EXCEPTION 'V146: % shipped UD wall norm(s) are still asked as a PERIMETER', left_over;
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 2. MINERAL WOOL BOUGHT TWICE — on the WALL, and only there (DRYWALL §4, item 2)
--
-- «Звукоізоляція та утеплення» prices «Звукоізоляція стін мінеральною ватою» (1,05 m² of wool) and
-- then «Каркасна звукоізоляція (ГКЛ в два слоя) стін», which carries its own 1,05. Both lines are
-- priced for the same wall, so the wall's wool was bought twice. The wool comes off the FRAME
-- position, exactly as it already works for a partition: the frame position buys the frame and the
-- sheets, the insulation position buys the insulation.
--
-- The CEILING twin keeps its wool, and that is not an inconsistency. The library has no
-- «Звукоізоляція стелі мінеральною ватою» — the only standalone wool position is the wall one — so
-- «Каркасна звукоізоляція (ГКЛ в два слоя) стелі» is the one line in the bundle that buys the
-- ceiling's wool at all. Taking it off would stop a double count that does not exist and start an
-- undercount that does. The ceiling gets its own standalone position in the catalog-additions batch,
-- and the wool moves then, in the same migration that gives it somewhere to move TO.
--
-- Shipped rows only. A master who forked the wool row on the frame position typed a figure of his
-- own against a sequence of his own, and deleting it would take an answer he can still see with it.
-- -------------------------------------------------------------------------------------------------
DELETE FROM material_norm n
 WHERE n.owner_id IS NULL
   AND n.name_key = 'каркасна звукоізоляція (гкл в два слоя) стін'
   AND n.unit = 'M2'
   AND n.material_id = (SELECT id FROM material WHERE code = 'MINERAL_WOOL');

-- The master is the one who must notice that the wool is a second line now.
UPDATE catalog_templates
   SET description = 'Каркас і подвійна обшивка. Мінвата — окремою позицією «Звукоізоляція стін мінеральною ватою».'
 WHERE lower(trim(name)) = 'каркасна звукоізоляція (гкл в два слоя) стін'
   AND unit = 'M2'
   AND coalesce(description, '') = '';

DO $$
DECLARE left_over INT;
        ceiling   INT;
BEGIN
    SELECT count(*) INTO left_over
      FROM material_norm n
      JOIN material m ON m.id = n.material_id
     WHERE n.owner_id IS NULL
       AND m.code = 'MINERAL_WOOL'
       AND n.name_key = 'каркасна звукоізоляція (гкл в два слоя) стін';
    IF left_over <> 0 THEN
        RAISE EXCEPTION 'V146: the shipped wall frame position still buys mineral wool (% row(s))', left_over;
    END IF;

    -- And the ceiling still does, which is the whole point of the paragraph above.
    SELECT count(*) INTO ceiling
      FROM material_norm n
      JOIN material m ON m.id = n.material_id
     WHERE n.owner_id IS NULL
       AND m.code = 'MINERAL_WOOL'
       AND n.name_key = 'каркасна звукоізоляція (гкл в два слоя) стелі';
    IF ceiling <> 1 THEN
        RAISE EXCEPTION 'V146: the ceiling frame position must keep its wool, found % row(s)', ceiling;
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 3. GROUT BOUGHT TWICE — the hybrid ruling (TILING §4, items 3-4)
--
-- Every laying position carries grout AND the bundles contain a separate grouting step, so grout
-- was bought twice. It is NOT the primer case, where the answer was «only the standalone step»:
-- grout is GEOMETRY (tile sides, tile thickness, joint width), and the LAYING position is the only
-- one that knows the format. So the owner's ruling is a hybrid:
--
--   * grout stays on the laying positions, where the format is known;
--   * a «Заповнення/затирання швів» step that FOLLOWS laying consumes nothing — a recorded verdict
--     (`material_id IS NULL AND qty_per_unit IS NULL`, V127's shape), not a deleted row, so the
--     calculator can say «ця позиція нічого не споживає» instead of staying silent;
--   * a standalone grouting JOB keeps its own norm, because no laying line stands beside it —
--     «Заміна затірки швів», «Заповнення товстого шва напівсухою сумішшю», «Затирання швів від
--     3 мм» and «Затирання швів у декоративній плитці, мозаїці».
--
-- Shipped rows only, for the same reason as the wool above.
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm n
   SET material_id = NULL,
       qty_per_unit = NULL
 WHERE n.owner_id IS NULL
   AND n.unit = 'M2'
   AND n.name_key IN ('заповнення швів цементною сумішшю',
                      'заповнення швів цементною сумішшю з латексом',
                      'затирання швів цементною сумішшю з латексом')
   AND n.material_id = (SELECT id FROM material WHERE code = 'TILE_GROUT')
   AND NOT EXISTS (SELECT 1 FROM material_norm o
                    WHERE o.owner_id IS NULL AND o.trade = n.trade
                      AND o.name_key = n.name_key AND o.unit = n.unit
                      AND o.material_id IS NULL);

DO $$
DECLARE wrong INT;
BEGIN
    SELECT count(*) INTO wrong
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.unit = 'M2'
       AND n.name_key IN ('заповнення швів цементною сумішшю',
                          'заповнення швів цементною сумішшю з латексом',
                          'затирання швів цементною сумішшю з латексом')
       AND n.material_id IS NOT NULL;
    IF wrong <> 0 THEN
        RAISE EXCEPTION 'V146: % grouting step(s) that follow laying still consume a material', wrong;
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 3b. THE GROUT FIGURES THEMSELVES
--
-- Mass per m² follows the manufacturers' own formula (Mapei Ultracolor TDS, and Ceresit/Kreisel
-- agree within rounding):
--
--     kg/m² = (A + B) / (A × B) × C × D × 1,6     A, B = tile sides mm, C = thickness mm,
--                                                 D = joint mm, 1,6 = bulk density
--
-- The shipped figures were a flat 0,4 for «anything small» and a flat 0,10-0,15 for «anything
-- large», which is right for neither: a 100×100 tile needs more than 0,4 and a 300×600 needs half
-- of it, and a plank's long side does not make its joints disappear.
--
-- `baseline_param` (V144) is the joint each coefficient was written for, so the master's
-- `TILE_JOINT_MM` habit rescales it honestly. A figure with no baseline is read against the
-- product-wide 2,5 mm, which is what these ordinary-format rows assume — only the WIDE-joint rows
-- state a baseline of their own.
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm n
   SET qty_per_unit   = v.new_qty,
       baseline_param = coalesce(v.baseline, n.baseline_param)
  FROM (VALUES
      -- format               old    new    baseline (mm)
      ('укладання плитки 300х300',                      0.4,  0.25, NULL::numeric),
      ('укладання плитки 300х600',                      0.4,  0.2,  NULL),
      ('укладання плитки 300х900',                      0.4,  0.2,  NULL),
      ('укладання плитки 100х100',                      0.4,  0.5,  NULL),
      ('укладання плитки кабанчик, «цегла»',            0.4,  0.5,  NULL),
      ('укладання плитки дошка до 900 мм',              0.15, 0.3,  NULL),
      ('укладання плитки дошка до 1200 мм',             0.15, 0.25, NULL),
      ('укладання плитки дошка до 1800 мм',             0.15, 0.22, NULL),
      ('облицювання стандартною плиткою обсягів',       0.4,  0.3,  NULL),
      ('облицювання плиткою короба',                    0.4,  0.3,  NULL),
      -- Outdoor and clinker work: a wide joint, and a baseline that says so
      ('укладання керамограніту 20 мм',                 0.1,  0.4,  3),
      ('укладання керамограніту на вулиці',             0.1,  0.2,  3),
      ('облицювання будинків клінкером «під цеглу»',    1.2,  2.9,  10),
      -- Standalone grouting jobs: «від 3 мм» was carrying a 6 mm figure under a 3 mm name
      ('затирання швів від 3 мм цементною сумішшю',     0.8,  0.4,  3),
      ('заповнення товстого шва напівсухою сумішшю',    0.8,  0.8,  10)
  ) AS v(name_key, old_qty, new_qty, baseline)
 WHERE n.name_key = v.name_key
   AND n.unit = 'M2'
   AND n.material_id = (SELECT id FROM material WHERE code = 'TILE_GROUT')
   AND (n.owner_id IS NULL OR n.qty_per_unit = v.old_qty);

-- A step is ~0,45 m² of tile per running metre (`default_param`), so the per-m² figure is the same
-- 300×300-class 0,25 the formula gives — the SECTION basis multiplies it out.
UPDATE material_norm n
   SET qty_per_unit = 0.25
 WHERE n.name_key IN ('укладання плитки на сходи та підсходинок',
                      'облицювання сходових маршів',
                      'облицювання радіусних сходів (без підступка)')
   AND n.unit = 'LINEAR_METER'
   AND n.material_id = (SELECT id FROM material WHERE code = 'TILE_GROUT')
   AND (n.owner_id IS NULL OR n.qty_per_unit = 0.4);

DO $$
DECLARE missed INT;
BEGIN
    SELECT count(*) INTO missed
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.material_id = (SELECT id FROM material WHERE code = 'TILE_GROUT')
       AND ((n.name_key = 'укладання плитки 300х300'                   AND n.qty_per_unit <> 0.25)
         OR (n.name_key = 'укладання плитки 100х100'                   AND n.qty_per_unit <> 0.5)
         OR (n.name_key = 'облицювання будинків клінкером «під цеглу»' AND n.baseline_param <> 10)
         OR (n.name_key = 'затирання швів від 3 мм цементною сумішшю'  AND n.qty_per_unit <> 0.4)
         OR (n.name_key = 'заповнення товстого шва напівсухою сумішшю' AND n.baseline_param IS NULL));
    IF missed <> 0 THEN
        RAISE EXCEPTION 'V146: % shipped grout norm(s) kept the old geometry', missed;
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 4. TILE ADHESIVE: the class and the notch the work actually needs (TILING §4, item 5)
--
-- V145 fixed the formats whose C1 figure no sheet allowed; these are the four it did not reach.
-- Clinker floor tile is a pressed, strongly absorbent body on an 8-10 mm notch, which is C2
-- territory, not C1's 5,2. A thin brick slip on a facade is buttered, not combed, so it needs LESS
-- than the 8,5 of a large-format floor. A pool is C2 at a 8 mm notch.
-- -------------------------------------------------------------------------------------------------
-- The material move carries the forks (review B-35) — a norm is a (material, coefficient) pair.
UPDATE material_norm n
   SET material_id = (SELECT id FROM material WHERE code = 'TILE_ADHESIVE_C2')
 WHERE n.name_key = 'укладання клінкерної підлогової плитки'
   AND n.unit = 'M2'
   AND n.material_id = (SELECT id FROM material WHERE code = 'TILE_ADHESIVE_C1')
   AND NOT EXISTS (SELECT 1 FROM material_norm o
                    WHERE o.owner_id IS NOT DISTINCT FROM n.owner_id
                      AND o.trade IS NOT DISTINCT FROM n.trade
                      AND o.name_key = n.name_key AND o.unit = n.unit
                      AND o.material_id = (SELECT id FROM material WHERE code = 'TILE_ADHESIVE_C2'));

UPDATE material_norm n
   SET qty_per_unit = v.new_qty
  FROM (VALUES
      ('укладання клінкерної підлогової плитки',      5.2, 7.0),
      ('облицювання будинків клінкером «під цеглу»',  8.5, 6.0),
      ('облицювання басейнів',                        8.5, 7.0)
  ) AS v(name_key, old_qty, new_qty)
 WHERE n.name_key = v.name_key
   AND n.unit = 'M2'
   AND n.material_id = (SELECT id FROM material WHERE code = 'TILE_ADHESIVE_C2')
   AND (n.owner_id IS NULL OR n.qty_per_unit = v.old_qty);

-- «Дикий камінь» has no format and no fixed thickness, so a flat kg/m² is a guess about the stone
-- the master happens to have bought. It is the case THICKNESS exists for: he states the bed, the
-- calculator multiplies. 1,35 kg/m² per mm is the figure V137 already uses for the two other
-- thickness-driven adhesive rows — same product, same arithmetic — and 10 mm is the bed a sandstone
-- or slate course usually needs, pre-filled visibly and never applied silently.
UPDATE material_norm n
   SET basis = 'THICKNESS',
       qty_per_unit = 1.35,
       default_param = 10
 WHERE n.name_key IN ('укладання "дикого каменю" піщаник, сланець',
                      'облицювання «диким каменем» фасаду будинку, парканів')
   AND n.unit = 'M2'
   AND n.basis = 'QUANTITY'
   AND n.material_id = (SELECT id FROM material WHERE code = 'TILE_ADHESIVE_C2')
   AND (n.owner_id IS NULL OR n.qty_per_unit = 8.5);

DO $$
DECLARE wrong INT;
BEGIN
    SELECT count(*) INTO wrong
      FROM material_norm n
      JOIN material m ON m.id = n.material_id
     WHERE n.owner_id IS NULL
       AND ((n.name_key = 'укладання клінкерної підлогової плитки'
                 AND m.code = 'TILE_ADHESIVE_C1')
         OR (n.name_key IN ('укладання "дикого каменю" піщаник, сланець',
                            'облицювання «диким каменем» фасаду будинку, парканів')
                 AND m.code LIKE 'TILE_ADHESIVE%' AND n.basis <> 'THICKNESS'));
    IF wrong <> 0 THEN
        RAISE EXCEPTION 'V146: % shipped adhesive norm(s) kept the old class or basis', wrong;
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 5. WALLPAPER GLUE WAS TWENTY TIMES TOO HIGH (PAINTER §4, items 12-13; review B-109)
--
-- `WALLPAPER_GLUE` is DRY glue sold in a 0,3 kg pack, and a pack makes ~5 l and hangs ~30 m² — so
-- ~0,009 kg of powder per m². The shipped 0,2 is a LITRE-of-paste figure wearing a kilogram's unit,
-- which bought 7 packs for a 10 m² wall.
--
-- The paste case needs no new material: `FIBERGLASS_GLUE` already IS the ready-mixed dispersive
-- glue, at 0,25 kg/m² in a 10 kg bucket, and the same bucket is what hangs heavy glass-fibre
-- wallpaper. It only needed a name that says so.
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm n
   SET qty_per_unit = 0.009
 WHERE n.unit = 'M2'
   AND n.material_id = (SELECT id FROM material WHERE code = 'WALLPAPER_GLUE')
   AND (n.owner_id IS NULL OR n.qty_per_unit = 0.2);

UPDATE material SET name = 'Клей для склополотна і склошпалер (готовий)'
 WHERE code = 'FIBERGLASS_GLUE';

-- Package sizes a master can actually carry to the till (PAINTER §4, item 14; DRYWALL §4, item 6):
-- a filler primer is a 5 l canister, a silicone cartridge is 280 ml, and glass fibre comes on a
-- 1 × 20 m roll. A package size that is wrong rounds the whole answer up to the wrong number of
-- packs — it is the last multiplication the shopping list does.
UPDATE material SET package_size = 5     WHERE code = 'PRIMER_FILLER'    AND package_size = 10;
UPDATE material SET package_size = 0.28  WHERE code = 'SEALANT_SILICONE' AND package_size = 0.3;
UPDATE material SET package_size = 20, package_unit = 'M2', package_name = 'рулон'
 WHERE code = 'FIBERGLASS' AND package_size IS NULL;

DO $$
DECLARE wrong INT;
BEGIN
    SELECT count(*) INTO wrong
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.material_id = (SELECT id FROM material WHERE code = 'WALLPAPER_GLUE')
       AND n.qty_per_unit <> 0.009;
    IF wrong <> 0 THEN
        RAISE EXCEPTION 'V146: % shipped wallpaper-glue norm(s) kept a paste figure', wrong;
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 6. PRIMER: the right product, and «once» extended to the running metre (PAINTER §4, items 2-3)
--
-- V145 settled «primer is bought once» for m² positions. Two things it could not reach:
--
--   * «Грунтовка поверхонь перед штукатуркою/армуванням» IS a priming position, so it rightly kept
--     its row — but it kept бетоноконтакт, which is the wrong product. Concrete contact is for a
--     smooth non-absorbent slab and has its own position; a wall about to be plastered or meshed
--     takes a deep primer. Same reason V145 took бетоноконтакт off the gypsum-plaster rows.
--   * Three LINEAR_METER positions prime a reveal or a box inside the painting/puttying work, while
--     «Грунтування укосів» is the standalone running-metre priming step the master prices beside
--     them. That is the m² ruling exactly, one unit over, so the rows come off.
--
-- What deliberately does NOT happen here: «Декоративна штукатурка фасаду короїд баранець» does not
-- get its quartz primer back. It is a WORK position that also consumes plaster, so V145's rule
-- reaches it like any other, and «Грунтовка поверхні кварцгрунтом» is the position that buys it —
-- which section 7 below is what finally makes answer for a builder as well as a painter.
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm n
   SET material_id = (SELECT id FROM material WHERE code = 'PRIMER_DEEP')
 WHERE n.name_key = 'грунтовка поверхонь перед штукатуркою армуванням'
   AND n.unit = 'M2'
   AND n.material_id = (SELECT id FROM material WHERE code = 'PRIMER_CONTACT')
   AND NOT EXISTS (SELECT 1 FROM material_norm o
                    WHERE o.owner_id IS NOT DISTINCT FROM n.owner_id
                      AND o.trade IS NOT DISTINCT FROM n.trade
                      AND o.name_key = n.name_key AND o.unit = n.unit
                      AND o.material_id = (SELECT id FROM material WHERE code = 'PRIMER_DEEP'));

UPDATE material_norm n
   SET qty_per_unit = 0.15
 WHERE n.name_key = 'грунтовка поверхонь перед штукатуркою армуванням'
   AND n.unit = 'M2'
   AND n.material_id = (SELECT id FROM material WHERE code = 'PRIMER_DEEP')
   AND (n.owner_id IS NULL OR n.qty_per_unit = 0.3);

DELETE FROM material_norm n
 WHERE n.unit = 'LINEAR_METER'
   AND n.material_id = (SELECT id FROM material WHERE code = 'PRIMER_DEEP')
   AND n.name_key IN ('фарбування укосів',
                      'шпаклівка коробів укосів ніш під фарбування',
                      'шпаклівка коробів, укосів, ніш та виступів під фарбування');

DO $$
DECLARE wrong INT;
BEGIN
    SELECT count(*) INTO wrong
      FROM material_norm n
      JOIN material m ON m.id = n.material_id
     WHERE n.owner_id IS NULL
       AND ((n.name_key = 'грунтовка поверхонь перед штукатуркою армуванням'
                 AND m.code <> 'PRIMER_DEEP')
         OR (n.unit = 'LINEAR_METER' AND m.code = 'PRIMER_DEEP'
                 AND n.name_key IN ('фарбування укосів',
                                    'шпаклівка коробів укосів ніш під фарбування',
                                    'шпаклівка коробів, укосів, ніш та виступів під фарбування')));
    IF wrong <> 0 THEN
        RAISE EXCEPTION 'V146: % shipped primer row(s) carry the old product or double-prime', wrong;
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 7. A NORM FILED UNDER ONE TRADE FOR A POSITION TWO TRADES SHIP (BUILDER §4; TILING §4, item 9)
--
-- `catalog_items` holds ONE row per (owner, name, type, unit), so a position two trades ship is
-- stored once (V118) — but the NORM carries a trade, and the trade is a FILTER on the answer. Ten
-- shipped positions were filed under the trade that happened to write the norm, so the other trade
-- asked for materials and got NOTHING: a builder's facade bought no primer, no mesh, no decorative
-- plaster and no facade paint; a plumber's inspection hatch bought no hatch; a builder's
-- waterproofing bought no cement.
--
-- The answer is V137's: a shared position is re-filed to `trade = NULL` — a trade-less norm answers
-- for anyone — and never DUPLICATED per trade, which would be two coefficients for one job. The
-- forks move in the same statement (review B-35), because `owner_id` is inside `ux_material_norm`
-- and a fork left under the old trade would stop hiding the row it was written to replace.
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm n
   SET trade = NULL
 WHERE n.trade IS NOT NULL
   AND (n.trade, n.name_key, n.unit) IN (
          ('DRYWALL', 'демонтаж гіпсокартонної стелі',                 'M2'),
          ('DRYWALL', 'демонтаж перегородки з гіпсокартону',           'M2'),
          ('DRYWALL', 'установка люка-ревізії простого',               'PIECE'),
          ('PAINTER', 'армування фасаду сітка перетяжка',              'M2'),
          ('PAINTER', 'грунтовка поверхні кварцгрунтом',               'M2'),
          ('PAINTER', 'декоративна штукатурка фасаду короїд баранець', 'M2'),
          ('PAINTER', 'демонтаж будівельного риштування',              'M2'),
          ('PAINTER', 'монтаж будівельного риштування',                'M2'),
          ('PAINTER', 'фарбування фасаду',                             'M2'),
          ('TILING',  'гідроізоляція сухою сумішшю',                   'M2'))
   AND NOT EXISTS (SELECT 1 FROM material_norm o
                    WHERE o.owner_id IS NOT DISTINCT FROM n.owner_id
                      AND o.trade IS NULL
                      AND o.name_key = n.name_key AND o.unit = n.unit
                      AND o.material_id IS NOT DISTINCT FROM n.material_id);

DO $$
DECLARE left_over INT;
        stranded  INT;
BEGIN
    SELECT count(*) INTO left_over
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND (n.trade, n.name_key, n.unit) IN (
              ('DRYWALL', 'демонтаж гіпсокартонної стелі',                 'M2'),
              ('DRYWALL', 'демонтаж перегородки з гіпсокартону',           'M2'),
              ('DRYWALL', 'установка люка-ревізії простого',               'PIECE'),
              ('PAINTER', 'армування фасаду сітка перетяжка',              'M2'),
              ('PAINTER', 'грунтовка поверхні кварцгрунтом',               'M2'),
              ('PAINTER', 'декоративна штукатурка фасаду короїд баранець', 'M2'),
              ('PAINTER', 'демонтаж будівельного риштування',              'M2'),
              ('PAINTER', 'монтаж будівельного риштування',                'M2'),
              ('PAINTER', 'фарбування фасаду',                             'M2'),
              ('TILING',  'гідроізоляція сухою сумішшю',                   'M2'));
    IF left_over <> 0 THEN
        RAISE EXCEPTION 'V146: % shared shipped norm(s) are still filed under one trade', left_over;
    END IF;

    SELECT count(*) INTO stranded
      FROM material_norm n
     WHERE n.owner_id IS NOT NULL
       AND n.trade IS NOT NULL
       AND (n.name_key, n.unit) IN (
              ('демонтаж гіпсокартонної стелі',                 'M2'),
              ('демонтаж перегородки з гіпсокартону',           'M2'),
              ('установка люка-ревізії простого',               'PIECE'),
              ('армування фасаду сітка перетяжка',              'M2'),
              ('грунтовка поверхні кварцгрунтом',               'M2'),
              ('декоративна штукатурка фасаду короїд баранець', 'M2'),
              ('демонтаж будівельного риштування',              'M2'),
              ('монтаж будівельного риштування',                'M2'),
              ('фарбування фасаду',                             'M2'),
              ('гідроізоляція сухою сумішшю',                   'M2'));
    IF stranded <> 0 THEN
        RAISE EXCEPTION 'V146: % owned fork(s) stayed behind under the old trade', stranded;
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 8. FIVE TILING BUNDLES NEVER PRIMED ANYTHING (TILING §4, item 9)
--
-- A default bundle is a SEQUENCE, and the sequence is what the master prices. «Ґрунтівка поверхні»
-- is the position that buys the primer for every tiling job after V145, so a bundle without it
-- prices no priming and buys no primer — while the five below all start on a screed or a fresh
-- plaster coat that has to be primed before anything is stuck to it.
--
-- Each step goes where the work actually happens: after the demolition/screed, before the
-- waterproofing or the first adhesive. Nothing is appended at the end, because the order IS the
-- content.
--
-- Forked bundles are untouched (the parent is filtered on `is_default AND owner_id IS NULL`): a
-- master who rebuilt the sequence owns it. A bundle that already carries the step is skipped, so
-- re-running the ranking cannot produce two.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE r   RECORD;
        tid UUID;
BEGIN
    FOR r IN SELECT * FROM (VALUES
                ('Басейн та мозаїка',                         1),
                ('Душова без піддону (трап, лінійний канал)', 3),
                ('Натуральний камінь та сляби',               1),
                ('Сходи плиткою',                             0),
                ('Тераса, балкон, вулиця',                    2)
             ) AS v(bundle, at_pos)
    LOOP
        SELECT id INTO tid
          FROM estimate_templates
         WHERE owner_id IS NULL AND is_default AND trade = 'TILING' AND name = r.bundle;
        IF tid IS NULL THEN
            RAISE WARNING 'V146: default bundle "%" is gone — the priming step was not added', r.bundle;
            CONTINUE;
        END IF;
        IF EXISTS (SELECT 1 FROM estimate_template_items
                    WHERE template_id = tid
                      AND lower(trim(name)) = 'ґрунтівка поверхні') THEN
            CONTINUE;
        END IF;
        UPDATE estimate_template_items
           SET sort_order = sort_order + 1
         WHERE template_id = tid AND sort_order >= r.at_pos;
        INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
        VALUES (gen_random_uuid(), tid, 'Ґрунтівка поверхні', 'WORK', 'M2', r.at_pos);
    END LOOP;
END $$;

-- «Підлога великоформатом, керамограніт» already has the step, one line too late: a self-levelling
-- floor is poured ONTO a primed base, so priming belongs before it, not after.
WITH pair AS (
    SELECT g.id AS primer_id, g.sort_order AS primer_ord,
           s.id AS screed_id, s.sort_order AS screed_ord
      FROM estimate_templates t
      JOIN estimate_template_items g ON g.template_id = t.id
      JOIN estimate_template_items s ON s.template_id = t.id
     WHERE t.owner_id IS NULL AND t.is_default AND t.trade = 'TILING'
       AND t.name = 'Підлога великоформатом, керамограніт'
       AND lower(trim(g.name)) = 'ґрунтівка поверхні'
       AND lower(trim(s.name)) = 'влаштування наливної підлоги'
       AND g.sort_order = s.sort_order + 1
)
UPDATE estimate_template_items i
   SET sort_order = CASE WHEN i.id = p.primer_id THEN p.screed_ord ELSE p.primer_ord END
  FROM pair p
 WHERE i.id IN (p.primer_id, p.screed_id);

DO $$
DECLARE missing INT;
BEGIN
    SELECT count(*) INTO missing
      FROM estimate_templates t
     WHERE t.owner_id IS NULL AND t.is_default AND t.trade = 'TILING'
       AND t.name IN ('Басейн та мозаїка', 'Душова без піддону (трап, лінійний канал)',
                      'Натуральний камінь та сляби', 'Сходи плиткою', 'Тераса, балкон, вулиця')
       AND (SELECT count(*) FROM estimate_template_items i
             WHERE i.template_id = t.id
               AND lower(trim(i.name)) = 'ґрунтівка поверхні') <> 1;
    IF missing <> 0 THEN
        RAISE EXCEPTION 'V146: % tiling bundle(s) do not carry exactly one priming step', missing;
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 9. A «КОМПЛЕКС» POSITION INSIDE A BUNDLE OF ITS OWN PARTS (BUILDER §4, items 1-2)
--
-- «Утеплення фасаду» priced «Утеплення фасада комплекс пінопласт сітка декор штукатурка» AND the
-- four positions that make it up, so the whole facade was charged twice. «Покрівля двоскатна» did
-- the same with «Монтаж двоскатного даху комплекс». A rollup belongs in a bundle of its own, never
-- beside its own parts.
--
-- Removing the roof rollup leaves the bundle with no rafter work: the position that should take its
-- place («Монтаж кроквяної системи») does not exist in the library yet, and a catalog addition is a
-- later batch. Charging a roof twice is the error that costs money today; a missing line the master
-- adds himself is not.
--
-- «Кладка цегла» mixed mortar WITH gravel, which is concrete, not masonry mortar — the library has
-- the right position and the bundle simply named the wrong one.
-- -------------------------------------------------------------------------------------------------
DELETE FROM estimate_template_items i
 USING estimate_templates t
 WHERE t.id = i.template_id
   AND t.owner_id IS NULL AND t.is_default AND t.trade = 'BUILDER'
   AND ((t.name = 'Утеплення фасаду'
             AND lower(trim(i.name)) = 'утеплення фасада комплекс пінопласт сітка декор штукатурка')
     OR (t.name = 'Покрівля двоскатна'
             AND lower(trim(i.name)) = 'монтаж двоскатного даху комплекс'));

UPDATE estimate_template_items i
   SET name = 'Приготування розчину для кладки без щебня'
  FROM estimate_templates t
 WHERE t.id = i.template_id
   AND t.owner_id IS NULL AND t.is_default AND t.trade = 'BUILDER'
   AND t.name = 'Кладка цегла'
   AND lower(trim(i.name)) = 'приготування розчину з щебнем';

-- `sort_order` IS the content, so a deletion must leave no hole behind it.
WITH ordered AS (
    SELECT i.id,
           row_number() OVER (PARTITION BY i.template_id ORDER BY i.sort_order, i.name) - 1 AS pos
      FROM estimate_template_items i
      JOIN estimate_templates t ON t.id = i.template_id
     WHERE t.owner_id IS NULL AND t.is_default AND t.trade = 'BUILDER'
       AND t.name IN ('Утеплення фасаду', 'Покрівля двоскатна')
)
UPDATE estimate_template_items i
   SET sort_order = o.pos
  FROM ordered o
 WHERE o.id = i.id AND i.sort_order <> o.pos;

DO $$
DECLARE doubled INT;
BEGIN
    SELECT count(*) INTO doubled
      FROM estimate_template_items i
      JOIN estimate_templates t ON t.id = i.template_id
     WHERE t.owner_id IS NULL AND t.is_default AND t.trade = 'BUILDER'
       AND (lower(trim(i.name)) IN ('утеплення фасада комплекс пінопласт сітка декор штукатурка',
                                    'монтаж двоскатного даху комплекс')
         OR (t.name = 'Кладка цегла' AND lower(trim(i.name)) = 'приготування розчину з щебнем'));
    IF doubled <> 0 THEN
        RAISE EXCEPTION 'V146: % builder bundle line(s) still double-count or mix the wrong mortar', doubled;
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 10. A SYSTEM IS STARTED AFTER IT HAS HELD PRESSURE, NOT BEFORE (PLUMBING §4, item 1)
--
-- Six bundles ran «Запуск системи …» and then «Перевірка системи … тиском». That is the test done
-- on a system already filled and running, which is not a pressure test at all — the point of
-- опресування is to find the leak while the pipe is still exposed and nothing downstream is at
-- risk. The two lines swap.
--
-- In «САНТЕХНІКА» the chase is closed between them, which is worse: the water pipe was buried
-- before it had been tested. After the swap the test is still behind «Заробка штроб», so a second
-- swap puts it in front of it. Order ends as: lay → test → close the chase → start.
-- -------------------------------------------------------------------------------------------------
WITH pairs AS (
    SELECT z.id AS start_id, z.sort_order AS start_ord,
           p.id AS test_id,  p.sort_order AS test_ord
      FROM estimate_templates t
      JOIN estimate_template_items z ON z.template_id = t.id
      JOIN estimate_template_items p ON p.template_id = t.id
     WHERE t.owner_id IS NULL AND t.is_default AND t.trade = 'PLUMBING'
       AND z.name LIKE 'Запуск системи %'
       AND p.name LIKE 'Перевірка системи % тиском'
       AND p.sort_order = z.sort_order + 1
       -- the same system: «опалення» started must be «опалення» tested
       AND split_part(z.name, ' ', 3) = split_part(p.name, ' ', 3)
)
UPDATE estimate_template_items i
   SET sort_order = CASE WHEN i.id = pr.start_id THEN pr.test_ord ELSE pr.start_ord END
  FROM pairs pr
 WHERE i.id IN (pr.start_id, pr.test_id);

WITH pairs AS (
    SELECT c.id AS chase_id, c.sort_order AS chase_ord,
           p.id AS test_id,  p.sort_order AS test_ord
      FROM estimate_templates t
      JOIN estimate_template_items c ON c.template_id = t.id
      JOIN estimate_template_items p ON p.template_id = t.id
     WHERE t.owner_id IS NULL AND t.is_default AND t.trade = 'PLUMBING'
       AND c.name LIKE 'Заробка штроб%'
       AND p.name LIKE 'Перевірка системи % тиском'
       AND p.sort_order = c.sort_order + 1
)
UPDATE estimate_template_items i
   SET sort_order = CASE WHEN i.id = pr.chase_id THEN pr.test_ord ELSE pr.chase_ord END
  FROM pairs pr
 WHERE i.id IN (pr.chase_id, pr.test_id);

DO $$
DECLARE backwards INT;
BEGIN
    SELECT count(*) INTO backwards
      FROM estimate_templates t
      JOIN estimate_template_items z ON z.template_id = t.id
      JOIN estimate_template_items p ON p.template_id = t.id
     WHERE t.owner_id IS NULL AND t.is_default AND t.trade = 'PLUMBING'
       AND z.name LIKE 'Запуск системи %'
       AND p.name LIKE 'Перевірка системи % тиском'
       AND split_part(z.name, ' ', 3) = split_part(p.name, ' ', 3)
       AND p.sort_order > z.sort_order;
    IF backwards <> 0 THEN
        RAISE EXCEPTION 'V146: % plumbing bundle(s) still start a system before testing it', backwards;
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 11. A bundle line resolves its price off the master's own catalog by NAME (V112), so a name this
-- migration wrote that no library position matches would apply at 0 ₴ and say nothing. This is a
-- WARNING, not an exception: a catalog position is something an admin can rename between two
-- deploys, and the integration tests are what keep it honest in CI (review B-50).
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE orphans INT;
BEGIN
    SELECT count(*) INTO orphans
      FROM (VALUES ('Ґрунтівка поверхні'), ('Приготування розчину для кладки без щебня')) AS v(name)
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates c
                        WHERE lower(trim(c.name)) = lower(trim(v.name)));
    IF orphans <> 0 THEN
        RAISE WARNING 'V146: % bundle line name(s) match no catalog position — they would apply at 0 UAH', orphans;
    END IF;
END $$;
