-- V133 - three unrelated fixes from the 2026-09 code review, plus the drywall norm figures
-- re-derived from manufacturer datasheets.
--
-- Sections 1 and 2 are schema (review items B-17 and B-27); sections 3-6 are norm DATA, corrected
-- against Knauf / Rigips / Siniat system sheets. They travel together because they are one review
-- round, not because they are related.
--
-- -------------------------------------------------------------------------------------------------
-- WHY THE NUMBERS MOVE, AND THE TWO RULES THAT DECIDE THEM
--
-- V127 seeded fastener figures from a general handbook. Reading the actual system sheets, the
-- screws were the one family that was wrong everywhere, and wrong in the same direction: 30 per m2
-- was roughly a factory-floor figure for screwing at 100 mm on every rib. The manufacturers screw
-- the FIELD of a board at 250 mm and only its EDGES denser, and that halves the count.
--
-- Rule 1 (V127 decision 1, master's ruling 2026-09-08) still governs every partition figure: the
-- m2 the master types on a «перегородки 2 сторони» position is SHEATHING area, both faces already
-- in it. Every manufacturer figure below is quoted PER M2 OF PARTITION, so it is halved for us.
-- The partition numbers therefore look implausibly small beside the wall numbers; they are not.
--
-- Rule 2: a norm's unit is the POSITION's unit and nothing is converted (V127 decision 2). The
-- primer added in section 6 is 0,15 LITRE per M2 - a LITRE material on an M2 norm is correct.
--
-- -------------------------------------------------------------------------------------------------
-- TWO CONFLICTS THE SOURCES DO NOT SETTLE, AND WHAT WE CHOSE
--
-- (a) The ceiling frame. Knauf D113 hangs a two-level frame on 0,7 direct hangers per m2 BECAUSE
--     it also carries 1,5 cross connectors per m2; Siniat's single-level frame has no connectors
--     and needs 3,0 hangers. Both are correct, and mixing them buys the ceiling twice. V130 has
--     already shipped the Knauf shape in full (0,7 HANGER_DIRECT + 1,7 CONNECTOR_CRAB + 0,2
--     PROFILE_CD_EXTENDER + 1,6 DOWEL_NAIL), so it is internally consistent and it STAYS. Do not
--     raise the hangers to 3,0 without deleting the connectors in the same migration.
--
-- (b) Partition DOWEL_NAIL. The sheets give 0,9 (Siniat) to 1,5 (Rigips) per m2 of partition, i.e.
--     0,45-0,75 for us. V130 shipped 0,75, the top of the band; a deeper reading argues for 0,5.
--     Both are inside the band and dowels are bought in 100-piece packs that the calculator rounds
--     up to anyway, so the shipped figure stays rather than churn every master's list for a
--     difference no shop counter would notice.
--
-- Left alone deliberately: the радіусні конструкції SCREW_TN25 35. No manufacturer publishes a
-- figure for a bent partition; 35 is denser than the straight 12 on purpose, because a curve is
-- screwed at roughly 100 mm rather than 250 mm. It is our own estimate and stays flagged as one.
-- -------------------------------------------------------------------------------------------------


-- -------------------------------------------------------------------------------------------------
-- 1. B-17: the calculator labels a result with the material's `unit` but computes it in the
--    PACKAGE's unit (MaterialCalculatorService#line divides by package_size and never reads
--    package_unit). Every row V126/V127/V130/V131 seeded happens to have the two agree, so the
--    honest fix is to make that a rule instead of a coincidence - the day someone adds a KG
--    material sold in 10-LITRE buckets, the migration fails instead of the shopping list lying.
-- -------------------------------------------------------------------------------------------------
ALTER TABLE material ADD CONSTRAINT material_package_unit_matches_check
    CHECK (package_unit IS NULL OR package_unit = unit);

COMMENT ON COLUMN material.package_unit IS
    'Always equal to `unit` (V133 CHECK). The calculator rounds up to `package_size` and then '
    'labels the result with `unit`, so a package measured in anything else would be silently '
    'mislabelled. Kept as its own column because a future packaging model may need it to diverge - '
    'at which point the calculator must learn to convert FIRST.';


