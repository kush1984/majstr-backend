-- =================================================================================================
-- V147 — the trade-by-trade review, batch 2: the first material norms for FLOORING
--
-- Source: `C:\Work\prompts\trade-review\04-FLOORING.md` §3, read against a clean V145 DB. FLOORING
-- ships 54 WORK positions and, until this migration, exactly ZERO norms — so «Матеріали» answered
-- nothing at all for a floor layer. That is also why the set lands in ONE migration: V138 settled
-- that partial coverage is worse than none, because the screen switches ON and offers a buying list
-- of one line out of forty.
--
-- WHAT IS NORMED, AND HOW THE 54 POSITIONS ARE ACCOUNTED FOR:
--   * 40 positions get a material norm under `trade = 'FLOORING'`      (section 3)
--   * 5 positions BUILDER ships too get theirs at `trade = NULL`       (section 4)
--   * 7 positions get a recorded «consumes nothing» verdict            (section 5)
--   * 2 are deliberately left unnormed and say so                      (see below)
--
-- DELIBERATELY UNNORMED, and so visible in the coverage report by construction:
--   * «Машинна стяжка самовирівнююча» — the name describes either a semi-dry screed mixed on site
--     from cement, sand and fibre (the dictionary holds none of the three) or a machine-poured
--     anhydrite floor, which would be SELF_LEVELLING at 30-40 mm. Two incompatible products behind
--     one name is a RENAME first and a norm second (§1c of the report).
--   * «Монтаж та виготовлення ніші під плінтус прихованого монтажу» — the niche profile and the
--     filler it takes belong to a system we do not name, so the master types the quantity.
--
-- THE WASTE DECISION (report §3.1, §4 item 6) — AND WHY IT IS NOT WHAT THE REPORT RECOMMENDED:
-- the report asked for `waste_percent = 5` on every covering, because the old bucket applied the
-- MAXIMUM allowance in it to everything in it, so one diagonal position bought the whole flat's
-- laminate at the diagonal rate. **V146 fixed that in the engine**: each amount is now grown by its
-- OWN norm's allowance. With the bug gone, writing 5 here would do real harm instead — a norm's own
-- `waste_percent` OVERRIDES the master's `WASTE_PERCENT` habit, so a shipped 5 would SILENCE the
-- figure he set himself on the one material where his own cutting habit matters most. Every shipped
-- norm carries 0 for exactly that reason, and these do too.
--
-- What DOES belong in the coefficient is the LAYOUT surplus, because it is a property of the
-- pattern and not of his hand: a diagonal course needs ~5 % more plank than a straight one however
-- carefully it is cut. So «проста» is 1,00 and «по діагоналі» is 1,05, and his own allowance
-- multiplies on top of both.
--
-- THE FIVE SHARED POSITIONS (report §4 item 2, solved differently): the report asked for a
-- V132-style re-filing of `catalog_items.trade` for masters who have FLOORING, because V118's seed
-- array puts a position BUILDER and FLOORING both ship under BUILDER. V137 established the cheaper
-- answer and V146 used it ten more times: a norm with NO trade answers for anyone, so the five
-- positions are normed at `trade = NULL` and nothing in a master's own catalog is touched.
--
-- NO `catalog_templates` ROW IS INSERTED. Any migration that adds one must re-run V118's ranking
-- verbatim, which is a batch of its own — so the report's new LINEAR_METER damper-tape row (N38),
-- its new positions A1-A18 and their norms (§3.3) are absent, and the M2 damper-tape row keeps the
-- PERIMETER norm that is the only sensible reading of it.
--
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 1. Dictionary rows — 36 of them.
--
-- Packaging follows V127's rule: the smallest size commonly on the shelf, and NULL where we cannot
-- pick it, because rounding up to a package he does not need is the error the master pays for. That
-- is why every COVERING is NULL — a laminate pack is 1,5 to 2,6 m² depending on the product, so the
-- answer rounds to whole square metres and says nothing about packs. (A «pack area» habit beside
-- `GKL_SHEET` is the eventual fix; it is not this migration.)
--
-- Codes are NOT prefixed `PAINT_`, deliberately. That prefix is what the `PAINT_COVERAGE` habit
-- rescales, and it is the master's answer about HIS WALL PAINT. A parquet lacquer, a deck oil and a
-- 2K polyurethane are different products with coverages of their own, read off their data sheets —
-- V145 settled the same point for enamel and clear varnish.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    -- ---- Coverings: sold by the m², packed by the product ---------------------------------------
    (gen_random_uuid(), 'LAMINATE',          'Ламінат',                        NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'SPC_VINYL',         'Кварцвініл замковий (SPC)',      NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'LVT_VINYL',         'Кварцвініл клейовий (LVT)',      NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'FLOOR_ROLL',        'Лінолеум / ковролін рулонний',   NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'FLOOR_BOARD',       'Дошка підлогова',                NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'PARQUET_BLOCK',     'Паркет штучний',                 NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'DECK_BOARD',        'Дошка терасна',                  NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'UNDERLAY_LAMINATE', 'Підкладка під ламінат',          NULL, 'M2', NULL, NULL, NULL),
    -- A separate code on purpose: an SPC underlay is a dense 1-1,5 mm sheet, not a 3 mm foam, and
    -- merging the two would offer the wrong one for the covering the position names.
    (gen_random_uuid(), 'UNDERLAY_SPC',      'Підкладка під кварцвініл',       NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'PE_FILM',           'Плівка поліетиленова',           NULL, 'M2', NULL, NULL, NULL),

    -- ---- Screed and base ------------------------------------------------------------------------
    -- A screed mesh sheet is 1×2 m at one merchant and 2×3 at the next, keramzit is bagged at 0,04
    -- to 0,05 m³, a damper roll is 10, 20, 25 or 50 m. All three are left without a package.
    (gen_random_uuid(), 'MESH_SCREED',       'Сітка армувальна для стяжки',    NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'EXPANDED_CLAY',     'Керамзит',                       NULL, 'M3', NULL, NULL, NULL),
    (gen_random_uuid(), 'DAMPER_TAPE',       'Стрічка демпферна',              NULL, 'LINEAR_METER', NULL, NULL, NULL),

    -- ---- Adhesives: here the package IS published ------------------------------------------------
    (gen_random_uuid(), 'PARQUET_ADHESIVE',    'Клей паркетний (MS / поліуретановий)',    NULL, 'KG', 13, 'KG', 'банка'),
    (gen_random_uuid(), 'ADHESIVE_FLOOR_DISP', 'Клей дисперсійний для рулонних покриттів', NULL, 'KG', 3,  'KG', 'відро'),
    (gen_random_uuid(), 'MOUNTING_ADHESIVE',   'Клей монтажний',                           NULL, 'LITRE', 0.31, 'LITRE', 'картридж'),

    -- ---- Wood finishes --------------------------------------------------------------------------
    (gen_random_uuid(), 'PARQUET_PRIMER',  'Ґрунт-лак для паркету',             NULL, 'LITRE', NULL, NULL, NULL),
    (gen_random_uuid(), 'PARQUET_LACQUER', 'Лак паркетний',                     NULL, 'LITRE', 4.95, 'LITRE', 'каністра'),
    (gen_random_uuid(), 'PARQUET_FILLER',  'Зв''язувач для шпаклювання паркету', NULL, 'LITRE', NULL, NULL, NULL),
    (gen_random_uuid(), 'DECK_OIL',        'Олія-імпрегнат для терасної дошки',  NULL, 'LITRE', 0.7, 'LITRE', 'банка'),

    -- ---- Epoxy / polyurethane: a two-component kit is bought as A+B, never as one half -----------
    (gen_random_uuid(), 'EPOXY_PRIMER',  'Ґрунт епоксидний (2К)',               NULL, 'KG', NULL, NULL, NULL),
    (gen_random_uuid(), 'EPOXY_COATING', 'Покриття епоксидне (2К)',             NULL, 'KG', 20,  'KG', 'комплект A+B'),
    (gen_random_uuid(), 'PU_TOPCOAT',    'Лак поліуретановий для підлоги (2К)', NULL, 'KG', 7.5, 'KG', 'комплект A+B'),

    -- ---- Skirting: five separate codes, because they are five different goods --------------------
    (gen_random_uuid(), 'SKIRTING_PVC',  'Плінтус пластиковий',    NULL, 'LINEAR_METER', 2.5, 'LINEAR_METER', 'планка'),
    (gen_random_uuid(), 'SKIRTING_MDF',  'Плінтус МДФ шпонований', NULL, 'LINEAR_METER', 2.5, 'LINEAR_METER', 'планка'),
    (gen_random_uuid(), 'SKIRTING_WOOD', 'Плінтус дерев''яний',    NULL, 'LINEAR_METER', 2.5, 'LINEAR_METER', 'планка'),
    (gen_random_uuid(), 'SKIRTING_FOAM', 'Плінтус полістирольний', NULL, 'LINEAR_METER', 2,   'LINEAR_METER', 'планка'),
    (gen_random_uuid(), 'SKIRTING_ALU',  'Плінтус алюмінієвий',    NULL, 'LINEAR_METER', 2.5, 'LINEAR_METER', 'планка'),
    (gen_random_uuid(), 'HIDDEN_SKIRTING_PROFILE', 'Профіль плінтуса прихованого монтажу', NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'HIDDEN_SKIRTING_INSERT',  'Вставка плінтуса прихованого монтажу', NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'SKIRTING_CLIP', 'Кліпса для плінтуса',     NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'CORK_STRIP',    'Компенсатор пробковий',   NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'THRESHOLD_PROFILE', 'Поріг / стиковий профіль', NULL, 'LINEAR_METER', 0.9, 'LINEAR_METER', 'планка'),

    -- ---- Carpentry ------------------------------------------------------------------------------
    (gen_random_uuid(), 'TIMBER_JOIST', 'Брус для лаг',                NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'WOOD_SCREW',   'Саморіз по дереву',           NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'DECK_CLIP',    'Кліпса для терасної дошки',   NULL, 'PIECE', NULL, NULL, NULL);

