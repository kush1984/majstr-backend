-- =================================================================================================
-- V137 - the TILING and PAINTER halves of the material calculator, and the parameter the first
-- two trades were missing: the LAYER THICKNESS.
--
-- V127 furnished the drywall room and V131 taught the engine to ASK for a figure a position name
-- cannot carry (the box section). This migration finishes the other two trades the product ships
-- catalogs for - ~170 live TILING positions and ~230 PAINTER ones - and fixes what the datasheet
-- audit in "Materials calculators/NORMS-SUMMARY.md" made visible about the machinery itself.
--
-- EVERY FIGURE BELOW IS AN ORIENTATION VALUE FOR THE MASTER TO CHECK, NOT A TRUTH. They come from
-- the manufacturers' datasheet survey in NORMS-SUMMARY sections 2 and 3 (Ceresit, Knauf, Kreisel,
-- Baumit, Polimin, Ferozit, Anserglob, Master, Caparol, Sniezka), and the result screen exists
-- precisely so he can correct any of them before he buys.
--
-- WHAT WAS WRONG, AND IS FIXED HERE
--
--   1. THREE PREFERENCE KEYS WERE THE WRONG KIND OF ANSWER. V126 shipped TILE_SIZE,
--      TILE_THICKNESS_MM and TILE_LAYOUT as MASTER habits. They are not habits - they are
--      properties of the WORK, and the catalog already names them: «Укладання плитки 300х600»,
--      «Укладання плитки 1200х3200 мм», «Штукатурка стін ручна до 2см», and the layout is its own
--      PERCENT position («по діагоналі», «ялинкою»). Worse, one answer per MASTER is silently
--      wrong for an estimate mixing 300х300 floor tile with 600х1200 wall tile - which is most
--      bathrooms. They are dropped; the norms below read the format off the position name, which
--      is where it has always been.
--
--   2. THE PARAMETER THAT IS GENUINELY MISSING IS THE LAYER THICKNESS, AND IT IS PER POSITION.
--      Plaster, screed, levelling compound and start putty are consumed per m2 PER MILLIMETRE, so
--      the millimetres ARE the bill: «Стяжка маякова цементна» at 20 mm and at 60 mm differ
--      threefold. A position name carries at most a bound («до 2 см» is not a thickness), and a
--      per-MASTER answer is wrong for the same reason TILE_SIZE was. So it gets exactly the
--      treatment V131 gave the box section: basis THICKNESS, asked once per POSITION, reported as
--      a missing parameter until it is answered.
--
--   3. A MISSING PARAMETER NOW CARRIES ITS OWN SUGGESTION. open-questions section 15 says a
--      parameter a norm needs but the position name does not carry must be enterable, have a
--      default, and have that default ANNOUNCED rather than silently applied. The box section had
--      no honest default and got none; a thickness does - «до 2 см» plasterwork is 15 mm of real
--      work, «від 5 см» is 60, a self-levelling floor is 5 - and material_norm.default_param
--      carries it so the app can pre-fill a field the master can see and change.
--
--   4. TEN POSITIONS WERE FILED UNDER ONE TRADE AND WORKED FOR TWO. «Грунтування», «Обезпилення
--      поверхні», «Захист підлоги картоном», «Поклейка склополотна», «Шпаклювання фінішне (2-4
--      рази)» and five more are shipped by BOTH the drywall and the painting catalogs, but their
--      V127 norms carried trade = 'DRYWALL', so a painter's line got no answer at all. A norm that
--      carries NO trade answers for anyone (V132, the normsFor ladder), which is what these are.
--      Re-filed rather than duplicated: two rows saying the same thing is how figures drift apart.
--      The same treatment goes to the organisational positions tiling and painting both ship
--      («Прибирання приміщення після робіт», «Розвантаження матеріалу»...).
--
--   5. THE DEEP PRIMER NORM DISAGREED WITH ITSELF. V127 wrote 0,12 l/m2 for «Грунтування» and 0,15
--      for «Монтаж гіпсокартону на клей» - one material, one coat, two numbers. The datasheet
--      survey (NORMS-SUMMARY 2.4, n = 14, range 0,05-0,3) settles on 0,15 for one dictionary row.
--
-- WHAT IS DELIBERATELY LEFT UNNORMED (and so lands in the coverage report by construction):
--   - EPOXY grout, in all of its positions. No manufacturer publishes a per-m2 figure that
--     survived the survey, and an invented number for the most expensive grout on the market is
--     worse than an honest gap.
--   - Decorative plasters other than короїд / баранець (венеціанська, травертин, мікробетон,
--     марморіно, мікроцемент, «1000 ліній»...). Each is a different product with its own rate and
--     its own grain parameter, which NORMS-SUMMARY names as still missing.
--   - «Штукатурка укосів» and every other LINEAR_METER plastered reveal: a plastered reveal needs
--     BOTH a width and a thickness, and one position asks for one number. V131 refused the same
--     question for «Монтаж укосів із гіпсокартону»; asking the wrong question is worse than
--     asking none. A TILED reveal or stair is fine - an area needs one figure, so it is SECTION.
--   - Paints outside the interior wall/ceiling pair: facade paint, lacquer, enamel on wood and
--     metal. Each is its own dictionary material with its own rate; none is in the dictionary yet.
--   - Products the master chooses rather than consumes: піддони, трапи, люки, сифони, мембрани,
--     плівка, риштування.
--   - The ГКЛ constructions inside the tiling and painting catalogs (фальшстіна, конструкція під
--     інсталяцію, каркасна і безкаркасна звукоізоляція). Their figures live in V127 under DRYWALL
--     and copying them here is exactly the drift point 4 is about.
--
-- No catalog_templates row is inserted, so V118's ranking is not re-run - there is nothing to rank.
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 1. Schema: the fourth basis, and the suggestion that makes it answerable.
-- -------------------------------------------------------------------------------------------------
ALTER TABLE material_norm DROP CONSTRAINT material_norm_basis_check;
ALTER TABLE material_norm ADD CONSTRAINT material_norm_basis_check
    CHECK (basis IN ('QUANTITY', 'PERIMETER', 'SECTION', 'THICKNESS'));

COMMENT ON COLUMN material_norm.basis IS
    'What qty_per_unit multiplies. QUANTITY = the estimate line quantity. PERIMETER = the room perimeter in metres, asked once per estimate. SECTION = the box section (розгортка) in metres, asked once per POSITION; amount = length x section x qty_per_unit. THICKNESS = the layer thickness in MILLIMETRES, asked once per POSITION; amount = area x mm x qty_per_unit, so qty_per_unit is written per m2 PER MM.';

-- The figure to put in the field the master is asked to fill, in that parameter's own unit (metres
-- for a SECTION, millimetres for a THICKNESS). NULL means we have no honest suggestion: a короб's
-- розгортка is anywhere between 0,2 m and 1,2 m and pre-filling one would pass a guess off as our
-- answer, which is why V131's box norms leave it empty.
ALTER TABLE material_norm ADD COLUMN default_param numeric(15, 4);
ALTER TABLE material_norm ADD CONSTRAINT material_norm_default_param_check
    CHECK (default_param IS NULL OR default_param > 0);

