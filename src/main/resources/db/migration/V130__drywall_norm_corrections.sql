-- =================================================================================================
-- V130 - four corrections to the V127 DRYWALL norms, found by checking them against the Ukrainian
-- consumption tables for Knauf systems (dealer compilations of the handbook figures; Knauf itself
-- publishes no Ukrainian calculator - knauf.ua redirects to knauf.com/uk-UA, which has none).
--
-- The master asked for the audit and then for the fix: «прав норми».
--
-- Most of V127 held: CD 2.9 m/m2 on a ceiling and 2.0 on a wall, CW 2.0 per m2 of partition face,
-- finishing putty 1.2 kg/m2 and primer 0.1-0.12 l/m2 all match the tables exactly. The screw counts
-- LOOK 2x apart and are not: ours are per m2 of BOARD, the tables' per m2 of partition FACE.
--
-- What was actually wrong - two missing materials, one missing fastener, one number:
--
--   1. NO CONNECTORS AT ALL for a single-level ceiling frame. Two CD profiles that cross need a
--      crab, and a run longer than the profile needs an extender; V127 shipped neither, in the
--      dictionary or in a norm, so every ceiling calculation silently under-bought the frame. The
--      tables give 1.7 crabs and 0.2 extenders per m2 (consistent with their own CD 2.9, which is
--      the figure we already agreed with).
--
--   2. NO HANGER ON A WALL FRAME. «Монтаж гіпсокартону на стіни» normed CD but nothing to fix it to
--      the wall with; a ceiling got its 0.7 підвіс and a wall got nothing. 1.3 шт/m2 (tables: 1.32
--      universal bracket) - higher than a ceiling because the step down a wall is tighter.
--
--   3. NO DOWEL ON A PARTITION. UW is fixed to floor and ceiling with a дюбель-цвях and all four
--      partition positions were missing it. 1.5 шт per m2 of face = 0.75 per m2 of sheathing.
--
--   4. UW WAS ~50 % HIGH: 0.5 per m2 of sheathing is 1.0 per m2 of face, against the tables' 0.7.
--      Arithmetic: a partition of length L and height 3 m needs 2L of track (floor + ceiling) for
--      3L of face, i.e. 0.67 -> 0.7 per m2 of face -> 0.35 per m2 of sheathing. The over-buy came
--      from halving a per-face figure that had already been halved.
--
-- The BASIS is unchanged and is the reason every partition number here is half a handbook one:
-- V127 decision 1 (master's ruling, 2026-09-08) - the m2 the master typed on a «перегородки
-- 2 сторони» line is the SHEATHING area with both faces already in it, and the calculator never
-- reinterprets a quantity he typed. One basis for every norm on the line.
--
-- Every figure is still an ORIENTATION VALUE the master corrects on the result screen, and his own
-- coefficient wins: qty_per_unit is updated only WHERE owner_id IS NULL, so a norm anyone has
-- already forked (V126's owner_id-inside-the-natural-key, MaterialNormService.saveOwn) keeps his
-- number. sort_order IS shifted on forks too - it is presentation, not his coefficient, and leaving
-- a fork behind would interleave it oddly with the defaults around it.
--
-- Deliberately NOT changed, and why:
--   * Клей гіпсовий монтажний 5.0 kg/m2 - the tables say 3.5 for Perlfix, Knauf's own datasheet
--     ~5.0. Ours sits at the manufacturer's figure; a dealer table is weaker evidence than that.
--   * Шпаклівка для стиків 0.4 kg/м.п. - on the high side (handbook ~0.3), but it is bought in
--     25 kg bags and the calculator rounds UP to a bag, so on any realistic joint length the
--     difference does not change what the master carries out of the shop. Left for his word.
--   * NO new catalog position. «Монтаж дворівневої / багаторівневої стелі» was considered and
--     REJECTED in V120 (that job is a flat ceiling plus a короб by the linear metre, both of which
--     we already sell; one m2 position on top of them double-bills), and V116's «Стеля з
--     гіпсокартону» bundle already sequences exactly that pair. What a multi-level ceiling really
--     hits is the короб positions having no norm at all - they need a section (w+h) the position
--     name cannot carry, which is the open «box / slope / niche parameters» question from the §19
--     audit, not a missing position.
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 1. Two dictionary rows. Same shape as V127's fasteners: no price, no owner (V81), sold by the
--    hundred, and named for the job rather than a system code (a краб is what the merchant's shelf
--    says; CD is a profile designation we already use, not a brand).
-- -------------------------------------------------------------------------------------------------
INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    (gen_random_uuid(), 'CONNECTOR_CRAB',      'З''єднувач однорівневий (краб)', NULL, 'PIECE', 100, 'PIECE', 'упаковка'),
    (gen_random_uuid(), 'PROFILE_CD_EXTENDER', 'Подовжувач профілю CD',          NULL, 'PIECE', 100, 'PIECE', 'упаковка');

-- -------------------------------------------------------------------------------------------------
-- 2. Correction 4 - the UW coefficient. Four positions, defaults only.
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm n
   SET qty_per_unit = 0.35
  FROM material m
 WHERE m.id = n.material_id
   AND m.code = 'PROFILE_UW'
   AND n.owner_id IS NULL
   AND n.qty_per_unit = 0.5;

-- -------------------------------------------------------------------------------------------------
-- 3. Make room for the new frame rows. The crab and the extender belong with the profiles they
--    join, and the wall hanger with the frame it holds - not appended after the fasteners.
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm
   SET sort_order = sort_order + 2
 WHERE name_key IN ('монтаж гіпсокартону на стелю рівну',
                    'монтаж гіпсокартону на стелю зі скосами',
                    'каркасна звукоізоляція (гкл в два слоя) стелі')
   AND unit = 'M2'
   AND sort_order >= 4;

UPDATE material_norm
   SET sort_order = sort_order + 1
 WHERE name_key IN ('монтаж гіпсокартону на стіни',
                    'каркасна звукоізоляція (гкл в два слоя) стін')
   AND unit = 'M2'
   AND sort_order >= 4;

-- -------------------------------------------------------------------------------------------------
-- 4. Corrections 1-3 - the three missing materials.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'DRYWALL', v.name_key, v.unit, m.id, v.qty, 'QUANTITY', v.ord
FROM (VALUES
    -- 1. Ceiling frame: a crossing needs a crab, a long run an extender.
    ('монтаж гіпсокартону на стелю рівну',             'M2', 'CONNECTOR_CRAB',      1.7,  4),
    ('монтаж гіпсокартону на стелю рівну',             'M2', 'PROFILE_CD_EXTENDER', 0.2,  5),
    ('монтаж гіпсокартону на стелю зі скосами',        'M2', 'CONNECTOR_CRAB',      1.7,  4),
    ('монтаж гіпсокартону на стелю зі скосами',        'M2', 'PROFILE_CD_EXTENDER', 0.2,  5),
    ('каркасна звукоізоляція (гкл в два слоя) стелі',  'M2', 'CONNECTOR_CRAB',      1.7,  4),
    ('каркасна звукоізоляція (гкл в два слоя) стелі',  'M2', 'PROFILE_CD_EXTENDER', 0.2,  5),

    -- 2. Wall frame: the CD had nothing holding it to the wall.
    ('монтаж гіпсокартону на стіни',                   'M2', 'HANGER_DIRECT',       1.3,  4),
    ('каркасна звукоізоляція (гкл в два слоя) стін',   'M2', 'HANGER_DIRECT',       1.3,  4),

    -- 3. Partition: the дюбель-цвях that fixes UW to floor and ceiling. Per m2 of SHEATHING, so
    --    half the 1.5 the tables quote per m2 of face - same halving as CW/UW above.
    ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар',  'M2', 'DOWEL_NAIL', 0.75, 6),
    ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари', 'M2', 'DOWEL_NAIL', 0.75, 7),
    ('монтаж радіусних конструкцій (перегородки) із гіпсокартону в 1 шар',  'M2', 'DOWEL_NAIL', 0.75, 5),
    ('монтаж радіусних конструкцій (перегородки) із гіпсокартону в 2 шари', 'M2', 'DOWEL_NAIL', 0.75, 6)
) AS v(name_key, unit, code, qty, ord)
JOIN material m ON m.code = v.code;

-- -------------------------------------------------------------------------------------------------
-- 5. Self-checks, in V127's spirit: a norm whose name_key matches no live position is INVISIBLE at
--    runtime - the position lands in the coverage report and the master reads our typo as a gap in
--    our data.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    v_bad  int;
    v_miss text;
BEGIN
    -- 5a. Same check V127 section 5 runs, over the keys this migration wrote to.
    SELECT string_agg(DISTINCT n.name_key || ' [' || n.unit || ']', ', ')
      INTO v_miss
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.trade = 'DRYWALL'
       AND NOT EXISTS (
           SELECT 1
             FROM catalog_templates t
            WHERE t.trade = 'DRYWALL'
              AND t.type = 'WORK'
              AND t.unit = n.unit
              AND lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) = n.name_key
       );
    IF v_miss IS NOT NULL THEN
        RAISE EXCEPTION 'V130: material norms reference DRYWALL positions that do not exist: %', v_miss;
    END IF;

    -- 5b. The UW correction landed on all four partition positions.
    SELECT count(*) INTO v_bad
      FROM material_norm n JOIN material m ON m.id = n.material_id
     WHERE m.code = 'PROFILE_UW' AND n.owner_id IS NULL AND n.qty_per_unit <> 0.35;
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V130: % default UW norm(s) still carry the old coefficient', v_bad;
    END IF;

    -- 5c. Every frame position now buys what holds it together. Counted as (position, material)
    --     pairs so a missing row in any one of them fails the migration rather than the field.
    SELECT count(*) INTO v_bad FROM (VALUES
        ('монтаж гіпсокартону на стелю рівну',                                  'CONNECTOR_CRAB'),
        ('монтаж гіпсокартону на стелю рівну',                                  'PROFILE_CD_EXTENDER'),
        ('монтаж гіпсокартону на стелю зі скосами',                             'CONNECTOR_CRAB'),
        ('монтаж гіпсокартону на стелю зі скосами',                             'PROFILE_CD_EXTENDER'),
        ('каркасна звукоізоляція (гкл в два слоя) стелі',                       'CONNECTOR_CRAB'),
        ('каркасна звукоізоляція (гкл в два слоя) стелі',                       'PROFILE_CD_EXTENDER'),
        ('монтаж гіпсокартону на стіни',                                        'HANGER_DIRECT'),
        ('каркасна звукоізоляція (гкл в два слоя) стін',                        'HANGER_DIRECT'),
        ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар',   'DOWEL_NAIL'),
        ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари',  'DOWEL_NAIL'),
        ('монтаж радіусних конструкцій (перегородки) із гіпсокартону в 1 шар',   'DOWEL_NAIL'),
        ('монтаж радіусних конструкцій (перегородки) із гіпсокартону в 2 шари',  'DOWEL_NAIL')
    ) AS want(name_key, code)
    WHERE NOT EXISTS (
        SELECT 1 FROM material_norm n JOIN material m ON m.id = n.material_id
         WHERE n.owner_id IS NULL AND n.name_key = want.name_key AND n.unit = 'M2'
           AND m.code = want.code);
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V130: % (position, material) pair(s) were not written', v_bad;
    END IF;

    -- 5d. Two materials on one position sharing a rank would order the shopping list arbitrarily.
    SELECT count(*) INTO v_bad FROM (
        SELECT name_key, unit, sort_order
          FROM material_norm
         WHERE owner_id IS NULL AND trade = 'DRYWALL' AND material_id IS NOT NULL
         GROUP BY name_key, unit, sort_order HAVING count(*) > 1) d;
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V130: % (position, rank) pair(s) are shared by two materials', v_bad;
    END IF;
END $$;