COMMENT ON COLUMN material_norm.qty_per_unit IS
    'Material per ONE unit of the position, in the MATERIAL''s unit. Read together with `basis`: '
    'QUANTITY multiplies the line''s own quantity, PERIMETER the room outline asked once per '
    'estimate, SECTION the developed width of a reveal, THICKNESS the layer depth in millimetres. '
    'A norm whose figure bakes in a LAYOUT surplus (V147: diagonal laminate at 1,05) is still a '
    'QUANTITY norm — the surplus belongs to the pattern, while the cutting allowance belongs to the '
    'master and stays in `waste_percent` / his WASTE_PERCENT habit.';

-- =================================================================================================
-- 2. Sources behind the figures (the report''s §3.0 in short form).
--
--   Sika SikaBond-54 Parquet (09/2024) + Wakol MS 230: parquet adhesive at a B11 notch is
--     0,8-1,0 and 1,0-1,2 kg/m² -> 1,0.
--   Sika SikaBond-130 Design Floor (03/2025): dispersion adhesive, LVT 250-300 g/m² at an A1/A2
--     notch, CV/PVC ~250, PVC-backed and carpet-on-PVC ~300 -> 0,30 kg.
--   Sika Sikafloor MultiDur ES-14 N (05/2024): epoxy primer 1-2 × 0,3-0,5 kg/m² -> 0,40.
--   Sika Sikafloor-263 SL (06/2017): epoxy binder, self-smoothing 0,9-1,2 kg/m² -> 1,00.
--   Sika Sikafloor-304 W (12/2023): PU topcoat 0,13 kg/m² per coat.
--   Bona Traffic HD (08/2025): 8-10 m²/l per coat -> 0,11 l; the scheme is 1 primer + 2 coats.
--   Bona Mix & Fill Plus (05/2023): gap filler binder 8-12 m²/l -> 0,10 l.
--   Tarkett, укр. instruction for domestic linoleum: +8 cm on every dimension and glued over the
--     whole area «незалежно від площі»; a 4×4 room is 4,08²/16 = 1,04, so 1,00 plus the master''s
--     own allowance is the honest figure, not a baked 1,10.
--   EGGER JUST clic! (03/2018): on a mineral base, a PE film over the whole floor turned up the
--     wall, 5-20 cm overlapped -> a 2 m roll at 0,2 m overlap is 2/1,8 = 1,11, plus the wall ≈ 1,15.
--   Orac Decor «Skirting» + Arbiton: one 310 ml cartridge per 7-8 m of moulding -> 0,04 l/lm, and
--     clips or dowels every 40-50 cm with one 5-10 cm from each corner -> 2,5 pieces/lm.
--   Cezar 60 technical card: a skirting plank is 2,5 m, so corner cuts lose ~5 % -> 1,05.
--   Kreisel 375 floor primer: 0,15-0,2 l/m² on a cement-sand base, ≥ 0,3 undiluted on concrete.
--     This is the band for a FLOOR, which drinks more than the puttied wall the shipped 0,15 was
--     written for — two substrates, not one disagreement.
--   NORMS-SUMMARY §2.7 (n = 20, median 1,8) and §2.8 (n = 17, median 1,9, default 2,0): the
--     self-levelling and cement-screed figures already shipped for TILING, reused verbatim —
--     coefficient AND suggested depth (1,8 at 5 mm; 2,0 at 40 mm).
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 3. FLOORING norms — 58 rows over 40 positions.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order, default_param)
SELECT gen_random_uuid(), 'FLOORING', v.name_key, v.unit, m.id, v.qty, v.basis, v.ord, v.def
FROM (VALUES
    -- ---- Епоксидна підлога: primer -> coating -> topcoat, each its own position ------------------
    ('ґрунтування основи під епоксидну підлогу', 'M2', 'EPOXY_PRIMER',  0.4::numeric,  'QUANTITY', 1, NULL::numeric),
    ('нанесення епоксидного покриття',           'M2', 'EPOXY_COATING', 1.0,  'QUANTITY', 1, NULL),
    ('фінішне лакування підлоги',                'M2', 'PU_TOPCOAT',    0.13, 'QUANTITY', 1, NULL),

    -- ---- Плінтус. The 1,05 is the corner cut on a 2,5 m plank, not a cutting allowance. ----------
    ('монтаж плінтуса пластик',        'LINEAR_METER', 'SKIRTING_PVC',  1.05, 'QUANTITY', 1, NULL),
    ('монтаж плінтуса пластик',        'LINEAR_METER', 'DOWEL_NAIL',    2.5,  'QUANTITY', 2, NULL),
    ('монтаж плінтуса шпонованого мдф', 'LINEAR_METER', 'SKIRTING_MDF', 1.05, 'QUANTITY', 1, NULL),
    ('монтаж плінтуса шпонованого мдф', 'LINEAR_METER', 'SKIRTING_CLIP', 2.5, 'QUANTITY', 2, NULL),
    ('монтаж плінтуса шпонованого мдф', 'LINEAR_METER', 'DOWEL_NAIL',   2.5,  'QUANTITY', 3, NULL),
    ('монтаж плінтуса дерев''яного',   'LINEAR_METER', 'SKIRTING_WOOD', 1.05, 'QUANTITY', 1, NULL),
    ('монтаж плінтуса дерев''яного',   'LINEAR_METER', 'DOWEL_NAIL',    2.5,  'QUANTITY', 2, NULL),
    -- A polystyrene moulding is GLUED, never dowelled — that is the whole reason it is a separate
    -- position and not a finish choice on the plastic one.
    ('монтаж плінтуса з полістиролу',  'LINEAR_METER', 'SKIRTING_FOAM',     1.05, 'QUANTITY', 1, NULL),
    ('монтаж плінтуса з полістиролу',  'LINEAR_METER', 'MOUNTING_ADHESIVE', 0.04, 'QUANTITY', 2, NULL),
    ('монтаж алюмінієвого плінтуса',   'LINEAR_METER', 'SKIRTING_ALU',  1.05, 'QUANTITY', 1, NULL),
    ('монтаж алюмінієвого плінтуса',   'LINEAR_METER', 'DOWEL_NAIL',    2.5,  'QUANTITY', 2, NULL),
    ('монтаж пробкового компенсатора', 'LINEAR_METER', 'CORK_STRIP',    1.05, 'QUANTITY', 1, NULL),
    -- The hidden skirting is a profile plus an insert, and the library ships all three of «профіль»,
    -- «накладка» and the «комплект» row that is their sum. Each is normed for what its own name
    -- says; an estimate carrying the complex AND its two parts double-buys, which is a CATALOG
    -- problem (report §1b) and not something a coefficient can repair.
    ('монтаж профілю плінтуса прихованого монтажу',  'LINEAR_METER', 'HIDDEN_SKIRTING_PROFILE', 1.05, 'QUANTITY', 1, NULL),
    ('монтаж накладки плінтуса прихованого монтажу', 'LINEAR_METER', 'HIDDEN_SKIRTING_INSERT',  1.05, 'QUANTITY', 1, NULL),
    ('монтаж плінтуса прихованого монтажу', 'LINEAR_METER', 'HIDDEN_SKIRTING_PROFILE', 1.05, 'QUANTITY', 1, NULL),
    ('монтаж плінтуса прихованого монтажу', 'LINEAR_METER', 'HIDDEN_SKIRTING_INSERT',  1.05, 'QUANTITY', 2, NULL),
    ('монтаж міжкімнатного порога',    'LINEAR_METER', 'THRESHOLD_PROFILE', 1.0, 'QUANTITY', 1, NULL),
    -- 0,22 л/м² of enamel over two coats (V138) across the ~0,10 m² developed face of an 80 mm
    -- skirting. The PAINT_COVERAGE habit does not reach ENAMEL_WOOD — V145 settled that.
    ('фарбування плінтуса',            'LINEAR_METER', 'ENAMEL_WOOD',   0.022, 'QUANTITY', 1, NULL),
    -- 0,025 l/lm is the shipped silicone figure (TILING «заповнення швів герметиком»): a 5×5 mm
    -- bead is 25 ml per metre, and a threshold joint is the same bead.
    ('приклеювання стиків порожків герметизація', 'LINEAR_METER', 'SEALANT_SILICONE', 0.025, 'QUANTITY', 1, NULL),
    ('нанесення лаку тонера на підлогу', 'M2', 'PARQUET_LACQUER', 0.11, 'QUANTITY', 1, NULL),

    -- ---- Підлога --------------------------------------------------------------------------------
    ('укладка ламінату проста',        'M2', 'LAMINATE',          1.0,  'QUANTITY', 1, NULL),
    ('укладка ламінату проста',        'M2', 'UNDERLAY_LAMINATE', 1.0,  'QUANTITY', 2, NULL),
    -- The 5 % is the DIAGONAL LAYOUT, which needs more plank however carefully it is cut. The
    -- underlay is butt-jointed and does not care which way the planks run, so it stays at 1,00.
    ('укладка ламінату по діагоналі',  'M2', 'LAMINATE',          1.05, 'QUANTITY', 1, NULL),
    ('укладка ламінату по діагоналі',  'M2', 'UNDERLAY_LAMINATE', 1.0,  'QUANTITY', 2, NULL),
    ('укладання кварцвінілу',          'M2', 'SPC_VINYL',         1.0,  'QUANTITY', 1, NULL),
    ('укладка кварцвінілу на підкладку', 'M2', 'SPC_VINYL',    1.0, 'QUANTITY', 1, NULL),
    ('укладка кварцвінілу на підкладку', 'M2', 'UNDERLAY_SPC', 1.0, 'QUANTITY', 2, NULL),
    ('укладка кварцвінілу на клей',    'M2', 'LVT_VINYL',            1.0, 'QUANTITY', 1, NULL),
    ('укладка кварцвінілу на клей',    'M2', 'ADHESIVE_FLOOR_DISP',  0.3, 'QUANTITY', 2, NULL),
    ('настил лінолеуму ковроліну',     'M2', 'FLOOR_ROLL',           1.0, 'QUANTITY', 1, NULL),
    ('настил лінолеуму ковроліну на клей', 'M2', 'FLOOR_ROLL',          1.0, 'QUANTITY', 1, NULL),
    ('настил лінолеуму ковроліну на клей', 'M2', 'ADHESIVE_FLOOR_DISP', 0.3, 'QUANTITY', 2, NULL),
    ('настил підлоги із дошки',        'M2', 'FLOOR_BOARD', 1.05, 'QUANTITY', 1, NULL),
    -- One screw per board × joist crossing: (1 / 0,135 m) × (1 / 0,5 m) = 14,8. The 0,5 m joist
    -- step is an ASSUMPTION — the report says so and the master forks the row if he works at 0,4.
    ('настил підлоги із дошки',        'M2', 'WOOD_SCREW',  15,   'QUANTITY', 2, NULL),
    ('влаштування дерев''яних лаг',    'M2', 'TIMBER_JOIST',  2.1, 'QUANTITY', 1, NULL),
    ('влаштування дерев''яних лаг',    'M2', 'ANCHOR_WEDGE',  3,   'QUANTITY', 2, NULL),
    -- An artistic layout wastes like a herringbone. This is the one covering figure with no source
    -- behind it, and it is shipped because a number he can correct beats a blank he cannot.
    ('художній паркет',                'M2', 'PARQUET_BLOCK',     1.1, 'QUANTITY', 1, NULL),
    ('художній паркет',                'M2', 'PARQUET_ADHESIVE',  1.0, 'QUANTITY', 2, NULL),
    -- Sanding, filling, priming, two coats — the position name says «з лакуванням», so the whole
    -- Bona scheme is on it: 0,10 filler, 0,11 primer, 2 × 0,11 lacquer.
    ('циклювання паркету з лакуванням', 'M2', 'PARQUET_FILLER',  0.1,  'QUANTITY', 1, NULL),
    ('циклювання паркету з лакуванням', 'M2', 'PARQUET_PRIMER',  0.11, 'QUANTITY', 2, NULL),
    ('циклювання паркету з лакуванням', 'M2', 'PARQUET_LACQUER', 0.22, 'QUANTITY', 3, NULL),
    ('грунтовка підлоги підготовчі роботи', 'M2', 'PRIMER_DEEP', 0.2, 'QUANTITY', 1, NULL),
    -- An 8×6 mm expansion gap holds ~48 ml of acrylic per metre. PAINTER's 0,025 is a 5×5 bead in a
    -- crack; the gap a laminate course needs along a wall is twice that section, and V146 §3 settled
    -- that one figure for every geometry is the bug, not the fix.
    ('підрізка ламінату защільнення',  'LINEAR_METER', 'SEALANT_ACRYLIC', 0.05, 'QUANTITY', 1, NULL),
    ('підрізка ламінату (без плінтуса), защільнення', 'LINEAR_METER', 'SEALANT_ACRYLIC', 0.05, 'QUANTITY', 1, NULL),
    ('приклеювання стиків в місцях порожків, герметизація силіконом', 'PIECE', 'SEALANT_SILICONE', 0.025, 'QUANTITY', 1, NULL),

    -- ---- Стяжка ---------------------------------------------------------------------------------
    ('грунтовка під стяжку',           'M2', 'PRIMER_DEEP', 0.2,  'QUANTITY', 1, NULL),
    ('плівкова ізоляція під стяжку',   'M2', 'PE_FILM',     1.15, 'QUANTITY', 1, NULL),
    ('монтаж утеплювача під стяжку',   'M2', 'XPS_BOARD',   1.03, 'QUANTITY', 1, NULL),
    -- «Монтаж утеплювача» with no qualifier is the between-the-joists job (report §1b asks for the
    -- rename), and that is mineral wool at the figure three other positions already carry.
    ('монтаж утеплювача',              'M2', 'MINERAL_WOOL', 1.05, 'QUANTITY', 1, NULL),
    -- The tape runs the room OUTLINE, so the M2 row's area says nothing about how much is bought.
    -- PERIMETER is asked once for the whole estimate, which is exactly right for a damper strip.
    ('монтаж демпферної стрічки',      'M2', 'DAMPER_TAPE', 1.05, 'PERIMETER', 1, NULL),
    -- 2,0 kg per m² per mm at a suggested 40 mm, and 1,8 at 5 mm: both are the figures TILING's
    -- «стяжка маякова цементна» and «влаштування наливної підлоги» already ship, reused verbatim
    -- rather than re-derived. A second suggested depth for the same layer would be a disagreement
    -- and not a refinement — the same call V146 made about the stone-cladding bed. The two price
    -- steps of the screed are one product, so they get one coefficient and one depth.
    ('цементно-піщана стяжка до 20м2', 'M2', 'SCREED_CEMENT',   2.0, 'THICKNESS', 1, 40),
    ('наливна підлога самовирівнююча', 'M2', 'SELF_LEVELLING',  1.8, 'THICKNESS', 1, 5),
    -- 1 mm over 1 m² is 1 l of keramzit, plus ~10 % for compaction. The screed poured OVER it is a
    -- fixed 40 mm topping, so it is a QUANTITY row at 40 × 2,0 — the THICKNESS question on this
    -- position asks about the keramzit bed, which is the layer whose depth actually varies.
    ('стяжка з керамзитом',            'M2', 'EXPANDED_CLAY',  0.0011, 'THICKNESS', 1, 50),
    ('стяжка з керамзитом',            'M2', 'SCREED_CEMENT',  80,     'QUANTITY',  2, NULL),
    -- A 50×50 mm chase is 0,0025 m³ per metre at ~2000 kg/m³. The cross-section is an assumption
    -- the engine cannot yet ask about (the chase SECTION basis is still open).
    ('заливаня штроби в стяжці',       'LINEAR_METER', 'SCREED_CEMENT', 5.0, 'QUANTITY', 1, NULL)
) AS v(name_key, unit, code, qty, basis, ord, def)
JOIN material m ON m.code = v.code;