-- -------------------------------------------------------------------------------------------------
-- 2. B-27: custom photo-folder names are case-sensitive while the reserved aliases are not, so a
--    master who types «фасад» after making «Фасад» gets a second folder and his photos split in
--    two. Fold the twins onto the oldest spelling, then let a functional unique index enforce it.
--
--    Photos reference folders BY NAME (V111), so the fold has to rewrite project_photo too, and it
--    has to happen before the delete - a photo left pointing at a deleted folder name shows up in
--    a folder the master can no longer see.
-- -------------------------------------------------------------------------------------------------

-- 2a. Move every photo onto the surviving spelling of its folder.
UPDATE project_photo p
   SET folder = c.keep_name
  FROM project_photo_folder f
  JOIN (SELECT DISTINCT ON (project_id, lower(btrim(name)))
               project_id,
               lower(btrim(name)) AS norm_name,
               id                 AS keep_id,
               btrim(name)        AS keep_name
          FROM project_photo_folder
         ORDER BY project_id, lower(btrim(name)), created_at, id) c
    ON c.project_id = f.project_id
   AND c.norm_name = lower(btrim(f.name))
 WHERE p.project_id = f.project_id
   AND p.folder = f.name
   AND p.folder <> c.keep_name;

-- 2b. Drop the twins. The oldest row wins: it is the one the master made first and the one whose
--     spelling his photos already carried.
DELETE FROM project_photo_folder f
 USING (SELECT DISTINCT ON (project_id, lower(btrim(name)))
               project_id,
               lower(btrim(name)) AS norm_name,
               id                 AS keep_id
          FROM project_photo_folder
         ORDER BY project_id, lower(btrim(name)), created_at, id) c
 WHERE c.project_id = f.project_id
   AND c.norm_name = lower(btrim(f.name))
   AND f.id <> c.keep_id;

-- 2c. Survivors keep their case but lose stray padding, so the index key and the stored name agree.
UPDATE project_photo_folder SET name = btrim(name) WHERE name <> btrim(name);

-- 2d. The constraint the bug lived in, replaced by one that cannot be fooled by case or padding.
ALTER TABLE project_photo_folder DROP CONSTRAINT project_photo_folder_uk;
CREATE UNIQUE INDEX ux_project_photo_folder_name
    ON project_photo_folder (project_id, lower(btrim(name)));

COMMENT ON COLUMN project_photo_folder.name IS
    'The master''s own spelling, preserved for display. Identity is lower(btrim(name)) per object '
    '(V133 ux_project_photo_folder_name), so «Фасад» and «фасад» are ONE folder and a lookup that '
    'compares with = instead of ignoring case will start minting twins again.';