COMMENT ON COLUMN material_norm.default_param IS
    'Suggested value for the figure this norm ASKS for (basis PERIMETER/SECTION/THICKNESS), in that parameter''s own unit. Surfaced on MissingParameter so the app can pre-fill the input VISIBLY - open-questions section 15: a default is announced, never silently applied. NULL = no honest suggestion exists.';

-- -------------------------------------------------------------------------------------------------
-- 2. The three preference keys that were the wrong kind of answer - header point 1.
--    Rows first: the CHECK is re-created, and a stored value for a dropped key would refuse it.
-- -------------------------------------------------------------------------------------------------
DELETE FROM master_material_pref
 WHERE pref_key IN ('TILE_SIZE', 'TILE_THICKNESS_MM', 'TILE_LAYOUT');

ALTER TABLE master_material_pref DROP CONSTRAINT master_material_pref_key_check;
ALTER TABLE master_material_pref ADD CONSTRAINT master_material_pref_key_check
    CHECK (pref_key IN ('TILE_JOINT_MM', 'PAINT_COVERAGE', 'PAINT_COATS', 'GKL_SHEET',
                        'WASTE_PERCENT'));

-- -------------------------------------------------------------------------------------------------
-- 3. Dictionary rows. Packaging is the size the survey (NORMS-SUMMARY section 4) found most often
--    on the shelf; where two are equally common the SMALLER wins, because rounding up to a package
--    he does not need is the error the master pays for.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    -- ---- TILING ---------------------------------------------------------------------------------
    (gen_random_uuid(), 'TILE_ADHESIVE_C1',  'Клей для плитки цементний C1',         NULL, 'KG', 25, 'KG', 'мішок'),
    (gen_random_uuid(), 'TILE_ADHESIVE_C2',  'Клей для плитки еластичний C2',        NULL, 'KG', 25, 'KG', 'мішок'),
    (gen_random_uuid(), 'TILE_GROUT',        'Затирка для швів цементна',            NULL, 'KG', 2,  'KG', 'пачка'),
    (gen_random_uuid(), 'WATERPROOF_CEMENT', 'Гідроізоляція обмазувальна цементна',  NULL, 'KG', 25, 'KG', 'мішок'),
    (gen_random_uuid(), 'WATERPROOF_MASTIC', 'Гідроізоляція обмазувальна полімерна', NULL, 'KG', 7,  'KG', 'відро'),
    (gen_random_uuid(), 'SELF_LEVELLING',    'Суміш самовирівнювальна',              NULL, 'KG', 25, 'KG', 'мішок'),
    (gen_random_uuid(), 'SCREED_CEMENT',     'Стяжка цементна (суха суміш)',         NULL, 'KG', 25, 'KG', 'мішок'),
    -- A waterproofing tape comes in 10 m and 50 m rolls depending on the merchant, and V127's rule
    -- is that packaging we cannot pick is better left absent than guessed.
    (gen_random_uuid(), 'TAPE_WATERPROOF',   'Стрічка гідроізоляційна',              NULL, 'LINEAR_METER', NULL, NULL, NULL),

    -- ---- PAINTER --------------------------------------------------------------------------------
    (gen_random_uuid(), 'PLASTER_GYPSUM',         'Штукатурка гіпсова',             NULL, 'KG', 30, 'KG', 'мішок'),
    (gen_random_uuid(), 'PLASTER_GYPSUM_MACHINE', 'Штукатурка гіпсова машинна',     NULL, 'KG', 30, 'KG', 'мішок'),
    (gen_random_uuid(), 'PLASTER_CEMENT_LIME',    'Штукатурка цементно-вапняна',    NULL, 'KG', 25, 'KG', 'мішок'),
    (gen_random_uuid(), 'PUTTY_START_GYPSUM',     'Шпаклівка стартова гіпсова',     NULL, 'KG', 25, 'KG', 'мішок'),
    (gen_random_uuid(), 'PRIMER_QUARTZ',          'Ґрунт-фарба кварцова',           NULL, 'KG', 15, 'KG', 'відро'),
    (gen_random_uuid(), 'PRIMER_CONTACT',         'Ґрунт бетоноконтакт',            NULL, 'KG', 15, 'KG', 'відро'),
    (gen_random_uuid(), 'PAINT_INTERIOR',         'Фарба інтер''єрна',              NULL, 'LITRE', 5, 'LITRE', 'відро'),
    (gen_random_uuid(), 'PAINT_CEILING',          'Фарба для стелі',                NULL, 'LITRE', 5, 'LITRE', 'відро'),
    (gen_random_uuid(), 'PLASTER_DECOR',          'Штукатурка декоративна',         NULL, 'KG', 25, 'KG', 'мішок'),
    -- A 300 g packet covers ~35 m2 of fleece wallpaper, so the PACKET is the unit he buys in.
    (gen_random_uuid(), 'WALLPAPER_GLUE',         'Клей для шпалер',                NULL, 'KG', 0.3, 'KG', 'пачка'),
    (gen_random_uuid(), 'MESH_FIBERGLASS',        'Сітка скловолоконна штукатурна', NULL, 'M2', NULL, NULL, NULL);

-- -------------------------------------------------------------------------------------------------
-- 4. The positions two trades both ship - header point 4. A norm with no trade answers for anyone,
--    which is what a dust-off, a floor covered in cardboard or a trip to the shop actually is.
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm
   SET trade = NULL
 WHERE owner_id IS NULL
   AND trade = 'DRYWALL'
   AND name_key IN ('базове шпаклювання під скловолокно',
                    'герметизація швів стиків герметиком',
                    'грунтування',
                    'захист підлоги картоном',
                    'звукоізоляція стін мінеральною ватою',
                    'обезпилення поверхні',
                    'поклейка склополотна',
                    'шліфування під скловолокно/склохолст',
                    'шліфування стін/стель (фінішне)',
                    'шпаклювання фінішне (2–4 рази)');

-- Header point 5: one material, one coat, one figure.
UPDATE material_norm
   SET qty_per_unit = 0.15
 WHERE owner_id IS NULL
   AND name_key = 'грунтування'
   AND material_id = (SELECT id FROM material WHERE code = 'PRIMER_DEEP');