-- -------------------------------------------------------------------------------------------------
-- 4. The five positions BUILDER ships too — `trade = NULL`, so one norm answers for both.
--
-- V118's seed array lists BUILDER before FLOORING, so for a master who has both these rows land in
-- his catalog under BUILDER and a FLOORING-filed norm would never be reached. Re-filing his
-- `catalog_items` (report §4 item 2) would touch his own copies for no gain; a trade-less norm
-- touches nothing and answers for whichever trade the line ended up under. Same move as V137 and
-- V146 §7.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order, default_param)
SELECT gen_random_uuid(), NULL, v.name_key, v.unit, m.id, v.qty, v.basis, v.ord, v.def
FROM (VALUES
    ('монтаж терасної дошки', 'M2', 'DECK_BOARD', 1.05::numeric, 'QUANTITY', 1, NULL::numeric),
    -- One clip per board × joist crossing: (1 / 0,15 m) × (1 / 0,4 m) = 16,7, plus the ends. The
    -- 0,4 m joist step is an assumption, like the floorboard screw above.
    ('монтаж терасної дошки',    'M2', 'DECK_CLIP',  18,   'QUANTITY', 2, NULL),
    ('фарбування терасної дошки', 'M2', 'DECK_OIL',  0.18, 'QUANTITY', 1, NULL),
    -- A 1×2 m sheet overlapped by one 5 cm mesh square: 2 / (0,95 × 1,95) = 1,08 -> 1,10.
    ('армування підлоги сіткою під стяжку', 'M2', 'MESH_SCREED', 1.1, 'QUANTITY', 1, NULL),
    -- Both figures and both suggested depths are the shipped TILING ones (see section 3).
    ('вирівнювання підлоги самовирівнюючим розчином', 'M2', 'SELF_LEVELLING', 1.8, 'THICKNESS', 1, 5),
    ('цементно-піщана стяжка понад 20м2',             'M2', 'SCREED_CEMENT',  2.0, 'THICKNESS', 1, 40)
) AS v(name_key, unit, code, qty, basis, ord, def)
JOIN material m ON m.code = v.code;