-- -------------------------------------------------------------------------------------------------
-- 3. SCREW_TN25 / SCREW_TN35 on the QUANTITY-based sheathing positions.
--
--    Sources, per m2 of BOARD unless noted:
--      стіни (single layer on a frame)      Knauf W623 14; Rigips 3.21.10 12-14      -> 14
--      стеля рівна / зі скосами             Knauf D113 25, D111/D116 17; Siniat 18-19;
--                                           Rigips 17                                -> 20
--      перегородки в 1 шар                  24 per m2 of PARTITION (Rigips 3.40.01/02,
--                                           Siniat 75A50) -> halved by rule 1        -> 12
--      перегородки в 2 шари                 8-10 + 24-28 per m2 of PARTITION
--                                           (Rigips 3.41.012, Siniat 125A75)         -> 5 / 12
--      звукоізоляція стін (2 x 12,5)        Knauf W623 two-layer                      -> 7 / 14
--      звукоізоляція стелі (2 x 12,5)       Knauf D112 two-layer                      -> 9 / 17
--
--    The inner layer of a two-layer build takes FEWER screws than a single layer: it is only
--    tacked, the outer layer and its longer TN35 do the holding. That is why 5 and 7 are not typos.
--
--    owner_id IS NULL on every statement below: a master who corrected a coefficient owns his fork
--    (V126 put owner_id inside ux_material_norm for exactly this), and a data fix must never
--    overwrite it.
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm n
   SET qty_per_unit = v.qty
  FROM (VALUES
    ('монтаж гіпсокартону на стіни',                                        'SCREW_TN25', 14),
    ('монтаж гіпсокартону на стелю рівну',                                  'SCREW_TN25', 20),
    ('монтаж гіпсокартону на стелю зі скосами',                             'SCREW_TN25', 20),
    ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар',  'SCREW_TN25', 12),
    ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари', 'SCREW_TN25',  5),
    ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари', 'SCREW_TN35', 12),
    ('каркасна звукоізоляція (гкл в два слоя) стін',                        'SCREW_TN25',  7),
    ('каркасна звукоізоляція (гкл в два слоя) стін',                        'SCREW_TN35', 14),
    ('каркасна звукоізоляція (гкл в два слоя) стелі',                       'SCREW_TN25',  9),
    ('каркасна звукоізоляція (гкл в два слоя) стелі',                       'SCREW_TN35', 17)
  ) AS v(name_key, code, qty)
  JOIN material m ON m.code = v.code
 WHERE n.owner_id IS NULL
   AND n.name_key = v.name_key
   AND n.unit = 'M2'
   AND n.material_id = m.id;


-- -------------------------------------------------------------------------------------------------
-- 4. The same correction on V131's SECTION-based box and niche positions. A короб is sheathed with
--    the same board at the same spacing as a ceiling, so it takes the same per-m2 rate: 30 -> 20.
--    The радіусний box keeps its 40/28 ratio to the straight one for the reason in the header -
--    a bent face is screwed denser - so it scales with it rather than to a source of its own.
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm n
   SET qty_per_unit = v.qty
  FROM (VALUES
    ('монтаж короба (прямого) із гіпсокартону по периметру стелі',    20),
    ('монтаж короба (радіусного) із гіпсокартону по периметру стелі', 28),
    ('монтаж ніші під прихований карниз короб під комунікації',       20)
  ) AS v(name_key, qty)
  JOIN material m ON m.code = 'SCREW_TN25'
 WHERE n.owner_id IS NULL
   AND n.name_key = v.name_key
   AND n.basis = 'SECTION'
   AND n.material_id = m.id;


-- -------------------------------------------------------------------------------------------------
-- 5. Joint filler. Rigips 0,18, Siniat UA 0,25, ready-mixed Semin/Kreisel pastes 0,36 kg per linear
--    metre of joint. V127 carried 0,4 and V130 kept it on the argument that the stuff is bought in
--    25 kg bags and the calculator rounds up anyway - true for one room, wrong for a flat, where
--    the difference is a whole bag. 0,3 is the middle of the published band.
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm n
   SET qty_per_unit = 0.3
  FROM material m
 WHERE n.owner_id IS NULL
   AND m.code = 'PUTTY_JOINT'
   AND n.material_id = m.id
   AND n.unit = 'LINEAR_METER'
   AND n.name_key IN ('заповнення та армування стиків гкл',
                      'заповнення стиків гкл паперовою стрічкою високої щільності');


-- -------------------------------------------------------------------------------------------------
-- 6. Two materials the frame positions consume and the dictionary never listed.
--
--    6a. LN 3,5x11 is the metal-to-metal screw that fixes a hanger or a connector to the profile.
--        Every frame we sell needs it and no position bought a single one, which is the kind of gap
--        a master only finds at the top of a ladder. The figures follow OUR shipped frame (header
--        note (a)), not a datasheet's, because the datasheet's frame is a different one:
--          walls          1,3 hangers x 2 screws                     = 2,6  -> 3
--          ceilings       0,7 hangers x 2 + 1,7 connectors x 4       = 8,2  -> 8
--        Change the hangers or the connectors and these must be recomputed in the same migration.
--
--    6b. «Монтаж гіпсокартону на клей» glues board straight to the wall. Every adhesive datasheet
--        (Knauf Perlfix K465 above all) requires a primed substrate first; the glue was seeded
--        without it, so the list told the master to buy adhesive for an unprepared wall.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    (gen_random_uuid(), 'SCREW_LN', 'Саморіз LN 3,5×11', NULL, 'PIECE', 1000, 'PIECE', 'упаковка');

