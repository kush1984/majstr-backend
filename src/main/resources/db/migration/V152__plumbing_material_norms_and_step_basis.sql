-- =================================================================================================
-- V152 — the trade-by-trade review, batch 5: PLUMBING's first material norms, and the STEP basis
--
-- Source: `C:\Work\prompts\trade-review\07-PLUMBING.md` §3, re-read against the V151 library.
-- PLUMBING ships 176 WORK positions and had no norm of its own (the inspection hatch it shares with
-- DRYWALL was the only answer). Plumbing is fixtures and fittings, not square metres, so three kinds
-- of norm are honest here: running material where a metre of work IS a metre of pipe; «one part per
-- position» where the part is a fitting and not the client's appliance; and underfloor heating,
-- where the pipe follows the geometry of the loop.
--
-- THE STEP BASIS (section 1). Underfloor-heating pipe per m² falls as the loop spacing grows —
-- Purmo: 9,5 / 6,5 / 5,0 m/m² at 100 / 150 / 200 mm — and every existing basis multiplies. STEP is
-- asked per POSITION in millimetres, exactly like THICKNESS, but DIVIDES: amount = quantity ×
-- qty_per_unit ÷ mm, so the coefficient is the figure at a 1 mm spacing (6,5 × 150 = 975). One
-- position with one question, instead of three positions «…крок 100 / 150 / 200» that would also
-- leave every other spacing unanswered. A spacing typed by the master is stored like any other
-- answer (V142), so the CHECK there grows too.
--
-- OWNER RULINGS (2026-10-13), all as the review recommended:
--   1. Clips and sewer clamps ship with the usual flat's diameter assumed (PP-R Ø20: 1,7 per metre
--      cold, 1,8 at 60 °C; sewer Ø50: 2,0) — the arithmetic is on screen and a fork corrects it.
--   2. The underfloor position is normed through STEP with a 150 mm suggestion.
--   3. Mounting kits the client also chooses — the WC frame, the flush plate, a thermostatic head,
--      radiator legs — are normed at one per position, like the inspection hatch. The appliances
--      themselves (WC, bath, mixers, boilers, radiators, filters, pumps, septic tank) are not.
--
-- DELIBERATELY UNNORMED: fittings (their count is the layout's, and no manufacturer gives
-- «fittings per metre»), manifolds and boiler / radiator connection sets (outputs and connection
-- side are not in the name), chase filling (the chase section is not in the name), the riser pipe
-- (its height is not in the name and there is no LENGTH basis), outdoor sewer pipe, thread sealant
-- and lubricant (consumables / supplied with the pipe), radiator brackets (bundled with a panel
-- radiator), hoses, corrugated connectors and siphons of an appliance (usually in its box), and the
-- demolition positions shared with DEMOLITION (its own batch).
--
-- Every norm carries `waste_percent = 0` (batch 2's ruling). No `catalog_templates` row is inserted.
-- None of the normed positions is shipped by another trade, so every row is filed under PLUMBING.
--
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 1. The STEP basis.
-- -------------------------------------------------------------------------------------------------
ALTER TABLE material_norm DROP CONSTRAINT material_norm_basis_check;
ALTER TABLE material_norm ADD CONSTRAINT material_norm_basis_check
    CHECK (basis IN ('QUANTITY', 'PERIMETER', 'SECTION', 'THICKNESS', 'STEP'));

ALTER TABLE estimate_material_param DROP CONSTRAINT estimate_material_param_basis;
ALTER TABLE estimate_material_param ADD CONSTRAINT estimate_material_param_basis
    CHECK (basis IN ('PERIMETER', 'SECTION', 'THICKNESS', 'STEP'));

COMMENT ON COLUMN material_norm.qty_per_unit IS
    'Material per ONE unit of the position, in the MATERIAL''s unit. Read together with `basis`: '
    'QUANTITY multiplies the line''s own quantity, PERIMETER the room outline asked once per '
    'estimate, SECTION the developed width of a reveal, THICKNESS the layer depth in millimetres, '
    'and STEP (V152) DIVIDES by the loop spacing in millimetres, so a STEP coefficient is the figure '
    'at a 1 mm spacing. A norm whose figure bakes in a LAYOUT surplus (V147: diagonal laminate at '
    '1,05) is still a QUANTITY norm — the surplus belongs to the pattern, while the cutting allowance '
    'belongs to the master and stays in `waste_percent` / his WASTE_PERCENT habit.';

-- -------------------------------------------------------------------------------------------------
-- 2. Dictionary rows — 20 of them, brand-free, packaged only where the shelf agrees on one.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    -- ---- Water and heating pipe. One code for PP-R / PEX / multilayer: which one is the master's
    --      choice, and a fork renames nothing — the metres are the answer. -----------------------
    (gen_random_uuid(), 'PIPE_WATER',         'Труба для водопостачання та опалення', NULL, 'LINEAR_METER', NULL, NULL, NULL),
    -- Thermaflex ThermaSmart PRO: a pipe sleeve is 2 m long.
    (gen_random_uuid(), 'PIPE_INSULATION',    'Ізоляція трубна',                      NULL, 'LINEAR_METER', 2, 'LINEAR_METER', 'шт'),
    (gen_random_uuid(), 'PIPE_CLIP',          'Кліпса (хомут) для труби',             NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'WALL_PLATE_ELBOW',   'Водорозетка (настінний кутник)',       NULL, 'PIECE', NULL, NULL, NULL),

    -- ---- Sewer ----------------------------------------------------------------------------------
    (gen_random_uuid(), 'PIPE_SEWER',         'Труба каналізаційна',                  NULL,   'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'SEWER_CLAMP',        'Хомут каналізаційний з гумовою вставкою', NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'SEWER_CLAMP_110',    'Хомут каналізаційний',                 'Ø110', 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'SEWER_TEE_110',      'Трійник каналізаційний',               'Ø110', 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'SEWER_COMPENSATOR',  'Компенсатор каналізаційний',           NULL,   'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'SEWER_CAST_ADAPTER', 'Манжета перехідна чавун — пластик',    'Ø110', 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'SEWER_REVISION',     'Ревізія каналізаційна',                NULL,   'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'SEWER_CHECK_VALVE_50',  'Клапан зворотний каналізаційний',   'Ø50',  'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'SEWER_CHECK_VALVE_110', 'Клапан зворотний каналізаційний',   'Ø110', 'PIECE', NULL, NULL, NULL),

    -- ---- Mounting kits (ruling 3) --------------------------------------------------------------
    (gen_random_uuid(), 'INSTALL_FRAME_WC',   'Інсталяція для підвісного унітазу (комплект)', NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'FLUSH_PLATE',        'Панель змиву',                         NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'THERMO_HEAD',        'Термоголовка радіаторна',              NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'RADIATOR_LEGS',      'Ніжки для радіатора (комплект)',       NULL, 'PIECE', NULL, NULL, NULL),

    -- ---- Underfloor heating --------------------------------------------------------------------
    -- A coil is 120 to 600 m by product, so no package: the answer is in metres.
    (gen_random_uuid(), 'UFH_PIPE',           'Труба для теплої підлоги',             NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'UFH_CLIP',           'Скоба для труби теплої підлоги',       NULL, 'PIECE', NULL, NULL, NULL),
    -- Uponor Tacker: a roll is 1 × 10 m.
    (gen_random_uuid(), 'UFH_INSULATION',     'Утеплювач для теплої підлоги (системний)', NULL, 'M2', 10, 'M2', 'рулон');

-- =================================================================================================
-- 3. Sources behind the figures (the report's §3.1 in short form).
--
--   ДСТУ-Н Б В.2.5-35:2007, дод. Д, табл. 2.1 — PP-R support spacing: Ø20 600 mm at 20-50 °C and
--     550 at 60 °C → 1000/600 = 1,7 and 1000/550 = 1,8 clips per metre.
--   Ostendorf HT Safe (07/2024) п. 5.3 — sewer clamp spacing: Ø50 0,50 m horizontal → 2,0 per metre;
--     Ø110 2,0 m vertical → 2 per storey of 2,7-3,0 m.
--   Purmo underfloor catalogue (2012) — pipe 9,5 / 6,5 / 5,0 m/m² and clips 20 / 14 / 10 per m² at
--     100 / 150 / 200 mm. Written as 6,5 × 150 = 975 and 14 × 150 = 2100 at a 1 mm spacing: that is
--     Purmo's own figure at the suggested 150 and within 5 % of it at 100 and 200.
--   Uponor Tacker — system insulation roll 1 × 10 m, laid edge strip first.
--   Geberit Duofix — the frame ships with anchors, a Ø90 bend, a 90/110 adapter, an angle valve and
--     sound insulation, so none of them is normed separately.
--   Ceresit CS 25 — silicone 36 ml per metre of a 6 × 6 mm joint.
--   Calculation — riser sound insulation: π × 0,110 m = 0,35 m circumference + 10 % overlap ≈ 0,4 m².
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 4. PLUMBING norms.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order, default_param)
SELECT gen_random_uuid(), 'PLUMBING', v.name_key, v.unit, m.id, v.qty, v.basis, v.ord, v.def
FROM (VALUES
    -- ---- Water and heating pipe: a metre of work is a metre of pipe -----------------------------
    ('прокладання труб з ізоляцією',            'LINEAR_METER', 'PIPE_WATER',      1.0::numeric, 'QUANTITY', 1, NULL::numeric),
    ('прокладання труб з ізоляцією',            'LINEAR_METER', 'PIPE_INSULATION', 1.0,  'QUANTITY', 2, NULL),
    ('прокладання труб з ізоляцією (опалення)', 'LINEAR_METER', 'PIPE_WATER',      1.0,  'QUANTITY', 1, NULL),
    ('прокладання труб з ізоляцією (опалення)', 'LINEAR_METER', 'PIPE_INSULATION', 1.0,  'QUANTITY', 2, NULL),
    -- Ruling 1: PP-R Ø20, the usual flat's pipe. A clip at every bend is the layout's, not ours.
    ('прокладання труб на хомутах',             'LINEAR_METER', 'PIPE_WATER',      1.0,  'QUANTITY', 1, NULL),
    ('прокладання труб на хомутах',             'LINEAR_METER', 'PIPE_CLIP',       1.7,  'QUANTITY', 2, NULL),
    ('прокладання труб на хомутах (опалення)',  'LINEAR_METER', 'PIPE_WATER',      1.0,  'QUANTITY', 1, NULL),
    ('прокладання труб на хомутах (опалення)',  'LINEAR_METER', 'PIPE_CLIP',       1.8,  'QUANTITY', 2, NULL),
    ('прокладка труб в траншеї',                'LINEAR_METER', 'PIPE_WATER',      1.0,  'QUANTITY', 1, NULL),
    -- «з кінцевим елементом» is one wall plate per point, under both spellings the library ships.
    ('виводи по точках з кінцевим елементом',                    'POINT', 'WALL_PLATE_ELBOW', 1, 'QUANTITY', 1, NULL),
    ('виводи по точках (з встановленням кінцевого елементу)',     'POINT', 'WALL_PLATE_ELBOW', 1, 'QUANTITY', 1, NULL),

    -- ---- Sewer ----------------------------------------------------------------------------------
    ('прокладка труб каналізації',                'LINEAR_METER', 'PIPE_SEWER',  1.0, 'QUANTITY', 1, NULL),
    ('прокладання труб на хомутах (каналізація)', 'LINEAR_METER', 'PIPE_SEWER',  1.0, 'QUANTITY', 1, NULL),
    ('прокладання труб на хомутах (каналізація)', 'LINEAR_METER', 'SEWER_CLAMP', 2.0, 'QUANTITY', 2, NULL),
    -- A riser within one flat: one tee, two clamps a storey. The pipe is not normed — the riser's
    -- height is not in the name. The compensator has a position of its own in «Заміна стояків».
    ('монтаж (заміна) стояка каналізації (пластиковий на пластиковий) з проходом через одну панель', 'PIECE', 'SEWER_TEE_110',   1, 'QUANTITY', 1, NULL),
    ('монтаж (заміна) стояка каналізації (пластиковий на пластиковий) з проходом через одну панель', 'PIECE', 'SEWER_CLAMP_110', 2, 'QUANTITY', 2, NULL),
    ('монтаж (заміна) стояка каналізації (пластиковий на пластиковий) з проходом через дві панелі',  'PIECE', 'SEWER_TEE_110',   1, 'QUANTITY', 1, NULL),
    ('монтаж (заміна) стояка каналізації (пластиковий на пластиковий) з проходом через дві панелі',  'PIECE', 'SEWER_CLAMP_110', 2, 'QUANTITY', 2, NULL),
    ('монтаж заміна стояка пластик на пластик одна панель', 'PIECE', 'SEWER_TEE_110',   1, 'QUANTITY', 1, NULL),
    ('монтаж заміна стояка пластик на пластик одна панель', 'PIECE', 'SEWER_CLAMP_110', 2, 'QUANTITY', 2, NULL),
    ('монтаж заміна стояка пластик на пластик дві панелі',  'PIECE', 'SEWER_TEE_110',   1, 'QUANTITY', 1, NULL),
    ('монтаж заміна стояка пластик на пластик дві панелі',  'PIECE', 'SEWER_CLAMP_110', 2, 'QUANTITY', 2, NULL),
    -- Cast iron to plastic replaces the run between two cast-iron ends: one adapter at each.
    ('монтаж (заміна) стояка каналізації (чавунний на пластиковий) з проходом через одну панель', 'PIECE', 'SEWER_TEE_110',      1, 'QUANTITY', 1, NULL),
    ('монтаж (заміна) стояка каналізації (чавунний на пластиковий) з проходом через одну панель', 'PIECE', 'SEWER_CLAMP_110',    2, 'QUANTITY', 2, NULL),
    ('монтаж (заміна) стояка каналізації (чавунний на пластиковий) з проходом через одну панель', 'PIECE', 'SEWER_CAST_ADAPTER', 2, 'QUANTITY', 3, NULL),
    ('монтаж (заміна) стояка каналізації (чавунний на пластиковий) з проходом через дві панелі',  'PIECE', 'SEWER_TEE_110',      1, 'QUANTITY', 1, NULL),
    ('монтаж (заміна) стояка каналізації (чавунний на пластиковий) з проходом через дві панелі',  'PIECE', 'SEWER_CLAMP_110',    2, 'QUANTITY', 2, NULL),
    ('монтаж (заміна) стояка каналізації (чавунний на пластиковий) з проходом через дві панелі',  'PIECE', 'SEWER_CAST_ADAPTER', 2, 'QUANTITY', 3, NULL),
    ('монтаж заміна стояка чавун на пластик одна панель', 'PIECE', 'SEWER_TEE_110',      1, 'QUANTITY', 1, NULL),
    ('монтаж заміна стояка чавун на пластик одна панель', 'PIECE', 'SEWER_CLAMP_110',    2, 'QUANTITY', 2, NULL),
    ('монтаж заміна стояка чавун на пластик одна панель', 'PIECE', 'SEWER_CAST_ADAPTER', 2, 'QUANTITY', 3, NULL),
    ('монтаж заміна стояка чавун на пластик дві панелі',  'PIECE', 'SEWER_TEE_110',      1, 'QUANTITY', 1, NULL),
    ('монтаж заміна стояка чавун на пластик дві панелі',  'PIECE', 'SEWER_CLAMP_110',    2, 'QUANTITY', 2, NULL),
    ('монтаж заміна стояка чавун на пластик дві панелі',  'PIECE', 'SEWER_CAST_ADAPTER', 2, 'QUANTITY', 3, NULL),
    ('заміна трійника в стояку каналізації',      'PIECE', 'SEWER_TEE_110',         1, 'QUANTITY', 1, NULL),
    ('установка компенсатора каналізаційного',    'PIECE', 'SEWER_COMPENSATOR',     1, 'QUANTITY', 1, NULL),
    ('монтаж каналізаційної ревізії (прочистки)', 'PIECE', 'SEWER_REVISION',        1, 'QUANTITY', 1, NULL),
    ('монтаж зворотного клапана каналізації ф50',  'PIECE', 'SEWER_CHECK_VALVE_50',  1, 'QUANTITY', 1, NULL),
    ('монтаж зворотного клапана каналізації ф110', 'PIECE', 'SEWER_CHECK_VALVE_110', 1, 'QUANTITY', 1, NULL),
    ('шумоізоляція стояка каналізації', 'LINEAR_METER', 'ACOUSTIC_MEMBRANE', 0.4, 'QUANTITY', 1, NULL),

    -- ---- Underfloor heating (ruling 2). The damper strip runs the room's outline; a FLOORING
    --      damper norm in the same estimate shares the one perimeter answer, never adds to it. ----
    ('монтаж трубної системи тепла підлога з утеплювачем', 'M2', 'UFH_PIPE',       975,  'STEP',      1, 150),
    ('монтаж трубної системи тепла підлога з утеплювачем', 'M2', 'UFH_CLIP',       2100, 'STEP',      2, 150),
    ('монтаж трубної системи тепла підлога з утеплювачем', 'M2', 'UFH_INSULATION', 1.0,  'QUANTITY',  3, NULL),
    ('монтаж трубної системи тепла підлога з утеплювачем', 'M2', 'DAMPER_TAPE',    1.05, 'PERIMETER', 4, NULL),

    -- ---- Mounting kits (ruling 3) and the bath joint -------------------------------------------
    ('монтаж інсталяції для унітазу',                      'PIECE', 'INSTALL_FRAME_WC', 1, 'QUANTITY', 1, NULL),
    ('монтаж панелі з клавішами змиву',                    'PIECE', 'FLUSH_PLATE',      1, 'QUANTITY', 1, NULL),
    ('установка терморегулятора на радіатор',              'PIECE', 'THERMO_HEAD',      1, 'QUANTITY', 1, NULL),
    ('установка ніжок під радіатор із нижнім підключенням', 'PIECE', 'RADIATOR_LEGS',    1, 'QUANTITY', 1, NULL),
    ('герметизація ванни силіконом', 'LINEAR_METER', 'SEALANT_SILICONE', 0.036, 'QUANTITY', 1, NULL)
) AS v(name_key, unit, code, qty, basis, ord, def)
JOIN material m ON m.code = v.code;

-- -------------------------------------------------------------------------------------------------
-- 5. «Checked, consumes nothing» — tests, start-up, sealing a meter, chasing (a disc and a drill
--    bit are tool wear), niches, earthworks, and handling a radiator that stays.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'PLUMBING', v.name_key, v.unit, NULL, NULL, 'QUANTITY', 0
FROM (VALUES
    ('перевірка системи водопроводу тиском',                      'PIECE'),
    ('перевірка системи опалення тиском',                         'PIECE'),
    ('перевірка каналізації проливом',                            'PIECE'),
    ('запуск системи водопроводу',                                'PIECE'),
    ('запуск системи опалення',                                   'PIECE'),
    ('опломбування лічильника води',                              'PIECE'),
    ('пусконалагодження септика',                                 'PIECE'),
    ('перший нагрів теплої підлоги після стяжки',                 'PIECE'),
    ('гідравлічне налаштування (балансування) контурів теплої підлоги', 'SET'),
    ('штроблення в бетоні (вода)',                                'LINEAR_METER'),
    ('штроблення в бетоні (каналізація)',                         'LINEAR_METER'),
    ('штроблення в газобетоні цеглі (каналізація)',               'LINEAR_METER'),
    ('штроблення під трубу 100мм в бетоні',                       'LINEAR_METER'),
    ('штроблення під трубу 100мм в газобетоні цеглі',             'LINEAR_METER'),
    ('штроблення в бетоні (опалення)',                            'LINEAR_METER'),
    ('штроблення в цеглі газобетоні (опалення)',                  'LINEAR_METER'),
    ('штроблення в бетоні',                                       'LINEAR_METER'),
    ('штроблення в цеглі, газобетоні',                            'LINEAR_METER'),
    ('виготовлення ніші під радіатор опалення в цегляній стіні',  'PIECE'),
    ('виготовлення ніші під шафу колектора в бетоні',             'PIECE'),
    ('виготовлення ніші під шафу колектора в цеглі',              'PIECE'),
    ('виготовлення ніші під шафу колектора/гребінки (в бетоні)',  'PIECE'),
    ('виготовлення ніші під шафу колектора/гребінки (в цеглі)',   'PIECE'),
    ('земляні роботи під септик',                                 'M3'),
    ('нарізання різьби на стояку опалення',                       'PIECE'),
    ('зняття і встановлення радіатора (під час ремонту - один раз)', 'PIECE')
) AS v(name_key, unit);

-- -------------------------------------------------------------------------------------------------
-- 6. Self-checks, each about THIS migration's own work (review B-50).
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    orphans      text;
    ambiguous    text;
    out_of_range int;
    normed       int;
BEGIN
    -- 6a. Every PLUMBING key names a live PLUMBING WORK position (a typo here is mine).
    SELECT string_agg(DISTINCT n.name_key || ' [' || n.unit || ']', ', ')
      INTO orphans
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.trade = 'PLUMBING'
       AND NOT EXISTS (
            SELECT 1 FROM catalog_templates t
             WHERE t.trade = 'PLUMBING' AND t.type = 'WORK' AND t.unit = n.unit
               AND lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) = n.name_key
       );
    IF orphans IS NOT NULL THEN
        RAISE EXCEPTION 'V152: PLUMBING norms name no live catalog position: %', orphans;
    END IF;

    -- 6b. Two trades norming one (name, unit) cannot be resolved.
    SELECT string_agg(name_key || ' [' || unit || ']', ', ' ORDER BY name_key)
      INTO ambiguous
      FROM (SELECT name_key, unit FROM material_norm
             WHERE owner_id IS NULL
             GROUP BY name_key, unit
            HAVING count(DISTINCT trade) > 1
                OR (count(DISTINCT trade) = 1 AND count(trade) <> count(*))) AS a;
    IF ambiguous IS NOT NULL THEN
        RAISE EXCEPTION 'V152: a name and unit two trades both norm cannot be resolved: %', ambiguous;
    END IF;

    -- 6c. A STEP norm asks a question, so it must suggest an answer inside the calculator's bound
    --     (500 mm) — otherwise the pre-filled figure is refused and the position asks forever.
    SELECT count(*) INTO out_of_range
      FROM material_norm
     WHERE owner_id IS NULL AND basis = 'STEP'
       AND (default_param IS NULL OR default_param <= 0 OR default_param > 500);
    IF out_of_range > 0 THEN
        RAISE EXCEPTION 'V152: % STEP norm(s) suggest no spacing inside the bound', out_of_range;
    END IF;

    -- 6d. Row count: 29 positions with a material + 26 recorded verdicts, so a half-applied list
    --     cannot pass. With the trade-less rows (hatch, protection, debris, cleanup) 60 of 176 answer.
    SELECT count(DISTINCT name_key || '|' || unit) INTO normed
      FROM material_norm WHERE owner_id IS NULL AND trade = 'PLUMBING';
    IF normed <> 55 THEN
        RAISE EXCEPTION 'V152: expected 55 normed PLUMBING positions, found %', normed;
    END IF;
END $$;