-- -------------------------------------------------------------------------------------------------
-- 5. «Checked, consumes nothing» — V127's recorded verdict, both columns NULL together.
--
-- Abrasive belts, a vacuum cleaner and a wall chaser's disc are tool wear, not a material the
-- master buys per square metre. Without these rows every one of the seven lands in the coverage
-- report and reads as a gap in our data.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'FLOORING', v.name_key, v.unit, NULL, NULL, 'QUANTITY', 0
FROM (VALUES
    ('шліфування бетонної основи',                 'M2'),
    ('шліфування бетону стяжки',                   'M2'),
    ('шліфування дерев''яної підлоги',             'M2'),
    ('брашування паркету',                         'M2'),
    ('підготовка поверхні (очищення і т.п.)',      'M2'),
    ('чистка підлоги порохотягом підготовка',      'M2'),
    ('штроблення в стяжці під монтаж перегородок', 'LINEAR_METER')
) AS v(name_key, unit);

-- -------------------------------------------------------------------------------------------------
-- 6. Self-checks. Every one is about THIS migration's own work (review B-50): the name keys were
--    typed here against a catalog snapshot, so a key that resolves to nothing is a typo of mine and
--    not a rename an admin made between two deploys.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    orphans    text;
    homeless   text;
    ambiguous  text;
    no_default int;
    normed     int;
    unnormed   text;
