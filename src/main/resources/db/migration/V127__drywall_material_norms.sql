-- =================================================================================================
-- V127 - the DRYWALL half of the material calculator: the material dictionary's first content, the
-- consumption norms behind it, and the schema additions the engine needs (material-calculator
-- iteration, cut 2). V126 built the empty rooms; this migration furnishes the drywall one.
--
-- EVERY FIGURE BELOW IS AN ORIENTATION VALUE FOR MASTERS TO CHECK, NOT A TRUTH. They come from
-- manufacturers' handbooks and the audit in docs/iteration-material-calculator.md section 19, and
-- the result screen exists precisely so the master can correct any of them before buying. Where a
-- source gave a range, one number was chosen, and every such choice still needs field verification.
--
-- Three decisions that shape the content
--
--   1. THE CALCULATOR NEVER REINTERPRETS A QUANTITY THE MASTER TYPED (master's ruling, 2026-09-08:
--      nothing is multiplied by 2 - the figure he entered is the figure we use). The partition
--      positions are priced per m2, and that m2 is the SHEATHING area with both faces already in
--      it. So a board norm is 1 m2/m2 per layer - never x2 - and the frame norms (CW/UW, screws,
--      sealing tape) are written per m2 of SHEATHING as well, roughly half any handbook figure
--      quoted per m2 of partition face. One basis for every norm on the line.
--
--   2. A UNIT IS NEVER CONVERTED. A norm's unit is the POSITION's unit, so a position priced per
--      linear metre carries a per-linear-metre norm. 15 of the 56 live DRYWALL positions are
--      LINEAR_METER - writing a per-m2 norm against one of them was the bug that killed the first
--      draft of this feature, and it would have over-bought on 27 % of the trade. The pair the
--      audit singled out ships both halves: gluing fibreglass over a plane is 1.1 m2/m2, over a
--      joint 0.15 m2/l.m. - same material, ~7x apart, because one is an area and one is a strip.
--
--   3. "NO MATERIAL" IS A RECORDED VERDICT, NOT AN ABSENCE. 11 live positions legitimately consume
--      nothing (demolition, dust removal, sanding, milling, cutting openings). Left unnormed they
--      would land in the coverage report and read as a gap we forgot to fill - noise in the one
--      widget whose entire job is trust. They get a norm row with a NULL material meaning
--      "checked, consumes nothing", which the engine counts as covered and never lists.
--
-- What is deliberately NOT normed (and lands in the coverage report by construction): the arch,
-- decorative elements, the bath screen, installation cladding, the two box positions, the two niche
-- positions, the slopes, and the two frameless soundproofing positions. Each is driven by a
-- geometry the position name cannot carry (a section, a width, a product choice), and guessing one
-- costs the master a drive back to the shop. See audit section 19.2 D and E.
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 1. Schema: the things V126 could not know it would need.
-- -------------------------------------------------------------------------------------------------

-- 1a. A stable machine name for a dictionary row. The norms below reference materials by it instead
--     of by name (a name is content and may be reworded), and the engine needs to recognise exactly
--     one row - the drywall board - whose packaging is driven by the master's GKL_SHEET parameter.
ALTER TABLE material ADD COLUMN code varchar(50);
ALTER TABLE material ADD CONSTRAINT ux_material_code UNIQUE (code);

-- 1b. What a package is CALLED. "14 sheets" and "2 bags" are what the master reads on the shelf;
--     "2 x 25 kg" is the same fact in a language nobody uses in a builders' merchant.
ALTER TABLE material ADD COLUMN package_name varchar(40);
ALTER TABLE material ADD CONSTRAINT material_package_name_check
    CHECK ((package_name IS NULL) = (package_size IS NULL));

-- 1c. A norm may now say "this work consumes nothing" - see decision 3. Material and quantity
--     become nullable together: a quantity without a material has nothing to multiply, and a
--     material without a quantity has nothing to say.
ALTER TABLE material_norm ALTER COLUMN material_id  DROP NOT NULL;
ALTER TABLE material_norm ALTER COLUMN qty_per_unit DROP NOT NULL;
ALTER TABLE material_norm DROP CONSTRAINT material_norm_qty_check;
ALTER TABLE material_norm ADD CONSTRAINT material_norm_qty_check CHECK (
    (material_id IS NULL     AND qty_per_unit IS NULL)
 OR (material_id IS NOT NULL AND qty_per_unit IS NOT NULL AND qty_per_unit > 0)
);