-- =================================================================================================
-- 5. TILING norms.
--
-- Adhesive is chosen by NOTCH, and the notch by the tile format the position name already states
-- (NORMS-SUMMARY 2.1-2.2, by the tile's LONGER side): <=10 cm -> 4 mm -> 2,6 kg/m2; 10-20 -> 6 mm
-- -> 3,9; 20-30 -> 8 mm -> 5,2; 30-40 -> 10 mm -> 6,5; >=40 -> 12 mm -> 7,8. From 60x60 up the
-- tile is laid on elastic C2 with back-buttering, which the datasheets put at ~8,5 kg/m2.
-- Grout is 0,4 kg/m2 at a 2-3 mm joint, 0,6 for mosaic, 0,8 from 3 mm up (NORMS-SUMMARY 2.3).
-- =================================================================================================
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order, default_param)
SELECT gen_random_uuid(), 'TILING', v.name_key, v.unit, m.id, v.qty, v.basis, v.ord, v.def
FROM (VALUES
    -- ---- Укладання плитки: format -> notch -> adhesive --------------------------------------------
    ('укладання плитки 100х100', 'M2', 'TILE_ADHESIVE_C1', 2.6::numeric, 'QUANTITY', 1, NULL::numeric),
    ('укладання плитки 100х100', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 100х100', 'M2', 'TILE_GROUT',       0.4,  'QUANTITY', 3, NULL),
    ('укладання плитки 200х200', 'M2', 'TILE_ADHESIVE_C1', 3.9,  'QUANTITY', 1, NULL),
    ('укладання плитки 200х200', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 200х200', 'M2', 'TILE_GROUT',       0.4,  'QUANTITY', 3, NULL),
    ('укладання плитки 300х300', 'M2', 'TILE_ADHESIVE_C1', 5.2,  'QUANTITY', 1, NULL),
    ('укладання плитки 300х300', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 300х300', 'M2', 'TILE_GROUT',       0.4,  'QUANTITY', 3, NULL),
    ('укладання плитки 300х600', 'M2', 'TILE_ADHESIVE_C1', 7.8,  'QUANTITY', 1, NULL),
    ('укладання плитки 300х600', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 300х600', 'M2', 'TILE_GROUT',       0.4,  'QUANTITY', 3, NULL),
    ('укладання плитки 300х900', 'M2', 'TILE_ADHESIVE_C1', 7.8,  'QUANTITY', 1, NULL),
    ('укладання плитки 300х900', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 300х900', 'M2', 'TILE_GROUT',       0.4,  'QUANTITY', 3, NULL),
    ('укладання плитки 600х600', 'M2', 'TILE_ADHESIVE_C2', 8.5,  'QUANTITY', 1, NULL),
    ('укладання плитки 600х600', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 600х600', 'M2', 'TILE_GROUT',       0.4,  'QUANTITY', 3, NULL),
    ('укладання плитки 600х1200', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання плитки 600х1200', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 600х1200', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання плитки 800х800', 'M2', 'TILE_ADHESIVE_C2', 8.5,  'QUANTITY', 1, NULL),
    ('укладання плитки 800х800', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 800х800', 'M2', 'TILE_GROUT',       0.4,  'QUANTITY', 3, NULL),
    ('укладання плитки 800х1600', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання плитки 800х1600', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 800х1600', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання плитки 1000х1000', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання плитки 1000х1000', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 1000х1000', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання плитки 1000х2000 мм', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання плитки 1000х2000 мм', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 1000х2000 мм', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання плитки 1000х3000 мм', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання плитки 1000х3000 мм', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 1000х3000 мм', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання плитки 1200х1200 мм', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання плитки 1200х1200 мм', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 1200х1200 мм', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання плитки 1200х2400 мм', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання плитки 1200х2400 мм', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 1200х2400 мм', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання плитки 1200х3200 мм', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання плитки 1200х3200 мм', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 1200х3200 мм', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання плитки 1500х3000 мм', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання плитки 1500х3000 мм', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 1500х3000 мм', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання плитки 1600х1600 мм', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання плитки 1600х1600 мм', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 1600х1600 мм', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання плитки 1600х3200 мм', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання плитки 1600х3200 мм', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки 1600х3200 мм', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання плитки більше 3200 мм', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання плитки більше 3200 мм', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки більше 3200 мм', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання плитки дошка до 900 мм', 'M2', 'TILE_ADHESIVE_C1', 6.5, 'QUANTITY', 1, NULL),
    ('укладання плитки дошка до 900 мм', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки дошка до 900 мм', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання плитки дошка до 1200 мм', 'M2', 'TILE_ADHESIVE_C1', 7.8, 'QUANTITY', 1, NULL),
    ('укладання плитки дошка до 1200 мм', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки дошка до 1200 мм', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання плитки дошка до 1800 мм', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання плитки дошка до 1800 мм', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки дошка до 1800 мм', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання плитки кабанчик, «цегла»', 'M2', 'TILE_ADHESIVE_C1', 3.9, 'QUANTITY', 1, NULL),
    ('укладання плитки кабанчик, «цегла»', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання плитки кабанчик, «цегла»', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання керамічного паркету, дрібної дошки', 'M2', 'TILE_ADHESIVE_C1', 6.5, 'QUANTITY', 1, NULL),
    ('укладання керамічного паркету, дрібної дошки', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання керамічного паркету, дрібної дошки', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('укладання керамограніту 20 мм', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання керамограніту 20 мм', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 2, NULL),
    ('укладання керамограніту на вулиці', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання керамограніту на вулиці', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 2, NULL),
    ('укладання клінкерної підлогової плитки', 'M2', 'TILE_ADHESIVE_C1', 5.2, 'QUANTITY', 1, NULL),
    ('укладання клінкерної підлогової плитки', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання клінкерної підлогової плитки', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    -- Мозаїка sits on a thin bed and the joints eat the grout (NORMS-SUMMARY 2.3).
    ('укладання мозаїки', 'M2', 'TILE_ADHESIVE_C2', 3.9, 'QUANTITY', 1, NULL),
    ('укладання мозаїки', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання мозаїки', 'M2', 'TILE_GROUT',       0.6, 'QUANTITY', 3, NULL),
    -- Decorative gypsum/concrete brick is grouted by its own position, so no grout row here.
    ('укладання декоративної плитки під «цеглу» або «камінь» (гіпсова, бетонна)', 'M2', 'TILE_ADHESIVE_C1', 3.9, 'QUANTITY', 1, NULL),
    ('укладання декоративної плитки під «цеглу» або «камінь» (гіпсова, бетонна)', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    -- The thick-bed position names the bed and not its depth, so it ASKS. 1,3-1,4 kg per m2 per mm
    -- of solid layer (NORMS-SUMMARY 2.1); 15 mm is the usual answer above the 1 cm the name states.
    ('укладання плитки на шар клею більше 1 см', 'M2', 'TILE_ADHESIVE_C1', 1.35, 'THICKNESS', 1, 15),

    -- ---- Промислове облицювання -------------------------------------------------------------------
    ('облицювання стандартною плиткою обсягів', 'M2', 'TILE_ADHESIVE_C1', 5.2, 'QUANTITY', 1, NULL),
    ('облицювання стандартною плиткою обсягів', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('облицювання стандартною плиткою обсягів', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('облицювання широкоформатною плиткою обсягів', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('облицювання широкоформатною плиткою обсягів', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('облицювання широкоформатною плиткою обсягів', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),
    ('облицювання мозаїкою обсягів', 'M2', 'TILE_ADHESIVE_C2', 3.9, 'QUANTITY', 1, NULL),
    ('облицювання мозаїкою обсягів', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('облицювання мозаїкою обсягів', 'M2', 'TILE_GROUT',       0.6, 'QUANTITY', 3, NULL),
    -- Acid-resistant tiling is grouted with epoxy, which is a deliberate gap - adhesive only.
    ('облицювання кислотостійкою плиткою', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('облицювання каменем обсягів', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('облицювання каменем обсягів', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('облицювання плиткою короба', 'M2', 'TILE_ADHESIVE_C1', 5.2, 'QUANTITY', 1, NULL),
    ('облицювання плиткою короба', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('облицювання плиткою короба', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 3, NULL),

    -- ---- Натуральний камінь -----------------------------------------------------------------------
    ('укладання натурального каменю граніту, мармуру', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання натурального каменю граніту, мармуру', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання великих слябів з каменю', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання великих слябів з каменю', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('укладання "дикого каменю" піщаник, сланець', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('укладання "соломки" з каменю', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),

    -- ---- Фасад, басейн, ремонт облицювання --------------------------------------------------------
    ('облицювання басейнів', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('облицювання радіусних поверхонь мозаїкою', 'M2', 'TILE_ADHESIVE_C2', 3.9, 'QUANTITY', 1, NULL),
    ('облицювання радіусних поверхонь мозаїкою', 'M2', 'PRIMER_DEEP',      0.15, 'QUANTITY', 2, NULL),
    ('облицювання радіусних поверхонь мозаїкою', 'M2', 'TILE_GROUT',       0.6, 'QUANTITY', 3, NULL),
    ('облицювання будинків клінкером «під цеглу»', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    ('облицювання будинків клінкером «під цеглу»', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 2, NULL),
    ('облицювання «диким каменем» фасаду будинку, парканів', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),
    -- A repair re-lays a patch, so it consumes what laying consumes - just over a smaller area.
    ('ремонт облицювання з плитки', 'M2', 'TILE_ADHESIVE_C1', 5.2, 'QUANTITY', 1, NULL),
    ('ремонт облицювання з плитки', 'M2', 'TILE_GROUT',       0.4, 'QUANTITY', 2, NULL),
    ('ремонт облицювання з мозаїки', 'M2', 'TILE_ADHESIVE_C2', 3.9, 'QUANTITY', 1, NULL),
    ('ремонт облицювання з мозаїки', 'M2', 'TILE_GROUT',       0.6, 'QUANTITY', 2, NULL),
    ('ремонт облицювання з каменю', 'M2', 'TILE_ADHESIVE_C2', 8.5, 'QUANTITY', 1, NULL),


    -- ---- Затирання швів as its own position -------------------------------------------------------
    ('заповнення швів цементною сумішшю', 'M2', 'TILE_GROUT', 0.4, 'QUANTITY', 1, NULL),
    ('заповнення швів цементною сумішшю з латексом', 'M2', 'TILE_GROUT', 0.4, 'QUANTITY', 1, NULL),
    ('затирання швів цементною сумішшю з латексом', 'M2', 'TILE_GROUT', 0.4, 'QUANTITY', 1, NULL),
    ('затирання швів від 3 мм цементною сумішшю', 'M2', 'TILE_GROUT', 0.8, 'QUANTITY', 1, NULL),
    ('затирання швів у декоративній плитці, мозаїці', 'M2', 'TILE_GROUT', 0.6, 'QUANTITY', 1, NULL),
    ('заміна затірки швів', 'M2', 'TILE_GROUT', 0.4, 'QUANTITY', 1, NULL),
    ('заповнення товстого шва напівсухою сумішшю', 'M2', 'TILE_GROUT', 0.8, 'QUANTITY', 1, NULL),
    -- A movement joint is closed with sealant, not grout - that is what makes it a movement joint.
    ('заповнення швів герметиком', 'LINEAR_METER', 'ACOUSTIC_SEALANT', 0.025, 'QUANTITY', 1, NULL),

    -- ---- Гідроізоляція (NORMS-SUMMARY 2.6) ---------------------------------------------------------
    ('влаштування гідроізоляції', 'M2', 'WATERPROOF_CEMENT', 3.0, 'QUANTITY', 1, NULL),
    ('влаштування гідроізоляції', 'M2', 'PRIMER_DEEP',       0.15, 'QUANTITY', 2, NULL),
    ('гідроізоляція сухою сумішшю', 'M2', 'WATERPROOF_CEMENT', 3.0, 'QUANTITY', 1, NULL),
    ('гідроізоляція сухою сумішшю', 'M2', 'PRIMER_DEEP',       0.15, 'QUANTITY', 2, NULL),
    ('нанесення двокомпонентної гідроізоляції', 'M2', 'WATERPROOF_CEMENT', 3.0, 'QUANTITY', 1, NULL),
    ('нанесення двокомпонентної гідроізоляції', 'M2', 'PRIMER_DEEP',       0.15, 'QUANTITY', 2, NULL),
    ('нанесення однокомпонентної гідроізоляції', 'M2', 'WATERPROOF_MASTIC', 1.5, 'QUANTITY', 1, NULL),
    ('нанесення однокомпонентної гідроізоляції', 'M2', 'PRIMER_DEEP',       0.15, 'QUANTITY', 2, NULL),
    -- A pool is wet permanently, which the datasheets price at 4,5 kg/m2 rather than 3,0.
    ('гідроізоляція басейну', 'M2', 'WATERPROOF_CEMENT', 4.5, 'QUANTITY', 1, NULL),
    ('гідроізоляція басейну', 'M2', 'PRIMER_DEEP',       0.15, 'QUANTITY', 2, NULL),
    ('армування кутів гідроізоляційної стрічкою', 'LINEAR_METER', 'TAPE_WATERPROOF', 1.05, 'QUANTITY', 1, NULL),

    -- ---- Основа: everything here is per m2 PER MM, so everything here ASKS ------------------------
    ('стяжка маякова цементна', 'M2', 'SCREED_CEMENT', 2.0, 'THICKNESS', 1, 40),
    ('влаштування наливної підлоги', 'M2', 'SELF_LEVELLING', 1.8, 'THICKNESS', 1, 5),
    ('влаштування наливної підлоги', 'M2', 'PRIMER_DEEP',    0.15, 'QUANTITY', 2, NULL),
    ('штукатурка маякова цементна', 'M2', 'PLASTER_CEMENT_LIME', 1.6, 'THICKNESS', 1, 20),
    ('штукатурка маякова цементна', 'M2', 'PRIMER_CONTACT',      0.3, 'QUANTITY', 2, NULL),
    ('штукатурка, стяжка басейну', 'M2', 'PLASTER_CEMENT_LIME', 1.6, 'THICKNESS', 1, 20),
    ('вирівнювання поверхні шаром клею', 'M2', 'TILE_ADHESIVE_C1', 1.35, 'THICKNESS', 1, 5),
    ('ґрунтівка поверхні', 'M2', 'PRIMER_DEEP', 0.15, 'QUANTITY', 1, NULL),

    -- ---- Сходи, відкоси, підвіконня: a metre of stair is an AREA once its розгортка is known.
    --      This is V131's SECTION question and it takes ONE number, unlike a plastered reveal.
    ('укладання плитки на сходи та підсходинок', 'LINEAR_METER', 'TILE_ADHESIVE_C2', 8.5, 'SECTION', 1, 0.45),
    ('укладання плитки на сходи та підсходинок', 'LINEAR_METER', 'TILE_GROUT',       0.4, 'SECTION', 2, 0.45),
    ('облицювання сходових маршів', 'LINEAR_METER', 'TILE_ADHESIVE_C2', 8.5, 'SECTION', 1, 0.45),
    ('облицювання сходових маршів', 'LINEAR_METER', 'TILE_GROUT',       0.4, 'SECTION', 2, 0.45),
    ('облицювання радіусних сходів (без підступка)', 'LINEAR_METER', 'TILE_ADHESIVE_C2', 8.5, 'SECTION', 1, 0.3),
    ('облицювання радіусних сходів (без підступка)', 'LINEAR_METER', 'TILE_GROUT',       0.4, 'SECTION', 2, 0.3),
    ('облицювання каменем сходів', 'LINEAR_METER', 'TILE_ADHESIVE_C2', 8.5, 'SECTION', 1, 0.45),
    ('облицювання каменем відкосів, підвіконь', 'LINEAR_METER', 'TILE_ADHESIVE_C2', 8.5, 'SECTION', 1, 0.3)
) AS v(name_key, unit, code, qty, basis, ord, def)
JOIN material m ON m.code = v.code;

-- «Consumes nothing» is a RECORDED verdict, not an absent row (V127): without these the coverage
-- ratio reads «24 of 61» on an estimate that is in fact answered in full.
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'TILING', v.name_key, v.unit, NULL, NULL, 'QUANTITY', 1
FROM (VALUES
    ('демонтаж плитки', 'M2'),
    ('демонтаж фарби', 'M2'),
    ('демонтаж штукатурки, стяжки', 'M2'),
    ('очищення, обезпилювання', 'M2'),
    ('шліфування стяжки, вирівнювання до 5 мм', 'M2'),
    ('шліфування видалення слабкого шару, молочка', 'M2'),
    ('сортування плитки та підбір малюнка', 'M2'),
    ('полірування каменю', 'M2'),
    ('порізка широкоформатної плитки', 'LINEAR_METER'),
    ('заусовка під 45° кута широкоформатної плитки', 'LINEAR_METER'),
    ('різ плітки під 45° заусовка', 'LINEAR_METER'),
    ('різ плітки під 90° (чорновий різ)', 'LINEAR_METER'),
    ('різ плітки під 90° зі шліфуванням (чистовий різ)', 'LINEAR_METER'),
    ('різ плітки радіальний, фігурний', 'LINEAR_METER'),
    ('фрезерування плитки під плінтус, сходи', 'LINEAR_METER'),
    ('виріз отворів до 70 мм', 'PIECE'),
    ('виріз отворів більше 70 мм', 'PIECE'),
    ('виріз в плитці г-образний', 'PIECE'),
    ('виріз отвору в плитці великого формату', 'PIECE'),
    ('замір приміщення та схема розкладки плитки', 'PIECE'),
    ('виїзд майстра в магазин для підбору плитки', 'PIECE'),
    ('набір малюнка з мозаїки', 'M2'),
    ('вирівнювання плитки під час збору кута 90°', 'LINEAR_METER'),
    -- Surcharges for a small job, a day of work and a flight of stairs buy nothing.
    ('обсяг замовлення менше 10 м²', 'M2'),
    ('обсяг замовлення менше 25 м²', 'M2'),
    ('день роботи майстра', 'DAY'),
    ('підняття широкоформатної плитки по сходах', 'FLOOR')
) AS v(name_key, unit);

-- =================================================================================================
-- 6. PAINTER norms.
--
-- Plaster and start putty are consumed per m2 PER MM (NORMS-SUMMARY 3.1-3.4): hand gypsum 1,0,
-- machine gypsum 0,95, cement-lime 1,4. So they ASK, and the figure the master is shown comes from
-- the bound in the position's own name - «до 2 см» is 15 mm of real work, «від 2 см» is 30,
-- «від 5 см» is 60. Paint is 9 m2 per litre per coat over 2 coats = 0,22 l/m2 (NORMS-SUMMARY 3.8).
-- =================================================================================================
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order, default_param)
SELECT gen_random_uuid(), 'PAINTER', v.name_key, v.unit, m.id, v.qty, v.basis, v.ord, v.def
FROM (VALUES
    -- ---- Штукатурка -------------------------------------------------------------------------------
    ('штукатурка стін (до 2 см)', 'M2', 'PLASTER_GYPSUM', 1.0::numeric, 'THICKNESS', 1, 15::numeric),
    ('штукатурка стін (до 2 см)', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 2, NULL),
    ('штукатурка стін (від 2 см)', 'M2', 'PLASTER_GYPSUM', 1.0, 'THICKNESS', 1, 30),
    ('штукатурка стін (від 2 см)', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 2, NULL),
    ('штукатурка стін (від 5 см)', 'M2', 'PLASTER_GYPSUM', 1.0, 'THICKNESS', 1, 60),
    ('штукатурка стін (від 5 см)', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 2, NULL),
    ('штукатурка стін ручна до 2см', 'M2', 'PLASTER_GYPSUM', 1.0, 'THICKNESS', 1, 15),
    ('штукатурка стін ручна до 2см', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 2, NULL),
    ('штукатурка стін ручна від 2см', 'M2', 'PLASTER_GYPSUM', 1.0, 'THICKNESS', 1, 30),
    ('штукатурка стін ручна від 2см', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 2, NULL),
    ('штукатурка стін ручна від 5см', 'M2', 'PLASTER_GYPSUM', 1.0, 'THICKNESS', 1, 60),
    ('штукатурка стін ручна від 5см', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 2, NULL),
    ('штукатурка стін до 2см об''ємом до 50м2', 'M2', 'PLASTER_GYPSUM', 1.0, 'THICKNESS', 1, 15),
    ('штукатурка стін до 2см об''ємом до 50м2', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 2, NULL),
    ('штукатурка стін від 2см об''ємом до 50м2', 'M2', 'PLASTER_GYPSUM', 1.0, 'THICKNESS', 1, 30),
    ('штукатурка стін від 2см об''ємом до 50м2', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 2, NULL),
    ('штукатурка криволінійних площин', 'M2', 'PLASTER_GYPSUM', 1.0, 'THICKNESS', 1, 15),
    ('штукатурка криволінійних площин', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 2, NULL),
    ('штукатурні роботи (від)', 'M2', 'PLASTER_GYPSUM', 1.0, 'THICKNESS', 1, 15),
    ('штукатурні роботи (від)', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 2, NULL),
    ('штукатурка стін машинкою до 2см', 'M2', 'PLASTER_GYPSUM_MACHINE', 0.95, 'THICKNESS', 1, 15),
    ('штукатурка стін машинкою до 2см', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 2, NULL),
    ('штукатурка стін машинкою понад 2см', 'M2', 'PLASTER_GYPSUM_MACHINE', 0.95, 'THICKNESS', 1, 30),
    ('штукатурка стін машинкою понад 2см', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 2, NULL),
    ('машинна штукатурка стін', 'M2', 'PLASTER_GYPSUM_MACHINE', 0.95, 'THICKNESS', 1, 20),
    ('машинна штукатурка стін', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 2, NULL),
    ('машинна штукатурка стель', 'M2', 'PLASTER_GYPSUM_MACHINE', 0.95, 'THICKNESS', 1, 15),
    ('машинна штукатурка стель', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 2, NULL),
    ('місцевий ремонт цементно-вапняної штукатурки (перетяжка)', 'M2', 'PLASTER_CEMENT_LIME', 1.4, 'THICKNESS', 1, 10),
    ('місцевий ремонт цементно-вапняної штукатурки (перетяжка)', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 2, NULL),

    -- ---- Ґрунтування (NORMS-SUMMARY 2.5, 3.5, 3.7) --------------------------------------------------
    ('грунтовка поверхонь бетоноконтактом', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 1, NULL),
    ('грунтовка поверхонь перед штукатуркою армуванням', 'M2', 'PRIMER_CONTACT', 0.3, 'QUANTITY', 1, NULL),
    ('грунтовка поверхні кварцгрунтом', 'M2', 'PRIMER_QUARTZ', 0.3, 'QUANTITY', 1, NULL),
    ('грунтування кварцгрунтом', 'M2', 'PRIMER_QUARTZ', 0.3, 'QUANTITY', 1, NULL),
    ('ґрунтування основи під мікроцемент', 'M2', 'PRIMER_QUARTZ', 0.3, 'QUANTITY', 1, NULL),
    ('грунт-фарба (праймер під фарбу)', 'M2', 'PRIMER_FILLER', 0.18, 'QUANTITY', 1, NULL),
    ('фарбування грунт-фарбою', 'M2', 'PRIMER_FILLER', 0.18, 'QUANTITY', 1, NULL),
    ('грунтовка поверхонь перед шпаклівкою фарбуванням', 'M2', 'PRIMER_DEEP', 0.15, 'QUANTITY', 1, NULL),
    ('грунтовка поверхонь перед шпаклівкою, фарбуванням, поклейкою тощо', 'M2', 'PRIMER_DEEP', 0.15, 'QUANTITY', 1, NULL),

    -- ---- Шпаклювання ------------------------------------------------------------------------------
    ('шпаклювання стін (старт, за потреби)', 'M2', 'PUTTY_START_GYPSUM', 1.0, 'THICKNESS', 1, 3),
    ('шпаклювання стін (старт, за потреби)', 'M2', 'PRIMER_DEEP', 0.15, 'QUANTITY', 2, NULL),
    ('шпаклівка старт по цементній штукатурці', 'M2', 'PUTTY_START_GYPSUM', 1.0, 'THICKNESS', 1, 3),
    ('шпаклівка старт по цементній штукатурці', 'M2', 'PRIMER_DEEP', 0.15, 'QUANTITY', 2, NULL),
    ('шпаклювання стелі', 'M2', 'PUTTY_FINISH', 1.2, 'QUANTITY', 1, NULL),
    ('шпаклювання стелі', 'M2', 'PRIMER_DEEP', 0.15, 'QUANTITY', 2, NULL),
    ('шпаклівка стін під фарбування', 'M2', 'PUTTY_FINISH', 1.2, 'QUANTITY', 1, NULL),
    ('шпаклівка стін під фарбування', 'M2', 'PRIMER_DEEP', 0.15, 'QUANTITY', 2, NULL),
    ('шпаклівка стелі під фарбування', 'M2', 'PUTTY_FINISH', 1.2, 'QUANTITY', 1, NULL),
    ('шпаклівка стелі під фарбування', 'M2', 'PRIMER_DEEP', 0.15, 'QUANTITY', 2, NULL),
    ('шпаклівка стін під склополотно шпалери', 'M2', 'PUTTY_FINISH', 1.1, 'QUANTITY', 1, NULL),
    ('шпаклівка стелі під склополотно шпалери', 'M2', 'PUTTY_FINISH', 1.1, 'QUANTITY', 1, NULL),
    ('шпаклювання криволінійних площин 3 рази зі шліфуванням', 'M2', 'PUTTY_FINISH', 1.8, 'QUANTITY', 1, NULL),
    ('шпаклювання швів гкл та шурупів зі шліфуванням', 'M2', 'PUTTY_JOINT', 0.4, 'QUANTITY', 1, NULL),

    -- ---- Фарбування -------------------------------------------------------------------------------
    ('фарбування стін водоемульсійною фарбою', 'M2', 'PAINT_INTERIOR', 0.22, 'QUANTITY', 1, NULL),
    ('фарбування стін водоемульсійною фарбою', 'M2', 'PRIMER_DEEP', 0.15, 'QUANTITY', 2, NULL),
    ('фарбування стелі водоемульсійною фарбою', 'M2', 'PAINT_CEILING', 0.22, 'QUANTITY', 1, NULL),
    ('фарбування стелі водоемульсійною фарбою', 'M2', 'PRIMER_DEEP', 0.15, 'QUANTITY', 2, NULL),
    ('фарбування стін/стель (білий)', 'M2', 'PAINT_INTERIOR', 0.22, 'QUANTITY', 1, NULL),
    ('фарбування стін/стель (білий)', 'M2', 'PRIMER_DEEP', 0.15, 'QUANTITY', 2, NULL),
    ('фарбування стін/стель (у кольорі)', 'M2', 'PAINT_INTERIOR', 0.22, 'QUANTITY', 1, NULL),
    ('фарбування стін/стель (у кольорі)', 'M2', 'PRIMER_DEEP', 0.15, 'QUANTITY', 2, NULL),
    ('фарбування безповітряним методом (airless)', 'M2', 'PAINT_INTERIOR', 0.22, 'QUANTITY', 1, NULL),
    ('фарбування 3d панелей', 'M2', 'PAINT_INTERIOR', 0.22, 'QUANTITY', 1, NULL),
    ('фарбування 3 д панелей з підготовкою', 'M2', 'PAINT_INTERIOR', 0.22, 'QUANTITY', 1, NULL),

    -- ---- Декоративна штукатурка: only короїд / баранець carry a surveyed figure --------------------
    ('декоративна штукатурка фасаду короїд баранець', 'M2', 'PLASTER_DECOR', 2.8, 'QUANTITY', 1, NULL),
    ('декоративна штукатурка фасаду короїд баранець', 'M2', 'PRIMER_QUARTZ', 0.3, 'QUANTITY', 2, NULL),

    -- ---- Шпалери. A 300 g packet covers ~35 m2 of fleece (NORMS-SUMMARY 3.10) ----------------------
    ('поклейка шпалер 50см без підбору', 'M2', 'WALLPAPER_GLUE', 0.01, 'QUANTITY', 1, NULL),
    ('поклейка шпалер 50см з підбором', 'M2', 'WALLPAPER_GLUE', 0.01, 'QUANTITY', 1, NULL),
    ('поклейка шпалер 100см без підбору', 'M2', 'WALLPAPER_GLUE', 0.01, 'QUANTITY', 1, NULL),
    ('поклейка шпалер 100см з підбором', 'M2', 'WALLPAPER_GLUE', 0.01, 'QUANTITY', 1, NULL),
    ('поклейка шпалер шириною 50 см на стіну (без підбору)', 'M2', 'WALLPAPER_GLUE', 0.01, 'QUANTITY', 1, NULL),
    ('поклейка шпалер шириною 50 см на стіну (з підбором)', 'M2', 'WALLPAPER_GLUE', 0.01, 'QUANTITY', 1, NULL),
    ('поклейка шпалер шириною 100 см на стіну (без підбору)', 'M2', 'WALLPAPER_GLUE', 0.01, 'QUANTITY', 1, NULL),
    ('поклейка шпалер шириною 100 см на стіну (з підбором)', 'M2', 'WALLPAPER_GLUE', 0.01, 'QUANTITY', 1, NULL),
    ('поклейка фотошпалер', 'M2', 'WALLPAPER_GLUE', 0.01, 'QUANTITY', 1, NULL),

    -- ---- Армування --------------------------------------------------------------------------------
    ('армування сіткою', 'M2', 'MESH_FIBERGLASS', 1.1, 'QUANTITY', 1, NULL),
    ('армування стін сіткою', 'M2', 'MESH_FIBERGLASS', 1.1, 'QUANTITY', 1, NULL),
    ('армування фасадною сіткою', 'M2', 'MESH_FIBERGLASS', 1.1, 'QUANTITY', 1, NULL),
    ('армування фасаду сітка перетяжка', 'M2', 'MESH_FIBERGLASS', 1.1, 'QUANTITY', 1, NULL),
    ('армування стін скловолокном (склохолст)', 'M2', 'FIBERGLASS', 1.1, 'QUANTITY', 1, NULL),
    ('армування стін скловолокном (склохолст)', 'M2', 'FIBERGLASS_GLUE', 0.25, 'QUANTITY', 2, NULL),
    ('армування стель скловолокном', 'M2', 'FIBERGLASS', 1.1, 'QUANTITY', 1, NULL),
    ('армування стель скловолокном', 'M2', 'FIBERGLASS_GLUE', 0.25, 'QUANTITY', 2, NULL),
    ('приклеювання скловолокна на криволінійні площини', 'M2', 'FIBERGLASS', 1.1, 'QUANTITY', 1, NULL),
    ('приклеювання скловолокна на криволінійні площини', 'M2', 'FIBERGLASS_GLUE', 0.25, 'QUANTITY', 2, NULL),
    ('монтаж шпаклювальних кутиків', 'LINEAR_METER', 'ANGLE_PERFORATED', 1.05, 'QUANTITY', 1, NULL),
    ('монтаж шпаклювальних кутиків (арочних)', 'LINEAR_METER', 'ANGLE_PERFORATED', 1.05, 'QUANTITY', 1, NULL),
    ('установка перфорованих кутів на укоси, кути', 'LINEAR_METER', 'ANGLE_PERFORATED', 1.05, 'QUANTITY', 1, NULL),
    ('монтаж кутника внутрішнього/мет.', 'LINEAR_METER', 'ANGLE_PERFORATED', 1.05, 'QUANTITY', 1, NULL),
    ('поклейка стрічки «американка»', 'LINEAR_METER', 'TAPE_PAPER', 1.05, 'QUANTITY', 1, NULL),
    ('поклейка сітки в кути', 'LINEAR_METER', 'TAPE_SERPYANKA', 1.05, 'QUANTITY', 1, NULL),

    -- ---- Герметизація -----------------------------------------------------------------------------
    ('акрилення примикань', 'LINEAR_METER', 'ACOUSTIC_SEALANT', 0.025, 'QUANTITY', 1, NULL),
    ('герметизація швів, стиків акрилом, спеціальною мастикою', 'LINEAR_METER', 'ACOUSTIC_SEALANT', 0.025, 'QUANTITY', 1, NULL),
    ('герметизація швів стиків акрилом мастикою', 'LINEAR_METER', 'ACOUSTIC_SEALANT', 0.025, 'QUANTITY', 1, NULL),

    -- ---- Захист -----------------------------------------------------------------------------------
    ('укривання підлоги картоном', 'M2', 'CARDBOARD_FLOOR', 1.05, 'QUANTITY', 1, NULL),
    ('укривання підлоги картоном', 'M2', 'TAPE_MASKING', 0.5, 'QUANTITY', 2, NULL),
    ('розділення кольорів (скотч)', 'LINEAR_METER', 'TAPE_MASKING', 1.05, 'QUANTITY', 1, NULL),
    ('відведення лінії фарби довкола вікон/дверей', 'M2', 'TAPE_MASKING', 0.5, 'QUANTITY', 1, NULL),
    -- A door leaf takes about 2 m2 of cardboard and a roll's worth of tape to mask.
    ('захист вхідних дверей картоном', 'PIECE', 'CARDBOARD_FLOOR', 2.0, 'QUANTITY', 1, NULL),
    ('захист вхідних дверей картоном', 'PIECE', 'TAPE_MASKING', 5.0, 'QUANTITY', 2, NULL),

    -- ---- Укоси і короби. A PLASTERED reveal needs both a width and a thickness and so stays
    --      unnormed; a primed, puttied or painted one needs only the width, which is the SECTION
    --      question V131 already answers. 0,3 m is the usual розгортка of a window reveal.
    ('грунтування укосів', 'LINEAR_METER', 'PRIMER_DEEP', 0.15, 'SECTION', 1, 0.3),
    ('обезпилення та грунтування укосів перед фарбуванням', 'LINEAR_METER', 'PRIMER_DEEP', 0.15, 'SECTION', 1, 0.3),
    ('фарбування укосів', 'LINEAR_METER', 'PAINT_INTERIOR', 0.22, 'SECTION', 1, 0.3),
    ('фарбування укосів', 'LINEAR_METER', 'PRIMER_DEEP', 0.15, 'SECTION', 2, 0.3),
    ('шпаклівка коробів укосів ніш під фарбування', 'LINEAR_METER', 'PUTTY_FINISH', 1.2, 'SECTION', 1, 0.3),
    ('шпаклівка коробів укосів ніш під фарбування', 'LINEAR_METER', 'PRIMER_DEEP', 0.15, 'SECTION', 2, 0.3),
    ('шпаклівка коробів, укосів, ніш та виступів під фарбування', 'LINEAR_METER', 'PUTTY_FINISH', 1.2, 'SECTION', 1, 0.3),
    ('шпаклівка коробів, укосів, ніш та виступів під фарбування', 'LINEAR_METER', 'PRIMER_DEEP', 0.15, 'SECTION', 2, 0.3),

    -- ---- Стрічка по стиках, тіньових швах і врізаних приладах -------------------------------------
    ('армування стиків гкл', 'LINEAR_METER', 'TAPE_PAPER', 1.05, 'QUANTITY', 1, NULL),
    ('армування тіньового шва/люків стрічкою', 'LINEAR_METER', 'TAPE_PAPER', 1.05, 'QUANTITY', 1, NULL),
    ('армування врізних трекових світильників/вентиляційних дифузорів', 'LINEAR_METER', 'TAPE_PAPER', 1.05, 'QUANTITY', 1, NULL)
) AS v(name_key, unit, code, qty, basis, ord, def)
JOIN material m ON m.code = v.code;

INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'PAINTER', v.name_key, v.unit, NULL, NULL, 'QUANTITY', 1
FROM (VALUES
    ('демонтажні роботи', 'M2'),
    ('шліфування штукатурки', 'M2'),
    ('шліфування бетонних стін та стель від напливів бетону', 'M2'),
    ('шліфування торців бетонних колон від напливів бетону', 'LINEAR_METER'),
    ('шліфування стін після штукатурки (зробленої не нами)', 'M2'),
    ('ошкурення стін після шпаклівки (зробленої не нами, за погодженням)', 'M2'),
    ('дефектовка стін (зроблених не нами, за погодженням)', 'M2'),
    ('чищення бетонних плит /підготовчі роботи/', 'M2'),
    ('чищення бетонних плит підготовчі', 'M2'),
    ('пилосмоктання підлоги перед фарбуванням', 'M2'),
    ('підготовка криволінійних площин під скловолокно', 'M2'),
    ('збирання сміття в мішки після демонтажу', 'PIECE'),
    ('замір приміщення', 'PIECE'),
    ('виїзд майстра в магазин для підбору матеріалів', 'PIECE'),
    ('демонтаж батарей', 'PIECE'),
    -- Scaffolding is equipment the master already owns, not something he buys for the job.
    ('монтаж будівельного риштування', 'M2'),
    ('демонтаж будівельного риштування', 'M2')
) AS v(name_key, unit);

-- The organisational positions both catalogs ship - header point 4. No trade, so they answer
-- wherever they appear, and nothing is bought for a trip to the shop.
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), NULL, v.name_key, v.unit, NULL, NULL, 'QUANTITY', 1
FROM (VALUES
    ('винесення та вивезення будівельного сміття', 'M3'),
    ('виїзд для прорахунку вартості робіт та матеріалів', 'PIECE'),
    ('виїзд спеціаліста для консультації', 'PIECE'),
    ('гарантійний повторний виїзд', 'PIECE'),
    ('прибирання приміщення після робіт', 'M2'),
    ('підняття матеріалу по сходах', 'T'),
    ('розвантаження матеріалу', 'T'),
    ('транспортні витрати за містом', 'KM')
) AS v(name_key, unit);

-- -------------------------------------------------------------------------------------------------
-- 7. Self-checks. RAISE EXCEPTION guards DATA; a wording drift is only ever a WARNING.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    orphans text;
    homeless text;
    thickness_without_default int;
    ambiguous text;
    tiling_count int;
    painter_count int;
BEGIN
    -- 7a. Every name_key this migration writes must still name a live catalog position of that
    --     trade. A catalog rebuild renames positions, and a norm nobody can reach is invisible.
    SELECT string_agg(DISTINCT n.trade || ' / ' || n.name_key || ' [' || n.unit || ']', ', ')
      INTO orphans
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.trade IN ('TILING', 'PAINTER')
       AND NOT EXISTS (
            SELECT 1 FROM catalog_templates t
             WHERE t.trade = n.trade
               AND t.type = 'WORK'
               AND t.unit = n.unit
               AND lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) = n.name_key
       );
    IF orphans IS NOT NULL THEN
        RAISE EXCEPTION 'V137: material_norm rows name no live catalog position: %', orphans;
    END IF;

    -- 7b. A trade-less norm answers everywhere, so it must still name a position SOMEWHERE - the
    --     re-filing in section 4 is exactly the move that could strand one.
    SELECT string_agg(DISTINCT n.name_key || ' [' || n.unit || ']', ', ')
      INTO homeless
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.trade IS NULL
       AND NOT EXISTS (
            SELECT 1 FROM catalog_templates t
             WHERE t.type = 'WORK'
               AND t.unit = n.unit
               AND lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) = n.name_key
       );
    IF homeless IS NOT NULL THEN
        RAISE EXCEPTION 'V137: trade-less norms name no catalog position at all: %', homeless;
    END IF;

    -- 7c. A THICKNESS norm with no suggestion asks a question over an empty field. Point 3 is the
    --     whole reason default_param exists; a row that forgets it silently un-does it.
    SELECT count(*) INTO thickness_without_default
      FROM material_norm
     WHERE owner_id IS NULL AND basis = 'THICKNESS' AND default_param IS NULL;
    IF thickness_without_default > 0 THEN
        RAISE EXCEPTION 'V137: % THICKNESS norms carry no default_param', thickness_without_default;
    END IF;

    -- 7d. MaterialCalculatorIntegrationTest pins this and it is worth failing the migration over:
    --     two trades norming one (name, unit) cannot be resolved, so the answer would be a coin
    --     flip. Point 4 re-files rather than duplicates precisely to keep this empty.
    SELECT string_agg(name_key || ' [' || unit || ']', ', ')
      INTO ambiguous
      FROM (SELECT name_key, unit FROM material_norm
             WHERE owner_id IS NULL
             GROUP BY name_key, unit HAVING count(DISTINCT trade) > 1) AS a;
    IF ambiguous IS NOT NULL THEN
        RAISE EXCEPTION 'V137: a name and unit two trades both norm cannot be resolved: %', ambiguous;
    END IF;

    -- 7e. Row counts, so a half-applied VALUES list cannot pass quietly.
    SELECT count(DISTINCT name_key || '|' || unit) INTO tiling_count
      FROM material_norm WHERE owner_id IS NULL AND trade = 'TILING';
    SELECT count(DISTINCT name_key || '|' || unit) INTO painter_count
      FROM material_norm WHERE owner_id IS NULL AND trade = 'PAINTER';
    IF tiling_count <> 99 THEN
        RAISE EXCEPTION 'V137: expected 99 normed TILING positions, found %', tiling_count;
    END IF;
    IF painter_count <> 95 THEN
        RAISE EXCEPTION 'V137: expected 95 normed PAINTER positions, found %', painter_count;
    END IF;
END $$;