BEGIN
    -- 6a. Every FLOORING key names a live FLOORING WORK position.
    SELECT string_agg(DISTINCT n.name_key || ' [' || n.unit || ']', ', ')
      INTO orphans
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.trade = 'FLOORING'
       AND NOT EXISTS (
            SELECT 1 FROM catalog_templates t
             WHERE t.trade = 'FLOORING'
               AND t.type = 'WORK'
               AND t.unit = n.unit
               AND lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) = n.name_key
       );
    IF orphans IS NOT NULL THEN
        RAISE EXCEPTION 'V147: FLOORING norms name no live catalog position: %', orphans;
    END IF;

    -- 6b. A trade-less norm answers everywhere, so it must still name a position SOMEWHERE. The six
    --     rows of section 4 are the move that could strand one.
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
        RAISE EXCEPTION 'V147: trade-less norms name no catalog position at all: %', homeless;
    END IF;

    -- 6c. A THICKNESS norm with no suggestion asks a question over an empty field.
    SELECT count(*) INTO no_default
      FROM material_norm
     WHERE owner_id IS NULL AND basis = 'THICKNESS' AND default_param IS NULL;
    IF no_default > 0 THEN
        RAISE EXCEPTION 'V147: % THICKNESS norms carry no default_param', no_default;
    END IF;

    -- 6d. Two trades norming one (name, unit) cannot be resolved — the answer would be a coin flip.
    --     Section 4 files at NULL rather than duplicating precisely to keep this empty. A trade-less
    --     row beside a traded one is the same ambiguity with a NULL in it, which `count(DISTINCT
    --     trade)` would not see, so it is asked for separately.
    SELECT string_agg(name_key || ' [' || unit || ']', ', ' ORDER BY name_key)
      INTO ambiguous
      FROM (SELECT name_key, unit FROM material_norm
             WHERE owner_id IS NULL
             GROUP BY name_key, unit
            HAVING count(DISTINCT trade) > 1
                OR (count(DISTINCT trade) = 1 AND count(trade) <> count(*))) AS a;
    IF ambiguous IS NOT NULL THEN
        RAISE EXCEPTION 'V147: a name and unit two trades both norm cannot be resolved: %', ambiguous;
    END IF;

    -- 6e. Row counts, so a half-applied VALUES list cannot pass quietly: 40 positions with a
    --     material + 7 recorded verdicts = 47 under FLOORING, plus the five shared ones at NULL.
    SELECT count(DISTINCT name_key || '|' || unit) INTO normed
      FROM material_norm WHERE owner_id IS NULL AND trade = 'FLOORING';
    IF normed <> 47 THEN
        RAISE EXCEPTION 'V147: expected 47 normed FLOORING positions, found %', normed;
    END IF;

    -- 6f. And the two we deliberately left alone are the ONLY two left — a third would mean a
    --     position fell out of the set by accident rather than by decision. Asked as a set
    --     difference rather than a sorted string, because the answer must not depend on how the
    --     database happens to collate Ukrainian.
    SELECT string_agg(x.label, ', ' ORDER BY x.label) INTO unnormed
      FROM (
        SELECT t.name || ' [' || t.unit || ']' AS label
          FROM catalog_templates t
         WHERE t.trade = 'FLOORING'
           AND t.type = 'WORK'
           AND NOT EXISTS (
                SELECT 1 FROM material_norm n
                 WHERE n.owner_id IS NULL
                   AND (n.trade = 'FLOORING' OR n.trade IS NULL)
                   AND n.unit = t.unit
                   AND n.name_key = lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')')))
           )
        EXCEPT SELECT unnest(ARRAY[
            'Машинна стяжка самовирівнююча [M2]',
            'Монтаж та виготовлення ніші під плінтус прихованого монтажу [LINEAR_METER]'])
      ) AS x;
    IF unnormed IS NOT NULL THEN
        RAISE EXCEPTION 'V147: FLOORING positions left unnormed by accident: %', unnormed;
    END IF;
END $$;