COMMENT ON COLUMN material_norm.material_id IS
    'NULL means "checked, this work consumes no material" - a recorded verdict, not a gap. The engine counts such a position as covered and the coverage report never lists it.';

-- 1d. What the norm multiplies. QUANTITY = the estimate line quantity, which is every norm but one.
--     PERIMETER = a figure the position cannot carry: a UD track runs around the room, not across
--     the sheathed area, so no per-m2 number can produce it. Until the master enters the perimeter
--     on the result screen, such a norm is reported as "perimeter not given" WITH a way to enter
--     it - never quietly computed from something else, and never silently dropped.
ALTER TABLE material_norm ADD COLUMN basis varchar(20) NOT NULL DEFAULT 'QUANTITY';
ALTER TABLE material_norm ADD CONSTRAINT material_norm_basis_check
    CHECK (basis IN ('QUANTITY', 'PERIMETER'));

-- -------------------------------------------------------------------------------------------------
-- 2. The dictionary. No price and no owner - V81's rule stands: a stale guess competing with a real
--    number is worse than no guess. Package sizes are the common Ukrainian retail formats; where a
--    material is sold in formats that vary too much to pick one (rolls of tape, mineral wool,
--    fibreglass), no package is recorded and the calculator rounds up to a whole unit instead.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    -- Boards. The sheet is measured in m2 and BOUGHT by the sheet - that is exactly what a package
    -- is. The size is the master's GKL_SHEET parameter, so the engine overrides these two columns.
    (gen_random_uuid(), 'GKL_SHEET',         'Лист ГКЛ',                        '1200×2500', 'M2', 3.0,  'M2', 'лист'),
    (gen_random_uuid(), 'GKL_SHEET_ARCH',    'Лист ГКЛ арковий 6,5 мм',         '1200×2500', 'M2', 3.0,  'M2', 'лист'),
    (gen_random_uuid(), 'GVL_FLOOR_ELEMENT', 'Елемент підлоги ГВЛ',             NULL,        'M2', NULL, NULL, NULL),

    -- Frame.
    (gen_random_uuid(), 'PROFILE_CD',        'Профіль CD 60×27',                NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'PROFILE_UD',        'Профіль UD 27×28',                NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'PROFILE_CW',        'Профіль стійковий CW',            NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'PROFILE_UW',        'Профіль напрямний UW',            NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'PROFILE_UA',        'Профіль посилений UA',            NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'PROFILE_SHADOW',    'Профіль тіньового шва',           NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'CORNICE_TRACK',     'Трек прихованого карниза',        NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'HANGER_DIRECT',     'Підвіс прямий',                   NULL, 'PIECE', 100,  'PIECE', 'упаковка'),

    -- Fasteners.
    (gen_random_uuid(), 'SCREW_TN25',        'Саморіз TN25',                    NULL, 'PIECE', 1000, 'PIECE', 'упаковка'),
    (gen_random_uuid(), 'SCREW_TN35',        'Саморіз TN35',                    NULL, 'PIECE', 1000, 'PIECE', 'упаковка'),
    (gen_random_uuid(), 'DOWEL_NAIL',        'Дюбель-цвях 6×40',                NULL, 'PIECE', 100,  'PIECE', 'упаковка'),
    (gen_random_uuid(), 'ANCHOR_WEDGE',      'Анкер-клин 6×40',                 NULL, 'PIECE', 100,  'PIECE', 'упаковка'),
    (gen_random_uuid(), 'DOWEL_UMBRELLA',    'Дюбель-парасолька',               NULL, 'PIECE', 100,  'PIECE', 'упаковка'),

    -- Jointing and finishing.
    (gen_random_uuid(), 'PUTTY_JOINT',       'Шпаклівка для стиків',            NULL, 'KG', 25, 'KG', 'мішок'),
    (gen_random_uuid(), 'PUTTY_FINISH',      'Шпаклівка фінішна',               NULL, 'KG', 25, 'KG', 'мішок'),
    (gen_random_uuid(), 'PRIMER_DEEP',       'Ґрунтовка глибокого проникнення', NULL, 'LITRE', 10, 'LITRE', 'каністра'),
    (gen_random_uuid(), 'PRIMER_FILLER',     'Ґрунт-наповнювач',                NULL, 'LITRE', 10, 'LITRE', 'каністра'),
    (gen_random_uuid(), 'TAPE_PAPER',        'Стрічка паперова армувальна',     NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'TAPE_SERPYANKA',    'Стрічка-серпянка',                NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'FIBERGLASS',        'Склополотно',                     NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'FIBERGLASS_GLUE',   'Клей для склополотна',            NULL, 'KG', 10, 'KG', 'відро'),
    (gen_random_uuid(), 'GYPSUM_GLUE',       'Клей гіпсовий монтажний',         NULL, 'KG', 30, 'KG', 'мішок'),

    -- Insulation, sealing, protection.
    (gen_random_uuid(), 'TAPE_SEALING',      'Стрічка ущільнювальна',           NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'TAPE_MASKING',      'Стрічка малярна',                 NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'MINERAL_WOOL',      'Мінеральна вата',                 NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'XPS_BOARD',         'Екструдований пінополістирол',    NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'ACOUSTIC_MEMBRANE', 'Акустична мембрана',              NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'ACOUSTIC_SEALANT',  'Герметик акустичний',             NULL, 'LITRE', 0.6, 'LITRE', 'туба'),
    (gen_random_uuid(), 'CARDBOARD_FLOOR',   'Картон захисний',                 NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'INSPECTION_HATCH',  'Люк-ревізія',                     NULL, 'PIECE', NULL, NULL, NULL);