-- A new norm is appended after whatever the position already ranks last, so the shopping list keeps
-- its build order and no existing row has to be renumbered (V130 had to shift ranks and it is the
-- fiddliest part of that migration).
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'DRYWALL', v.name_key, 'M2', m.id, v.qty, 'QUANTITY',
       (SELECT max(x.sort_order) + 1
          FROM material_norm x
         WHERE x.owner_id IS NULL AND x.name_key = v.name_key AND x.unit = 'M2')
FROM (VALUES
    ('монтаж гіпсокартону на стіни',                   'SCREW_LN',    3),
    ('каркасна звукоізоляція (гкл в два слоя) стін',   'SCREW_LN',    3),
    ('монтаж гіпсокартону на стелю рівну',             'SCREW_LN',    8),
    ('монтаж гіпсокартону на стелю зі скосами',        'SCREW_LN',    8),
    ('каркасна звукоізоляція (гкл в два слоя) стелі',  'SCREW_LN',    8),
    ('монтаж гіпсокартону на клей',                    'PRIMER_DEEP', 0.15)
) AS v(name_key, code, qty)
JOIN material m ON m.code = v.code;


-- -------------------------------------------------------------------------------------------------
-- 7. Self-checks. A data-only migration has no unit test of its own - these run at apply time
--    against the real schema, so a green Testcontainers boot IS the assertion.
--
--    Review item B-30(b): a check that guards DATA raises an EXCEPTION and stops the deploy; a
--    check that only guards our own WORDING raises a WARNING. An admin renaming a catalog category
--    between two deploys must not be able to stop Flyway.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    v_bad  int;
    v_miss text;
