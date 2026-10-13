-- =================================================================================================
-- V151 — the trade-by-trade review, batch 4: the first material norms for BUILDER
--
-- Source: `C:\Work\prompts\trade-review\05-BUILDER.md` §3, re-read against the V149 library (V146
-- already filed the seven shared PAINTER/TILING norms trade-less, V147 the five shared FLOORING ones,
-- V148 rebuilt the bundles). BUILDER ships 229 WORK positions and, until this migration, NOT ONE norm
-- of its own: a builder's «Матеріали» answered only the handful of positions it shares with other
-- trades. The set lands in one migration for V138's reason — partial coverage switches the screen ON
-- and offers one line out of forty.
--
-- WHAT IS NORMED (sections 3-5):
--   * masonry, concrete and bases, facade (ETICS), waterproofing, roofing, paving — under BUILDER;
--   * positions PAINTER or TILING ship too — at `trade = NULL`, one norm answering both (V137 §4);
--   * three FLOORING norms on screed positions BUILDER ships too are RE-FILED to NULL (section 6);
--   * earthworks and the masonry mortar step get a recorded «consumes nothing» verdict.
--
-- OWNER RULINGS (2026-10-13), all as the review recommended:
--   1. Masonry mortar is cement + sand (cement M100, РЕКН-6 6-47-18: 304 kg + 1,21 m³ per m³ of
--      mortar) — what a crew mixes. A master on a lime mortar or a bagged mix forks the rows.
--   2. Slag block ships: the block count is exact geometry (390×190×188 + 10 mm joint = 63/m³); the
--      mortar is an estimate, since a hollow block takes mortar into its voids.
--   3. «Кладка стіни з керамоблока 38х60х20» stays UNNORMED — the name does not say which block.
--   4. «Армування кладки (сіткою, арматурою)» [LINEAR_METER] and «Армування кладки сіткою арматурою»
--      [M2] stay UNNORMED — two materials in one name, and the M2 row is reinforced per metre. Rebar
--      as a whole waits for a «kg per m³» parameter the engine does not have.
--   5. «комплекс» rollups stay UNNORMED — no shipped bundle carries them any more (V146 §9, V148),
--      and a master who puts one beside its own parts would buy every material twice.
--
-- DELIBERATELY UNNORMED besides those (the calculator honestly answers «not covered»): rebar,
-- formwork, embedded parts and utilities; piece goods sized per job (ФБС, slabs, lintels, piles,
-- caps, gutters, hatches, kerbs); roof coverings bought by the slope layout (metal tile, profiled
-- sheet, seam, ceramic tile, slate/ondulin) and the timber frame (mauerlat, rafters, beams);
-- ventilation ducts, a bath plinth, antiseptic per m³ of timber, PU foam, polyurea, PU mastic, and
-- paving mortar and kerbs (ДСТУ Б Д.2.2-27 not read yet).
--
-- BASES AND BOUNDS. THICKNESS is the layer in millimetres and is bounded at 150 mm per question
-- (review round 2 §2), so a suggestion above it would be refused the moment it is pre-filled: the
-- sand cushion therefore suggests 150, not the report's 200. SECTION is in metres (≤ 5 m): the width
-- of the wall, the reveal or the plinth band.
--
-- Every norm carries `waste_percent = 0` (batch 2's ruling): the layout surplus is in the
-- coefficient, the cutting allowance is the master's habit. NO `catalog_templates` row is inserted,
-- so V118's ranking need not be re-run. No owned row is rewritten except the trade of a fork in
-- section 6, which must follow its shipped row (review B-35).
--
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 1. Dictionary rows — 28 of them. Packaging per V127: the smallest size commonly on the shelf, and
--    NULL where it differs by product or merchant, so the answer is in the material's own unit.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    -- ---- Masonry --------------------------------------------------------------------------------
    (gen_random_uuid(), 'GAS_BLOCK',         'Блок газобетонний (піноблок)',     NULL,                'M3',    NULL, NULL, NULL),
    (gen_random_uuid(), 'MASONRY_GLUE_AAC',  'Клей для газобетону',              NULL,                'KG',    25,   'KG', 'мішок'),
    (gen_random_uuid(), 'BRICK_COMMON',      'Цегла рядова',                     '1НФ 250×120×65',    'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'BRICK_FACING',      'Цегла лицьова',                    '1НФ 250×120×65',    'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'CERAMIC_STONE_2NF', 'Камінь керамічний',                '2НФ 250×120×138',   'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'SLAG_BLOCK',        'Шлакоблок',                        '390×190×188',       'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'WALL_TIE',          'Анкер для кладки',                 NULL,                'PIECE', NULL, NULL, NULL),

    -- ---- Binders and aggregates. A cement bag is 25 kg at every merchant; sand, crushed stone and
    --      ready-mix are bought by the cubic metre and delivered, never packed. ------------------
    (gen_random_uuid(), 'CEMENT',            'Цемент',                           NULL,                'KG',    25,   'KG', 'мішок'),
    (gen_random_uuid(), 'SAND',              'Пісок будівельний',                NULL,                'M3',    NULL, NULL, NULL),
    (gen_random_uuid(), 'CRUSHED_STONE',     'Щебінь',                           NULL,                'M3',    NULL, NULL, NULL),
    (gen_random_uuid(), 'CONCRETE_READY',    'Бетон товарний',                   NULL,                'M3',    NULL, NULL, NULL),

    -- ---- Facade (ETICS) -------------------------------------------------------------------------
    (gen_random_uuid(), 'ADHESIVE_ETICS',      'Клей для систем утеплення',            NULL, 'KG',           25,  'KG',    'мішок'),
    (gen_random_uuid(), 'EPS_FACADE',          'Пінополістирол фасадний',              NULL, 'M2',           NULL, NULL,   NULL),
    (gen_random_uuid(), 'MINERAL_WOOL_FACADE', 'Вата мінеральна фасадна',              NULL, 'M2',           NULL, NULL,   NULL),
    (gen_random_uuid(), 'DOWEL_FACADE_STEEL',  'Дюбель фасадний зі сталевим стрижнем', NULL, 'PIECE',        100, 'PIECE', 'пачка'),
    (gen_random_uuid(), 'CORNER_MESH',         'Кутник ПВХ з сіткою',                  NULL, 'LINEAR_METER', NULL, NULL,   NULL),
    (gen_random_uuid(), 'STARTER_PROFILE',     'Профіль стартовий (цокольний)',        NULL, 'LINEAR_METER', NULL, NULL,   NULL),

    -- ---- Waterproofing --------------------------------------------------------------------------
    (gen_random_uuid(), 'BITUMEN_MASTIC',    'Мастика бітумна',                  NULL, 'KG', NULL, NULL, NULL),
    (gen_random_uuid(), 'BITUMEN_PRIMER',    'Праймер бітумний',                 NULL, 'KG', NULL, NULL, NULL),
    -- A roll is 7,5, 10 or 15 m² by product, so no package.
    (gen_random_uuid(), 'ROLL_BITUMEN',      'Рулонна бітумна гідроізоляція',    NULL, 'M2', NULL, NULL, NULL),

    -- ---- Roofing --------------------------------------------------------------------------------
    (gen_random_uuid(), 'ROOF_MEMBRANE',     'Мембрана покрівельна (гідро- / пароізоляційна)', NULL, 'M2', 75, 'M2', 'рулон'),
    (gen_random_uuid(), 'BATTEN',            'Брусок 40×50',                     NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'OSB',               'Плита OSB',                        NULL, 'M2', 3.125, 'M2', 'лист'),
    (gen_random_uuid(), 'ROOF_UNDERLAY',     'Підкладковий килим',               NULL, 'M2', NULL, NULL, NULL),
    -- A bundle covers 2 to 3,5 m² depending on the model.
    (gen_random_uuid(), 'SHINGLES',          'Черепиця бітумна',                 NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'PVC_MEMBRANE',      'Мембрана ПВХ покрівельна',         NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'DRIP_EDGE',         'Планка карнизна (капельник)',      NULL, 'LINEAR_METER', NULL, NULL, NULL),

    -- ---- Paving ---------------------------------------------------------------------------------
    (gen_random_uuid(), 'PAVING',            'Плитка тротуарна / бруківка',      NULL, 'M2', NULL, NULL, NULL);

-- The ETICS reinforcing mesh is sold in 55 m² rolls (Ceresit CT 325: 1,1 × 50 m). It shipped with no
-- package because nothing priced it by the roll yet.
UPDATE material
   SET package_size = 55, package_unit = 'M2', package_name = 'рулон'
 WHERE code = 'MESH_FIBERGLASS' AND package_size IS NULL;

-- =================================================================================================
-- 2. Sources behind the figures (the report's §3 in short form).
--
--   [РЕКН-8] ДСТУ Б Д.2.2-8:2016, masonry: 8-22-1 AAC blocks 0,99 m³ + 25 kg glue per m³; 8-25-1 a
--     100 mm AAC partition 9,9 m³ blocks + 228 kg glue per 100 m²; 8-5-1 brick 394 pcs + 0,24 m³
--     mortar per m³; 8-6-5 a half-brick partition 5,04 thousand pcs + 2,3 m³ mortar per 100 m²;
--     8-12-1 ceramic stone 2НФ 195 pcs + 0,17 m³ mortar per m³; 8-3-2 one-layer horizontal roll
--     waterproofing 110 m² + 0,22 t mastic per 100 m²; 8-3-5/6 a fused roof in two layers 230 m² per
--     100 m²; 8-3-7 a two-coat bitumen coating 0,24 t per 100 m²; 8-2-1 sand 1,1 m³ and 8-2-2
--     crushed stone 1,15 m³ per m³ of compacted base.
--   [РЕКН-6] ДСТУ Б Д.2.2-6:2016, cast-in-place concrete: group 1 ready-mix 101,5 m³ per 100 m³ in
--     place (102 for a blinding layer); 6-47-18 mortar M100 = 304 kg ШПЦ-400 + 1,21 m³ sand per m³;
--     6-45-11 concrete B20 on crushed stone = 332 kg + 0,80 m³ + 0,50 m³; 6-45-10 B15 = 286 / 0,80 /
--     0,53. Rebar is «за проектом» there too, hence no norm here.
--   Kreisel 125: AAC glue 3 kg/m² at 12 cm, 5 at 24 → 0,025 kg per m² per mm.
--   Ceresit CT 83 / CT 85 / CT 190, Kreisel TF05 / F25: board adhesive ≈ 5 (EPS) to 6 (wool) kg/m²,
--     the reinforcing base coat 4-6 kg/m² → 5,0.
--   Knauf Therm ETIXX Fasada 31: at least 4 anchors per m²; Rockwool ETICS brochure: 5 in the field
--     and 7 at the edge → ≈5 (EPS) and ≈6 (wool, steel-pin anchors).
--   Bostik Waterstop Dysperbit: 1,5 kg/m² at ≥ 1 mm, primer 0,2 → mastic 2,0 (between Bostik and
--     РЕКН-8's 2,4) + primer 0,3.
--   Pruszyński metal-tile manual: battens 40×50 at 350 mm → 1/0,35 × 1,05 = 3,0 lm/m²; counter-battens
--     on rafters at 600 mm → 1/0,6 × 1,05 = 1,75.
--   Calculation (overlaps): membranes and underlay on a 1-1,5 m roll at 10-15 cm → 1,15; a fused roll
--     with 10 cm side and 15 cm end laps → 1,13 ≈ 1,15; PE film turned up the wall → 1,15; facade and
--     floor boards cut at the edges → 1,05 (1,1 on narrow reveal strips).
--   Reused verbatim from shipped norms: decorative plaster 2,8 (PAINTER короїд), cement-lime plaster
--     1,4 kg per m² per mm (PAINTER), clinker cladding C2 6,0 + grout 2,9 at a 10 mm joint (TILING
--     «під цеглу», V146), waterproofing mastic 1,5 and tape 1,05 (TILING), mineral wool 1,05
--     (DRYWALL); the screed mesh under a path at 1,15 (a 2 m² sheet lapped one square).
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 3. BUILDER norms.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order, default_param, baseline_param)
SELECT gen_random_uuid(), 'BUILDER', v.name_key, v.unit, m.id, v.qty, v.basis, v.ord, v.def, v.base
FROM (VALUES
    -- ---- Кладка. AAC is sold by the m³, so the block norm does not depend on the block size. ----
    ('кладка стін з газоблоку або піноблоку 200 мм', 'M3', 'GAS_BLOCK',        0.99::numeric, 'QUANTITY', 1, NULL::numeric, NULL::numeric),
    ('кладка стін з газоблоку або піноблоку 200 мм', 'M3', 'MASONRY_GLUE_AAC', 25,     'QUANTITY', 2, NULL, NULL),
    ('кладка стін з газоблоку або піноблоку 300 мм', 'M3', 'GAS_BLOCK',        0.99,   'QUANTITY', 1, NULL, NULL),
    ('кладка стін з газоблоку або піноблоку 300 мм', 'M3', 'MASONRY_GLUE_AAC', 25,     'QUANTITY', 2, NULL, NULL),
    -- A partition is priced by the m², so its thickness is asked: 9,9 m³ per 100 m² at 100 mm.
    ('кладка перегородки з піноблоку газоблоку до 50м2',  'M2', 'GAS_BLOCK',        0.00099, 'THICKNESS', 1, 100, NULL),
    ('кладка перегородки з піноблоку газоблоку до 50м2',  'M2', 'MASONRY_GLUE_AAC', 0.025,   'THICKNESS', 2, 100, NULL),
    ('кладка перегородки з піноблоку газоблоку від 50м2', 'M2', 'GAS_BLOCK',        0.00099, 'THICKNESS', 1, 100, NULL),
    ('кладка перегородки з піноблоку газоблоку від 50м2', 'M2', 'MASONRY_GLUE_AAC', 0.025,   'THICKNESS', 2, 100, NULL),
    -- Brick by the m³: the «240 / 380 мм» in the names is the wall, and a m³ of wall takes the same
    -- 394 bricks and 0,24 m³ of mortar whatever its thickness. Mortar = 0,24 × (304 kg, 1,21 m³).
    ('кладка цегли 240 мм', 'M3', 'BRICK_COMMON', 394,  'QUANTITY', 1, NULL, NULL),
    ('кладка цегли 240 мм', 'M3', 'CEMENT',       73,   'QUANTITY', 2, NULL, NULL),
    ('кладка цегли 240 мм', 'M3', 'SAND',         0.29, 'QUANTITY', 3, NULL, NULL),
    ('кладка цегли 380 мм', 'M3', 'BRICK_COMMON', 394,  'QUANTITY', 1, NULL, NULL),
    ('кладка цегли 380 мм', 'M3', 'CEMENT',       73,   'QUANTITY', 2, NULL, NULL),
    ('кладка цегли 380 мм', 'M3', 'SAND',         0.29, 'QUANTITY', 3, NULL, NULL),
    -- A half-brick partition: 50,4 bricks and 0,023 m³ of mortar per m².
    ('кладка перегородки з цегли до 50м2',  'M2', 'BRICK_COMMON', 50.4,  'QUANTITY', 1, NULL, NULL),
    ('кладка перегородки з цегли до 50м2',  'M2', 'CEMENT',       7.0,   'QUANTITY', 2, NULL, NULL),
    ('кладка перегородки з цегли до 50м2',  'M2', 'SAND',         0.028, 'QUANTITY', 3, NULL, NULL),
    ('кладка перегородки з цегли від 50м2', 'M2', 'BRICK_COMMON', 50.4,  'QUANTITY', 1, NULL, NULL),
    ('кладка перегородки з цегли від 50м2', 'M2', 'CEMENT',       7.0,   'QUANTITY', 2, NULL, NULL),
    ('кладка перегородки з цегли від 50м2', 'M2', 'SAND',         0.028, 'QUANTITY', 3, NULL, NULL),
    -- Facing brick is one half-brick leaf: the partition's geometry. A coloured mortar is a fork.
    ('кладка облицювальної цегли', 'M2', 'BRICK_FACING', 50.4,  'QUANTITY', 1, NULL, NULL),
    ('кладка облицювальної цегли', 'M2', 'CEMENT',       7.0,   'QUANTITY', 2, NULL, NULL),
    ('кладка облицювальної цегли', 'M2', 'SAND',         0.028, 'QUANTITY', 3, NULL, NULL),
    ('кладка стіни з керамоблока 2нф 25х12', 'M3', 'CERAMIC_STONE_2NF', 195,  'QUANTITY', 1, NULL, NULL),
    ('кладка стіни з керамоблока 2нф 25х12', 'M3', 'CEMENT',            52,   'QUANTITY', 2, NULL, NULL),
    ('кладка стіни з керамоблока 2нф 25х12', 'M3', 'SAND',              0.21, 'QUANTITY', 3, NULL, NULL),
    -- Ruling 2: 1/(0,40×0,20×0,198) = 63 blocks; mortar 1 − 63 × 0,01393 = 0,12 m³ (an estimate).
    ('кладка стін з шлакоблоку', 'M3', 'SLAG_BLOCK', 63,   'QUANTITY', 1, NULL, NULL),
    ('кладка стін з шлакоблоку', 'M3', 'CEMENT',     37,   'QUANTITY', 2, NULL, NULL),
    ('кладка стін з шлакоблоку', 'M3', 'SAND',       0.15, 'QUANTITY', 3, NULL, NULL),
    -- The levelling course is a 2 cm cement bed under the first row, as wide as the wall:
    -- 0,02 m³ per m² × (304 kg, 1,21 m³).
    ('кладка першого (вирівнюючого) ряду газоблоку на цементний розчин', 'LINEAR_METER', 'CEMENT', 6.1,   'SECTION', 1, 0.3, NULL),
    ('кладка першого (вирівнюючого) ряду газоблоку на цементний розчин', 'LINEAR_METER', 'SAND',   0.024, 'SECTION', 2, 0.3, NULL),
    ('гідроізоляція перед першим рядом', 'LINEAR_METER', 'ROLL_BITUMEN',   1.1, 'SECTION', 1, 0.3, NULL),
    ('гідроізоляція перед першим рядом', 'LINEAR_METER', 'BITUMEN_MASTIC', 2.2, 'SECTION', 2, 0.3, NULL),
    ('анкерування однієї стіни до іншої', 'PIECE', 'WALL_TIE', 1, 'QUANTITY', 1, NULL, NULL),

    -- ---- Бетон, фундамент, основи ---------------------------------------------------------------
    ('облаштування фундаменту (приймання бетону арматура)',    'M3', 'CONCRETE_READY', 1.015, 'QUANTITY', 1, NULL, NULL),
    ('влаштування фундаментної плити (армування, бетонування)', 'M3', 'CONCRETE_READY', 1.015, 'QUANTITY', 1, NULL, NULL),
    ('монолітні стіни',                                        'M3', 'CONCRETE_READY', 1.015, 'QUANTITY', 1, NULL, NULL),
    ('монолітне перекриття приймання бетону армування',        'M3', 'CONCRETE_READY', 1.015, 'QUANTITY', 1, NULL, NULL),
    ('виготовлення монолітних колон',                          'M3', 'CONCRETE_READY', 1.015, 'QUANTITY', 1, NULL, NULL),
    ('буронабивні палі буріння монтаж каркасу заливка',        'M3', 'CONCRETE_READY', 1.015, 'QUANTITY', 1, NULL, NULL),
    ('влаштування бетонної підготовки (підбетонки) до 100 мм', 'M2', 'CONCRETE_READY', 0.00102,  'THICKNESS', 1, 100, NULL),
    ('влаштування відмостки бетонної',                         'M2', 'CONCRETE_READY', 0.001015, 'THICKNESS', 1, 100, NULL),
    -- Four path rows: the two spellings the library still ships, each with and without mesh.
    ('доріжки з бетону без армування', 'M2', 'CONCRETE_READY', 0.001015, 'THICKNESS', 1, 100, NULL),
    ('доріжок з бетону без армування', 'M2', 'CONCRETE_READY', 0.001015, 'THICKNESS', 1, 100, NULL),
    ('доріжки з бетону з армуванням',  'M2', 'CONCRETE_READY', 0.001015, 'THICKNESS', 1, 100, NULL),
    ('доріжки з бетону з армуванням',  'M2', 'MESH_SCREED',    1.15,     'QUANTITY',  2, NULL, NULL),
    ('доріжок з бетону з армуванням',  'M2', 'CONCRETE_READY', 0.001015, 'THICKNESS', 1, 100, NULL),
    ('доріжок з бетону з армуванням',  'M2', 'MESH_SCREED',    1.15,     'QUANTITY',  2, NULL, NULL),
    -- The section is in the name: 0,2 × 0,5 m × 1,015.
    ('цоколь паркану бетонний (копка, армування, опалубка) 20х50', 'LINEAR_METER', 'CONCRETE_READY', 0.1015, 'QUANTITY', 1, NULL, NULL),
    ('цоколь паркану бетонний 20х50',                              'LINEAR_METER', 'CONCRETE_READY', 0.1015, 'QUANTITY', 1, NULL, NULL),
    ('приготування бетонного розчину для фундаменту', 'M3', 'CEMENT',        332,  'QUANTITY', 1, NULL, NULL),
    ('приготування бетонного розчину для фундаменту', 'M3', 'CRUSHED_STONE', 0.80, 'QUANTITY', 2, NULL, NULL),
    ('приготування бетонного розчину для фундаменту', 'M3', 'SAND',          0.50, 'QUANTITY', 3, NULL, NULL),
    ('приготування бетону для тротуарної плитки',     'M3', 'CEMENT',        286,  'QUANTITY', 1, NULL, NULL),
    ('приготування бетону для тротуарної плитки',     'M3', 'CRUSHED_STONE', 0.80, 'QUANTITY', 2, NULL, NULL),
    ('приготування бетону для тротуарної плитки',     'M3', 'SAND',          0.53, 'QUANTITY', 3, NULL, NULL),
    -- 150 and not the report's 200: THICKNESS is bounded at 150 mm per question.
    ('піщана подушка з ущільненням', 'M2', 'SAND',          0.0011,  'THICKNESS', 1, 150, NULL),
    ('щебенева підготовка до 10 см', 'M2', 'CRUSHED_STONE', 0.00115, 'THICKNESS', 1, 100, NULL),
    -- A product primer, so «primer once» (V145/V146) does not reach it.
    ('гідроізоляція фундаменту мастикою', 'M2', 'BITUMEN_MASTIC', 2.0,  'QUANTITY', 1, NULL, NULL),
    ('гідроізоляція фундаменту мастикою', 'M2', 'BITUMEN_PRIMER', 0.3,  'QUANTITY', 2, NULL, NULL),
    ('гідроізоляція плівкою',             'M2', 'PE_FILM',        1.15, 'QUANTITY', 1, NULL, NULL),
    ('утеплення фундаменту / цоколя екструдованим пінополістиролом', 'M2', 'XPS_BOARD',      1.05, 'QUANTITY', 1, NULL, NULL),
    ('утеплення фундаменту / цоколя екструдованим пінополістиролом', 'M2', 'ADHESIVE_ETICS', 6.0,  'QUANTITY', 2, NULL, NULL),
    ('утеплення фундаменту / цоколя екструдованим пінополістиролом', 'M2', 'DOWEL_UMBRELLA', 4,    'QUANTITY', 3, NULL, NULL),

    -- ---- Фасад (BUILDER-only rows; the shared ones are in section 4) -----------------------------
    -- The corner bead is bought HERE only. «Армування укосів» sits beside this position in the
    -- facade bundle, and a bead on both would buy it twice.
    ('монтаж кутника з сіткою на кути фасаду і укоси', 'LINEAR_METER', 'CORNER_MESH', 1.05, 'QUANTITY', 1, NULL, NULL),
    ('механізоване нанесення камінцевої штукатурки',   'M2', 'PLASTER_DECOR', 2.8, 'QUANTITY', 1, NULL, NULL),
    ('розділення розмітка двох кольорів декор штукатурки', 'LINEAR_METER', 'TAPE_MASKING', 1.1, 'QUANTITY', 1, NULL, NULL),
    ('штукатурка фасаду цементно-вапняна по сітці', 'M2', 'PLASTER_CEMENT_LIME', 1.4, 'THICKNESS', 1, 20, NULL),
    -- TILING's «під цеглу» figures, grout at its own 10 mm joint so the tile-joint habit leaves it be.
    ('облицювання фасаду клінкерною плиткою', 'M2', 'TILE_ADHESIVE_C2', 6.0, 'QUANTITY', 1, NULL, NULL),
    ('облицювання фасаду клінкерною плиткою', 'M2', 'TILE_GROUT',       2.9, 'QUANTITY', 2, NULL, 10),

    -- ---- Гідроізоляція та покрівля --------------------------------------------------------------
    ('гідроізоляція підлоги стін в санвузлі мастика', 'M2', 'WATERPROOF_MASTIC', 1.5, 'QUANTITY', 1, NULL, NULL),
    ('монтаж гідроізоляційної стрічки', 'LINEAR_METER', 'TAPE_WATERPROOF', 1.05, 'QUANTITY', 1, NULL, NULL),
    ('гідроізоляція покрівлі мастика і євроруберойд', 'M2', 'ROLL_BITUMEN',   1.15, 'QUANTITY', 1, NULL, NULL),
    ('гідроізоляція покрівлі мастика і євроруберойд', 'M2', 'BITUMEN_MASTIC', 2.2,  'QUANTITY', 2, NULL, NULL),
    ('гідроізоляція покрівлі мастика і євроруберойд', 'M2', 'BITUMEN_PRIMER', 0.3,  'QUANTITY', 3, NULL, NULL),
    ('облаштування покрівель з наплавленням руберойду в один шар', 'M2', 'ROLL_BITUMEN',   1.15, 'QUANTITY', 1, NULL, NULL),
    ('облаштування покрівель з наплавленням руберойду в один шар', 'M2', 'BITUMEN_PRIMER', 0.3,  'QUANTITY', 2, NULL, NULL),
    ('облаштування покрівель наплавлення руберойду один шар',      'M2', 'ROLL_BITUMEN',   1.15, 'QUANTITY', 1, NULL, NULL),
    ('облаштування покрівель наплавлення руберойду один шар',      'M2', 'BITUMEN_PRIMER', 0.3,  'QUANTITY', 2, NULL, NULL),
    ('облаштування покрівель з наплавленням руберойду в два шари', 'M2', 'ROLL_BITUMEN',   2.3,  'QUANTITY', 1, NULL, NULL),
    ('облаштування покрівель з наплавленням руберойду в два шари', 'M2', 'BITUMEN_PRIMER', 0.3,  'QUANTITY', 2, NULL, NULL),
    ('облаштування покрівель наплавлення руберойду два шари',      'M2', 'ROLL_BITUMEN',   2.3,  'QUANTITY', 1, NULL, NULL),
    ('облаштування покрівель наплавлення руберойду два шари',      'M2', 'BITUMEN_PRIMER', 0.3,  'QUANTITY', 2, NULL, NULL),
    ('гідробар''єр паробар''єр',                   'M2', 'ROOF_MEMBRANE', 1.15, 'QUANTITY', 1, NULL, NULL),
    ('монтаж пароізоляції покрівлі зсередини',     'M2', 'ROOF_MEMBRANE', 1.15, 'QUANTITY', 1, NULL, NULL),
    ('монтаж обрешітки покрівлі (крок 350 мм)',    'M2', 'BATTEN',        3.0,  'QUANTITY', 1, NULL, NULL),
    ('монтаж контррейки по кроквах (крок 600 мм)', 'M2', 'BATTEN',        1.75, 'QUANTITY', 1, NULL, NULL),
    ('монтаж осб плити',                           'M2', 'OSB',           1.05, 'QUANTITY', 1, NULL, NULL),
    ('укладання підкладкового килима',             'M2', 'ROOF_UNDERLAY', 1.15, 'QUANTITY', 1, NULL, NULL),
    ('укладання бітумної черепиці',                'M2', 'SHINGLES',      1.1,  'QUANTITY', 1, NULL, NULL),
    ('облаштування пвх-мембрани покрівлі',         'M2', 'PVC_MEMBRANE',  1.1,  'QUANTITY', 1, NULL, NULL),
    ('монтаж утеплювача на покрівлю до 100мм',     'M2', 'MINERAL_WOOL',  1.05, 'QUANTITY', 1, NULL, NULL),
    ('монтаж утеплювача на перекриття до 100мм',   'M2', 'MINERAL_WOOL',  1.05, 'QUANTITY', 1, NULL, NULL),
    -- «більше 100 мм» is two layers of board.
    ('монтаж утеплювача на покрівлю більше 100мм',   'M2', 'MINERAL_WOOL', 2.1, 'QUANTITY', 1, NULL, NULL),
    ('монтаж утеплювача на перекриття більше 100мм', 'M2', 'MINERAL_WOOL', 2.1, 'QUANTITY', 1, NULL, NULL),
    ('монтаж капельника', 'LINEAR_METER', 'DRIP_EDGE', 1.05, 'QUANTITY', 1, NULL, NULL),

    -- ---- Благоустрій ----------------------------------------------------------------------------
    ('укладання тротуарної плитки на гарцовку',           'M2', 'PAVING', 1.05, 'QUANTITY', 1, NULL, NULL),
    ('укладання бруківки типу "старе місто" на гарцовку', 'M2', 'PAVING', 1.05, 'QUANTITY', 1, NULL, NULL),
    ('укладання клінкерної бруківки',                     'M2', 'PAVING', 1.05, 'QUANTITY', 1, NULL, NULL)
) AS v(name_key, unit, code, qty, basis, ord, def, base)
JOIN material m ON m.code = v.code;

-- -------------------------------------------------------------------------------------------------
-- 4. Positions PAINTER or TILING ship too — `trade = NULL`, so one norm answers whichever trade the
--    master's line is filed under (V137 §4, V146 §7). None of them carried a norm before, except the
--    facade mesh, which gains the base-coat adhesive nothing anywhere bought until now.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order, default_param, baseline_param)
SELECT gen_random_uuid(), NULL, v.name_key, v.unit, m.id, v.qty, v.basis, v.ord, v.def, v.base
FROM (VALUES
    ('армування фасаду сітка перетяжка', 'M2', 'ADHESIVE_ETICS', 5.0::numeric, 'QUANTITY', 2, NULL::numeric, NULL::numeric),
    ('армування укосів сітка перетяжка', 'LINEAR_METER', 'MESH_FIBERGLASS', 1.1, 'SECTION', 1, 0.3, NULL),
    ('армування укосів сітка перетяжка', 'LINEAR_METER', 'ADHESIVE_ETICS',  5.0, 'SECTION', 2, 0.3, NULL),
    ('монтаж пінопласта клей дюбель', 'M2', 'EPS_FACADE',     1.05, 'QUANTITY', 1, NULL, NULL),
    ('монтаж пінопласта клей дюбель', 'M2', 'ADHESIVE_ETICS', 5.5,  'QUANTITY', 2, NULL, NULL),
    ('монтаж пінопласта клей дюбель', 'M2', 'DOWEL_UMBRELLA', 5,    'QUANTITY', 3, NULL, NULL),
    ('монтаж вати клей дюбель', 'M2', 'MINERAL_WOOL_FACADE', 1.05, 'QUANTITY', 1, NULL, NULL),
    ('монтаж вати клей дюбель', 'M2', 'ADHESIVE_ETICS',      6.0,  'QUANTITY', 2, NULL, NULL),
    ('монтаж вати клей дюбель', 'M2', 'DOWEL_FACADE_STEEL',  6,    'QUANTITY', 3, NULL, NULL),
    -- Narrow reveal strips leave more offcut than a field: 1,1.
    ('утеплення укосів пінопластом', 'LINEAR_METER', 'EPS_FACADE',          1.1, 'SECTION', 1, 0.3, NULL),
    ('утеплення укосів пінопластом', 'LINEAR_METER', 'ADHESIVE_ETICS',      5.5, 'SECTION', 2, 0.3, NULL),
    ('утеплення укосів ватою',       'LINEAR_METER', 'MINERAL_WOOL_FACADE', 1.1, 'SECTION', 1, 0.3, NULL),
    ('утеплення укосів ватою',       'LINEAR_METER', 'ADHESIVE_ETICS',      6.0, 'SECTION', 2, 0.3, NULL),
    -- The plinth band is priced by the metre; its height is the question.
    ('утеплення цоколя пінополістиролом', 'LINEAR_METER', 'XPS_BOARD',      1.05, 'SECTION', 1, 0.5, NULL),
    ('утеплення цоколя пінополістиролом', 'LINEAR_METER', 'ADHESIVE_ETICS', 6.0,  'SECTION', 2, 0.5, NULL),
    ('утеплення цоколя пінополістиролом', 'LINEAR_METER', 'DOWEL_UMBRELLA', 4,    'SECTION', 3, 0.5, NULL),
    ('монтаж стартової планки', 'LINEAR_METER', 'STARTER_PROFILE', 1.02, 'QUANTITY', 1, NULL, NULL),
    ('облицювання клінкерною плиткою', 'M2', 'TILE_ADHESIVE_C2', 6.0, 'QUANTITY', 1, NULL, NULL),
    ('облицювання клінкерною плиткою', 'M2', 'TILE_GROUT',       2.9, 'QUANTITY', 2, NULL, 10),
    ('гідроізоляція плівкова', 'M2', 'PE_FILM', 1.15, 'QUANTITY', 1, NULL, NULL)
) AS v(name_key, unit, code, qty, basis, ord, def, base)
JOIN material m ON m.code = v.code;

-- -------------------------------------------------------------------------------------------------
-- 5. «Checked, consumes nothing» — earthworks, and the mortar step whose mortar the masonry rows
--    already buy (the «Коробка будинку…» bundles carry both, which would buy it twice).
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'BUILDER', v.name_key, v.unit, NULL, NULL, 'QUANTITY', 0
FROM (VALUES
    ('приготування розчину для кладки без щебня',                         'M3'),
    ('вивезення ґрунту',                                                  'M3'),
    ('викорчовування пнів',                                               'PIECE'),
    ('зворотня засипка траншеї',                                          'M3'),
    ('копання траншей ям котловану вручну',                               'M3'),
    ('копка траншей глибиною 0,5 м і шириною 35см',                       'LINEAR_METER'),
    ('перенесення землі (не далі 20м)',                                   'M3'),
    ('планування грунту до 10 см',                                        'M2'),
    ('планування ділянки і нівелювання - від',                            'PIECE'),
    ('розчищення території від чагарників',                               'M2'),
    ('спил дерева',                                                       'PIECE'),
    ('планування ущільнення віброплитою під плитку до 20см',              'M2'),
    ('планування і ущільнення віброплитою під тротуарну плитку до 20 см', 'M2')
) AS v(name_key, unit);

-- -------------------------------------------------------------------------------------------------
-- 6. Three FLOORING norms on positions BUILDER ships too. V148 put them into BUILDER's screed bundle,
--    where a FLOORING-filed norm is never reached. Re-filed, not duplicated, and the forks move in
--    the same statement (review B-35) unless the owner already holds a trade-less twin (B-107).
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm n
   SET trade = NULL
 WHERE n.trade = 'FLOORING'
   AND (n.name_key, n.unit) IN (
          ('монтаж демпферної стрічки',    'M2'),
          ('монтаж утеплювача під стяжку', 'M2'),
          ('плівкова ізоляція під стяжку', 'M2'))
   AND NOT EXISTS (SELECT 1 FROM material_norm o
                    WHERE o.owner_id IS NOT DISTINCT FROM n.owner_id
                      AND o.trade IS NULL
                      AND o.name_key = n.name_key AND o.unit = n.unit
                      AND o.material_id IS NOT DISTINCT FROM n.material_id);

-- -------------------------------------------------------------------------------------------------
-- 7. Self-checks, each about THIS migration's own work (review B-50).
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    orphans      text;
    homeless     text;
    ambiguous    text;
    out_of_range int;
    left_over    int;
    normed       int;
BEGIN
    -- 7a. Every BUILDER key names a live BUILDER WORK position (a typo here is mine).
    SELECT string_agg(DISTINCT n.name_key || ' [' || n.unit || ']', ', ')
      INTO orphans
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.trade = 'BUILDER'
       AND NOT EXISTS (
            SELECT 1 FROM catalog_templates t
             WHERE t.trade = 'BUILDER' AND t.type = 'WORK' AND t.unit = n.unit
               AND lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) = n.name_key
       );
    IF orphans IS NOT NULL THEN
        RAISE EXCEPTION 'V151: BUILDER norms name no live catalog position: %', orphans;
    END IF;

    -- 7b. A trade-less norm must still name a position somewhere.
    SELECT string_agg(DISTINCT n.name_key || ' [' || n.unit || ']', ', ')
      INTO homeless
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.trade IS NULL
       AND NOT EXISTS (
            SELECT 1 FROM catalog_templates t
             WHERE t.type = 'WORK' AND t.unit = n.unit
               AND lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) = n.name_key
       );
    IF homeless IS NOT NULL THEN
        RAISE EXCEPTION 'V151: trade-less norms name no catalog position at all: %', homeless;
    END IF;

    -- 7c. Two trades norming one (name, unit) cannot be resolved; a trade-less row beside a traded
    --     one is the same ambiguity with a NULL in it.
    SELECT string_agg(name_key || ' [' || unit || ']', ', ' ORDER BY name_key)
      INTO ambiguous
      FROM (SELECT name_key, unit FROM material_norm
             WHERE owner_id IS NULL
             GROUP BY name_key, unit
            HAVING count(DISTINCT trade) > 1
                OR (count(DISTINCT trade) = 1 AND count(trade) <> count(*))) AS a;
    IF ambiguous IS NOT NULL THEN
        RAISE EXCEPTION 'V151: a name and unit two trades both norm cannot be resolved: %', ambiguous;
    END IF;

    -- 7d. A suggestion outside its per-question bound is refused the moment it is pre-filled, so the
    --     position would ask forever: THICKNESS in (0, 150] mm and never empty, SECTION in (0, 5] m.
    SELECT count(*) INTO out_of_range
      FROM material_norm
     WHERE owner_id IS NULL
       AND ((basis = 'THICKNESS' AND (default_param IS NULL OR default_param <= 0 OR default_param > 150))
         OR (basis = 'SECTION'   AND default_param IS NOT NULL AND (default_param <= 0 OR default_param > 5)));
    IF out_of_range > 0 THEN
        RAISE EXCEPTION 'V151: % shipped norm(s) suggest a parameter outside its bound', out_of_range;
    END IF;

    -- 7e. Section 6 left no shipped row behind.
    SELECT count(*) INTO left_over
      FROM material_norm
     WHERE owner_id IS NULL AND trade = 'FLOORING'
       AND (name_key, unit) IN (('монтаж демпферної стрічки', 'M2'),
                                ('монтаж утеплювача під стяжку', 'M2'),
                                ('плівкова ізоляція під стяжку', 'M2'));
    IF left_over <> 0 THEN
        RAISE EXCEPTION 'V151: % shared FLOORING norm(s) are still filed under one trade', left_over;
    END IF;

    -- 7f. Row count, so a half-applied VALUES list cannot pass quietly.
    SELECT count(DISTINCT name_key || '|' || unit) INTO normed
      FROM material_norm WHERE owner_id IS NULL AND trade = 'BUILDER';
    IF normed <> 76 THEN
        RAISE EXCEPTION 'V151: expected 76 normed BUILDER positions, found %', normed;
    END IF;

    -- 7g. And the whole trade: 106 of BUILDER's 229 positions answer (with the trade-less rows). The
    --     123 left are the rulings and the «not normable» list in the header; the integration test
    --     names the ones a ruling decided.
    SELECT count(*) INTO normed
      FROM catalog_templates t
     WHERE t.trade = 'BUILDER' AND t.type = 'WORK'
       AND EXISTS (
            SELECT 1 FROM material_norm n
             WHERE n.owner_id IS NULL
               AND (n.trade = 'BUILDER' OR n.trade IS NULL)
               AND n.unit = t.unit
               AND n.name_key = lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')')))
       );
    IF normed <> 106 THEN
        RAISE EXCEPTION 'V151: expected 106 answered BUILDER positions, found %', normed;
    END IF;
END $$;