-- -------------------------------------------------------------------------------------------------
-- 3. The norms. trade is DRYWALL on every row, but that is the FIRST RUNG of the lookup only - the
--    engine falls back to (name_key, unit), and it must, because 15 of these positions are shipped
--    by another trade too (PAINTER mostly) and V118 files a shared position under whichever trade
--    claimed it first. name_key is NameKeys.of(position name): whitespace collapsed, brackets
--    tidied, trimmed, lowercased. The self-check in section 5 refuses the migration if any of them
--    fails to match a live position.
--
--    waste_percent is 0 on every row on purpose: an overlap that is a property of the MATERIAL
--    (tape 1.05, fibreglass 1.1) is baked into qty_per_unit, while the waste allowance is one
--    number the master owns and changes on the result screen. The column stays for a future norm
--    whose own allowance genuinely differs.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'DRYWALL', v.name_key, v.unit, m.id, v.qty, v.basis, v.ord
FROM (VALUES
    -- ---- Sheathing on a frame ------------------------------------------------------------------
    -- CD at a 600 mm step over walls, 400 mm over ceilings - the difference between 2.0 and 2.9.
    ('монтаж гіпсокартону на стіни', 'M2', 'GKL_SHEET',     1.0,  'QUANTITY',  1),
    ('монтаж гіпсокартону на стіни', 'M2', 'PROFILE_CD',    2.0,  'QUANTITY',  2),
    -- Two runs of track on a wall lining (floor and ceiling), one around a ceiling: see the note on
    -- PERIMETER norms in MaterialCalculatorService - the largest of them wins, once per estimate.
    ('монтаж гіпсокартону на стіни', 'M2', 'PROFILE_UD',    2.1,  'PERIMETER', 3),
    ('монтаж гіпсокартону на стіни', 'M2', 'SCREW_TN25',    30,   'QUANTITY',  4),
    ('монтаж гіпсокартону на стіни', 'M2', 'DOWEL_NAIL',    1.6,  'QUANTITY',  5),

    ('монтаж гіпсокартону на стелю рівну', 'M2', 'GKL_SHEET',     1.0,  'QUANTITY',  1),
    ('монтаж гіпсокартону на стелю рівну', 'M2', 'PROFILE_CD',    2.9,  'QUANTITY',  2),
    ('монтаж гіпсокартону на стелю рівну', 'M2', 'PROFILE_UD',    1.05, 'PERIMETER', 3),
    ('монтаж гіпсокартону на стелю рівну', 'M2', 'HANGER_DIRECT', 0.7,  'QUANTITY',  4),
    ('монтаж гіпсокартону на стелю рівну', 'M2', 'SCREW_TN25',    30,   'QUANTITY',  5),
    ('монтаж гіпсокартону на стелю рівну', 'M2', 'DOWEL_NAIL',    1.6,  'QUANTITY',  6),

    ('монтаж гіпсокартону на стелю зі скосами', 'M2', 'GKL_SHEET',     1.0,  'QUANTITY',  1),
    ('монтаж гіпсокартону на стелю зі скосами', 'M2', 'PROFILE_CD',    2.9,  'QUANTITY',  2),
    ('монтаж гіпсокартону на стелю зі скосами', 'M2', 'PROFILE_UD',    1.05, 'PERIMETER', 3),
    ('монтаж гіпсокартону на стелю зі скосами', 'M2', 'HANGER_DIRECT', 0.7,  'QUANTITY',  4),
    ('монтаж гіпсокартону на стелю зі скосами', 'M2', 'SCREW_TN25',    30,   'QUANTITY',  5),
    ('монтаж гіпсокартону на стелю зі скосами', 'M2', 'DOWEL_NAIL',    1.6,  'QUANTITY',  6),

    -- Glued straight onto masonry: no frame, no fasteners, adhesive instead.
    ('монтаж гіпсокартону на клей', 'M2', 'GKL_SHEET',   1.0, 'QUANTITY', 1),
    ('монтаж гіпсокартону на клей', 'M2', 'GYPSUM_GLUE', 5.0, 'QUANTITY', 2),

    -- ---- Partitions - see decision 1: the m2 is the SHEATHING area, both faces already in it ----
    ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар', 'M2', 'GKL_SHEET',    1.0, 'QUANTITY', 1),
    ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар', 'M2', 'PROFILE_CW',   1.0, 'QUANTITY', 2),
    ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар', 'M2', 'PROFILE_UW',   0.5, 'QUANTITY', 3),
    ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар', 'M2', 'SCREW_TN25',   30,  'QUANTITY', 4),
    ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар', 'M2', 'TAPE_SEALING', 0.5, 'QUANTITY', 5),

    ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари', 'M2', 'GKL_SHEET',    2.0, 'QUANTITY', 1),
    ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари', 'M2', 'PROFILE_CW',   1.0, 'QUANTITY', 2),
    ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари', 'M2', 'PROFILE_UW',   0.5, 'QUANTITY', 3),
    ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари', 'M2', 'SCREW_TN25',   30,  'QUANTITY', 4),
    ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари', 'M2', 'SCREW_TN35',   30,  'QUANTITY', 5),
    ('монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари', 'M2', 'TAPE_SEALING', 0.5, 'QUANTITY', 6),

    -- Curved partitions: the arched board, and more screws because the step is tighter.
    ('монтаж радіусних конструкцій (перегородки) із гіпсокартону в 1 шар', 'M2', 'GKL_SHEET_ARCH', 1.0, 'QUANTITY', 1),
    ('монтаж радіусних конструкцій (перегородки) із гіпсокартону в 1 шар', 'M2', 'PROFILE_CW',     1.0, 'QUANTITY', 2),
    ('монтаж радіусних конструкцій (перегородки) із гіпсокартону в 1 шар', 'M2', 'PROFILE_UW',     0.5, 'QUANTITY', 3),
    ('монтаж радіусних конструкцій (перегородки) із гіпсокартону в 1 шар', 'M2', 'SCREW_TN25',     35,  'QUANTITY', 4),

    ('монтаж радіусних конструкцій (перегородки) із гіпсокартону в 2 шари', 'M2', 'GKL_SHEET_ARCH', 2.0, 'QUANTITY', 1),
    ('монтаж радіусних конструкцій (перегородки) із гіпсокартону в 2 шари', 'M2', 'PROFILE_CW',     1.0, 'QUANTITY', 2),
    ('монтаж радіусних конструкцій (перегородки) із гіпсокартону в 2 шари', 'M2', 'PROFILE_UW',     0.5, 'QUANTITY', 3),
    ('монтаж радіусних конструкцій (перегородки) із гіпсокартону в 2 шари', 'M2', 'SCREW_TN25',     35,  'QUANTITY', 4),
    ('монтаж радіусних конструкцій (перегородки) із гіпсокартону в 2 шари', 'M2', 'SCREW_TN35',     35,  'QUANTITY', 5),

    ('монтаж напівкруглої конструкції гкл', 'M2', 'GKL_SHEET_ARCH', 1.0, 'QUANTITY', 1),
    ('монтаж напівкруглої конструкції гкл', 'M2', 'PROFILE_CD',     2.5, 'QUANTITY', 2),
    ('монтаж напівкруглої конструкції гкл', 'M2', 'SCREW_TN25',     35,  'QUANTITY', 3),

    -- A patch: board plus the putty to bed it in. Small quantities, but it is still a real buy.
    ('ремонт ділянки конструкції з гіпсокартону', 'M2', 'GKL_SHEET',   1.0, 'QUANTITY', 1),
    ('ремонт ділянки конструкції з гіпсокартону', 'M2', 'PUTTY_JOINT', 0.5, 'QUANTITY', 2),

    -- ---- Per running metre - the 15-position family the first draft got wrong ------------------
    ('монтаж каркасу посиленим профілем', 'LINEAR_METER', 'PROFILE_UA',   1.05, 'QUANTITY', 1),
    ('монтаж каркасу посиленим профілем', 'LINEAR_METER', 'ANCHOR_WEDGE', 2,    'QUANTITY', 2),

    ('монтаж профілю тіньового шва по периметру стелі', 'LINEAR_METER', 'PROFILE_SHADOW', 1.05, 'QUANTITY', 1),
    ('монтаж профілю тіньового шва по периметру стелі', 'LINEAR_METER', 'SCREW_TN25',     5,    'QUANTITY', 2),

    ('монтаж треків прихованого карниза',        'LINEAR_METER', 'CORNICE_TRACK',    1.05,  'QUANTITY', 1),
    ('монтаж ущільнювальної стрічки на профіль', 'LINEAR_METER', 'TAPE_SEALING',     1.05,  'QUANTITY', 1),
    ('герметизація швів стиків герметиком',      'LINEAR_METER', 'ACOUSTIC_SEALANT', 0.025, 'QUANTITY', 1),

    -- ---- Insulation and soundproofing ----------------------------------------------------------
    ('каркасна звукоізоляція (гкл в два слоя) стелі', 'M2', 'GKL_SHEET',     2.0,  'QUANTITY',  1),
    ('каркасна звукоізоляція (гкл в два слоя) стелі', 'M2', 'PROFILE_CD',    2.9,  'QUANTITY',  2),
    ('каркасна звукоізоляція (гкл в два слоя) стелі', 'M2', 'PROFILE_UD',    1.05, 'PERIMETER', 3),
    ('каркасна звукоізоляція (гкл в два слоя) стелі', 'M2', 'HANGER_DIRECT', 0.7,  'QUANTITY',  4),
    ('каркасна звукоізоляція (гкл в два слоя) стелі', 'M2', 'MINERAL_WOOL',  1.05, 'QUANTITY',  5),
    ('каркасна звукоізоляція (гкл в два слоя) стелі', 'M2', 'SCREW_TN25',    30,   'QUANTITY',  6),
    ('каркасна звукоізоляція (гкл в два слоя) стелі', 'M2', 'SCREW_TN35',    30,   'QUANTITY',  7),
    ('каркасна звукоізоляція (гкл в два слоя) стелі', 'M2', 'DOWEL_NAIL',    1.6,  'QUANTITY',  8),

    ('каркасна звукоізоляція (гкл в два слоя) стін', 'M2', 'GKL_SHEET',    2.0,  'QUANTITY',  1),
    ('каркасна звукоізоляція (гкл в два слоя) стін', 'M2', 'PROFILE_CD',   2.0,  'QUANTITY',  2),
    ('каркасна звукоізоляція (гкл в два слоя) стін', 'M2', 'PROFILE_UD',   2.1,  'PERIMETER', 3),
    ('каркасна звукоізоляція (гкл в два слоя) стін', 'M2', 'MINERAL_WOOL', 1.05, 'QUANTITY',  4),
    ('каркасна звукоізоляція (гкл в два слоя) стін', 'M2', 'SCREW_TN25',   30,   'QUANTITY',  5),
    ('каркасна звукоізоляція (гкл в два слоя) стін', 'M2', 'SCREW_TN35',   30,   'QUANTITY',  6),
    ('каркасна звукоізоляція (гкл в два слоя) стін', 'M2', 'DOWEL_NAIL',   1.6,  'QUANTITY',  7),

    ('звукоізоляція стін мінеральною ватою', 'M2', 'MINERAL_WOOL',      1.05, 'QUANTITY', 1),
    ('утеплення мінватою в один шар',        'M2', 'MINERAL_WOOL',      1.05, 'QUANTITY', 1),
    ('монтаж акустичної мембрани',           'M2', 'ACOUSTIC_MEMBRANE', 1.05, 'QUANTITY', 1),
    ('утеплення гкл стіродуром',             'M2', 'XPS_BOARD',         1.03, 'QUANTITY', 1),
    ('утеплення гкл стіродуром',             'M2', 'DOWEL_UMBRELLA',    5,    'QUANTITY', 2),

    -- ---- Dry floor, hatch, floor protection ----------------------------------------------------
    ('монтаж сухої збірної підлоги з гіпсоволокна', 'M2',    'GVL_FLOOR_ELEMENT', 1.05, 'QUANTITY', 1),
    ('установка люка-ревізії простого',             'PIECE', 'INSPECTION_HATCH',  1,    'QUANTITY', 1),
    ('захист підлоги картоном',                     'M2',    'CARDBOARD_FLOOR',   1.05, 'QUANTITY', 1),
    ('захист підлоги картоном',                     'M2',    'TAPE_MASKING',      0.5,  'QUANTITY', 2),

    -- ---- Finishing for paint -------------------------------------------------------------------
    ('грунтування',              'M2', 'PRIMER_DEEP',   0.12, 'QUANTITY', 1),
    ('криючий ґрунт-наповнювач', 'M2', 'PRIMER_FILLER', 0.18, 'QUANTITY', 1),

    ('базове шпаклювання під скловолокно',                       'M2', 'PUTTY_FINISH', 1.1, 'QUANTITY', 1),
    ('шпаклювання та шліфування гіпсокартону (без склополотна)', 'M2', 'PUTTY_FINISH', 1.1, 'QUANTITY', 1),
    ('шпаклювання фінішне (2–4 рази)',                           'M2', 'PUTTY_FINISH', 1.2, 'QUANTITY', 1),

    -- The pair from the audit: a PLANE in m2, a STRIP in linear metres. Same material, ~7x apart.
    ('поклейка склополотна',                        'M2',           'FIBERGLASS',      1.1,  'QUANTITY', 1),
    ('поклейка склополотна',                        'M2',           'FIBERGLASS_GLUE', 0.25, 'QUANTITY', 2),
    ('проклеювання склополотном примикань і кутів', 'LINEAR_METER', 'FIBERGLASS',      0.15, 'QUANTITY', 1),
    ('проклеювання склополотном примикань і кутів', 'LINEAR_METER', 'FIBERGLASS_GLUE', 0.05, 'QUANTITY', 2),

    ('заповнення та армування стиків гкл', 'LINEAR_METER', 'TAPE_SERPYANKA', 1.05, 'QUANTITY', 1),
    ('заповнення та армування стиків гкл', 'LINEAR_METER', 'PUTTY_JOINT',    0.4,  'QUANTITY', 2),

    ('заповнення стиків гкл паперовою стрічкою високої щільності', 'LINEAR_METER', 'TAPE_PAPER',  1.05, 'QUANTITY', 1),
    ('заповнення стиків гкл паперовою стрічкою високої щільності', 'LINEAR_METER', 'PUTTY_JOINT', 0.4,  'QUANTITY', 2)
) AS v(name_key, unit, code, qty, basis, ord)
JOIN material m ON m.code = v.code;