BEGIN
    -- 7a. V127/V130's check over the keys this migration touched: a norm whose name_key matches no
    --     live position is INVISIBLE at runtime, and the master reads our typo as a gap in our data.
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
              AND lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'),
                                              '( ', '('), ' )', ')'))) = n.name_key);
    IF v_miss IS NOT NULL THEN
        RAISE EXCEPTION 'V133: material norms reference DRYWALL positions that do not exist: %', v_miss;
    END IF;

    -- 7b. Every screw correction landed. A name_key typo would leave the old figure in place and
    --     nothing else would look wrong - the list would simply keep over-buying.
    SELECT count(*) INTO v_bad FROM (VALUES
        ('монтаж гіпсокартону на стіни',                                        'SCREW_TN25', 14),
        ('монтаж гіпсокартону на стелю рівну',                                  'SCREW_TN25', 20),
        ('монтаж гіпсокартону на стелю зі скосами',                             'SCREW_TN25', 20),
        ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар',  'SCREW_TN25', 12),
        ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари', 'SCREW_TN25',  5),
        ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари', 'SCREW_TN35', 12),
        ('каркасна звукоізоляція (гкл в два слоя) стін',                        'SCREW_TN25',  7),
        ('каркасна звукоізоляція (гкл в два слоя) стін',                        'SCREW_TN35', 14),
        ('каркасна звукоізоляція (гкл в два слоя) стелі',                       'SCREW_TN25',  9),
        ('каркасна звукоізоляція (гкл в два слоя) стелі',                       'SCREW_TN35', 17)
    ) AS want(name_key, code, qty)
    WHERE NOT EXISTS (
        SELECT 1 FROM material_norm n JOIN material m ON m.id = n.material_id
         WHERE n.owner_id IS NULL AND n.name_key = want.name_key AND n.unit = 'M2'
           AND m.code = want.code AND n.qty_per_unit = want.qty);
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V133: % screw correction(s) did not land', v_bad;
    END IF;

    -- 7c. The box correction landed on all three SECTION positions.
    SELECT count(*) INTO v_bad
      FROM material_norm n JOIN material m ON m.id = n.material_id
     WHERE n.owner_id IS NULL AND m.code = 'SCREW_TN25' AND n.basis = 'SECTION'
       AND n.qty_per_unit NOT IN (20, 28);
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V133: % box screw norm(s) still carry the old coefficient', v_bad;
    END IF;

    -- 7d. Both new materials found a position. A missing code drops rows silently through the JOIN,
    --     which is how a frame ends up with no screws holding it together.
    SELECT count(*) INTO v_bad FROM (VALUES
        ('монтаж гіпсокартону на стіни',                   'SCREW_LN'),
        ('каркасна звукоізоляція (гкл в два слоя) стін',   'SCREW_LN'),
        ('монтаж гіпсокартону на стелю рівну',             'SCREW_LN'),
        ('монтаж гіпсокартону на стелю зі скосами',        'SCREW_LN'),
        ('каркасна звукоізоляція (гкл в два слоя) стелі',  'SCREW_LN'),
        ('монтаж гіпсокартону на клей',                    'PRIMER_DEEP')
    ) AS want(name_key, code)
    WHERE NOT EXISTS (
        SELECT 1 FROM material_norm n JOIN material m ON m.id = n.material_id
         WHERE n.owner_id IS NULL AND n.name_key = want.name_key AND n.unit = 'M2'
           AND m.code = want.code);
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V133: % (position, material) pair(s) were not written', v_bad;
    END IF;

    -- 7e. Two materials at the same rank in one position render in an arbitrary order. Section 6
    --     computes max+1, so a clash here means two rows raced for the same free rank.
    SELECT count(*) INTO v_bad FROM (
        SELECT name_key, unit, sort_order
          FROM material_norm
         WHERE owner_id IS NULL AND trade = 'DRYWALL' AND material_id IS NOT NULL
         GROUP BY name_key, unit, sort_order HAVING count(*) > 1) d;
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V133: % (position, rank) pair(s) are shared by two materials', v_bad;
    END IF;

    -- 7f. B-27 left no folder behind: every photo's folder still names a real row, or a reserved
    --     value, or nothing at all.
    SELECT count(*) INTO v_bad
      FROM project_photo p
     WHERE p.folder IS NOT NULL
       AND p.folder <> 'RECEIPTS'
       AND NOT EXISTS (SELECT 1 FROM project_photo_folder f
                        WHERE f.project_id = p.project_id
                          AND lower(btrim(f.name)) = lower(btrim(p.folder)));
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V133: % photo(s) point at a folder that no longer exists', v_bad;
    END IF;

    -- 7g. COSMETIC (B-30(b)): the ceiling figures in section 3 assume the Knauf connector frame
    --     V130 shipped. If someone has since removed the connectors, the hangers are now too few -
    --     but that is a judgement call about a live catalog, not a corrupt write, so it must not
    --     stop a deploy at 3am. Warn and let the migration through.
    SELECT count(*) INTO v_bad FROM (VALUES
        ('монтаж гіпсокартону на стелю рівну'),
        ('монтаж гіпсокартону на стелю зі скосами'),
        ('каркасна звукоізоляція (гкл в два слоя) стелі')
    ) AS want(name_key)
    WHERE NOT EXISTS (
        SELECT 1 FROM material_norm n JOIN material m ON m.id = n.material_id
         WHERE n.owner_id IS NULL AND n.name_key = want.name_key AND m.code = 'CONNECTOR_CRAB');
    IF v_bad > 0 THEN
        RAISE WARNING 'V133: % ceiling position(s) no longer buy CONNECTOR_CRAB - the 0.7 '
                      'HANGER_DIRECT figure assumes a connector frame and is now too low', v_bad;
    END IF;
END $$;