-- -------------------------------------------------------------------------------------------------
-- 4. "Checked, consumes nothing" - decision 3. Without these rows the coverage widget would report
--    "norms known for 33 of 44" on an estimate whose coverage is in fact complete, and every one of
--    these eleven lines would be offered to the master as something to fix.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'DRYWALL', v.name_key, v.unit, NULL, NULL, 'QUANTITY', 0
FROM (VALUES
    ('демонтаж гіпсокартонної стелі',       'M2'),
    ('демонтаж перегородки з гіпсокартону', 'M2'),
    ('вирізка отворів в гіпсокартоні',      'PIECE'),
    ('фрезерування гіпсокартону',           'LINEAR_METER'),
    ('вологе обезпилювання поверхні',       'M2'),
    ('обезпилення поверхні',                'M2'),
    ('локальне дефектування',               'M2'),
    ('мікрошліфування дефектів',            'M2'),
    ('шліфування стиків гкл',               'LINEAR_METER'),
    ('шліфування під скловолокно/склохолст', 'M2'),
    ('шліфування стін/стель (фінішне)',     'M2')
) AS v(name_key, unit);

-- -------------------------------------------------------------------------------------------------
-- 5. Self-check. A norm whose name_key matches nothing is INVISIBLE at runtime: the position simply
--    lands in the coverage report and the master reads it as a gap in our data rather than as a
--    typo. The same class of silence that made V112 add a self-check to the bundle seeding.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(DISTINCT n.name_key || ' [' || n.unit || ']', ', ')
      INTO missing
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
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V127: material norms reference DRYWALL positions that do not exist: %', missing;
    END IF;
END $$;
