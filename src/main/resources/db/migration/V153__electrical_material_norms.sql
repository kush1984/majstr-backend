-- =================================================================================================
-- V153 — the trade-by-trade review, batch 6: ELECTRICAL's first material norms
--
-- Source: `C:\Work\prompts\trade-review\06-ELECTRICAL.md` §3, re-read against the V152 library.
-- ELECTRICAL had no norm and the dictionary held no electrical material at all. What is honest here
-- is per UNIT OF WORK: a metre of laying is a metre of cable and of conduit, a box is a box, a
-- breaker is a breaker. Cable PER POINT stays impossible — no Ukrainian norm quantifies the
-- allowance for bends and drops — so nothing here derives a length from a point count.
--
-- THE CABLE AND THE PARKED MEASUREMENTS. The electrical measurement calculator (V60/V61 —
-- points, chases and a CABLE run off a plan) is built but UI-disabled behind
-- `ELECTRICAL_MEASUREMENTS_ENABLED = false`, so today these norms are the only source of a cable
-- figure. Re-enabling it must reconcile the two (open-questions), or the cable is offered twice.
--
-- OWNER RULINGS (2026-10-13), all as the review recommended:
--   1. Cable is normed at one metre per metre of laying, as a generic «Кабель мідний» or «Кабель
--      силовий»: the cross-section is not in any position name, so the master settles it at the
--      merchant — the metres are the answer that matters.
--   2. Chase filling ships at 0,4 kg/m, the figure for the usual 20×20 chase holding a Ø16 conduit,
--      with the arithmetic on screen and a fork to correct it.
--   3. Modular devices are normed at one per position — masters buy breakers themselves, unlike
--      sockets and lights, which the client chooses.
--
-- DELIBERATELY UNNORMED: socket and switch mechanisms, lights, strips and tracks (the client's
-- choice); equipment picked with the client (stabiliser, ATS, UPS, inverter, generator, EV charger,
-- boiler, meters, intercoms, cameras, routers); heating mats and film (sold as fixed-area kits);
-- mat adhesive (TILING's adhesive already covers the bed — a double count); wire connectors (the
-- circuit decides); clips and saddles for surface runs (the positions do not say surface or chase,
-- and a clip in a chase is a wrong answer); comb busbars and DIN rail; gypsum for a panel niche;
-- retro wire and soldering (no source); «імпульсного реле, таймера або фотореле» (three devices in
-- one name); and the wiring demolition shared with DEMOLITION (its own batch).
--
-- Every norm carries `waste_percent = 0` (batch 2's ruling). No `catalog_templates` row is inserted.
-- None of the normed positions is shipped by another trade, so every row is filed under ELECTRICAL.
--
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 1. Dictionary rows — 39 of them, brand-free. A package only where the shelf agrees on one.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    -- Ferozit Г-5: 2 and 5 kg bags; 5 is the one an electrician buys.
    (gen_random_uuid(), 'GYPSUM_BUILDING',      'Гіпс будівельний (алебастр)',        NULL, 'KG', 5, 'KG', 'пакет'),
    (gen_random_uuid(), 'BOX_FLUSH',            'Підрозетник',                        NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'BOX_JUNCTION_FLUSH',   'Коробка розподільча врізна',         NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'BOX_JUNCTION_SURFACE', 'Коробка розподільча накладна',       NULL, 'PIECE', NULL, NULL, NULL),

    -- ---- Cable and its ducts. Coils are 50 or 100 m by product, so the answer is in metres. -----
    (gen_random_uuid(), 'CABLE_COPPER',         'Кабель мідний (розетки, освітлення)', NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'CABLE_POWER',          'Кабель силовий',                     NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'CABLE_UTP',            'Кабель вита пара',                   NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'CABLE_COAX',           'Кабель коаксіальний',                NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'CONDUIT_16',           'Труба гофрована',                    'Ø16', 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'CONDUIT_20',           'Труба гофрована',                    'Ø20', 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'METAL_HOSE',           'Металорукав',                        NULL, 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'PIPE_HDPE_CABLE',      'Труба ПНД для кабелю',               NULL, 'LINEAR_METER', NULL, NULL, NULL),
    -- Legrand mini trunking and its peers: a 2 m length.
    (gen_random_uuid(), 'TRUNKING',             'Кабель-канал',                       NULL, 'LINEAR_METER', 2, 'LINEAR_METER', 'хлист'),
    (gen_random_uuid(), 'CABLE_LUG',            'Наконечник кабельний',               NULL, 'PIECE', NULL, NULL, NULL),

    -- ---- Panel enclosures: one code per size, so two different boxes never merge into «×2». -----
    (gen_random_uuid(), 'PANEL_BOX_36',         'Корпус електрощита накладний',       '12-36 модулів',   'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'PANEL_BOX_60',         'Корпус електрощита накладний',       '48-60 модулів',   'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'PANEL_BOX_90',         'Корпус електрощита накладний',       '72-90 модулів',   'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'PANEL_BOX_180',        'Корпус електрощита накладний',       '120-180 модулів', 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'PANEL_BOX_FLUSH',      'Корпус електрощита врізний',         NULL,              'PIECE', NULL, NULL, NULL),

    -- ---- Modular devices (ruling 3). Rating and curve are the master's; the count is the line's. -
    (gen_random_uuid(), 'MCB_1P',               'Автоматичний вимикач',               '1P', 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'MCB_2P',               'Автоматичний вимикач',               '2P', 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'MCB_3P',               'Автоматичний вимикач',               '3P', 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'MCB_4P',               'Автоматичний вимикач',               '4P', 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'RCBO',                 'Диференційний автомат',              NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'RCD_2P',               'Пристрій захисного відключення (ПЗВ)', '2P', 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'RCD_4P',               'Пристрій захисного відключення (ПЗВ)', '4P', 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'SPD',                  'Обмежувач перенапруги (УЗІП)',       NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'AFDD',                 'Пристрій захисту від дугового пробою (ПВДП)', NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'VOLTAGE_RELAY',        'Реле напруги однофазне',             NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'VOLTAGE_RELAY_3PH',    'Реле напруги трифазне',              NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'CONTACTOR_220',        'Контактор модульний',                '220 В', 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'CONTACTOR_380',        'Контактор модульний',                '380 В', 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'CHANGEOVER_2P',        'Автомат перекидний',                 '2P', 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'CHANGEOVER_4P',        'Автомат перекидний',                 '4P', 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'CHANGEOVER_RELAY_4P',  'Реле перекидне',                     '4P', 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'REVERSING_SWITCH',     'Рубильник реверсивний',              NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'NEUTRAL_BAR',          'Шина нульова',                       NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'DISTRIBUTION_BLOCK',   'Розподільчий блок на DIN-рейку',     NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'CROSS_MODULE',         'Крос-модуль',                        NULL, 'PIECE', NULL, NULL, NULL);

-- =================================================================================================
-- 2. Sources behind the figures (the report's §3.1 in short form).
--
--   Ferozit «Гіпс будівельний» Г-5 (2024): for filling holes and chases; 1 kg to 0,5-0,6 l water,
--     which sets to ≈1,07 kg per litre of cavity — taken as 1,0 kg/l, inside the 0,85-1,25 band of
--     the gypsum plasters in our data (Rotband, HP Start, Polimin SP-G, Ferozit 250).
--   Kaiser A1555-51: a flush box is Ø68 in a Ø68 bore. Box: bore Ø70×55 ≈ 0,21 l − box ≈ 0,16 l +
--     gap and base ≈ 0,05 l, ×2-3 for a ragged edge and the cap → 0,10-0,15 l → 0,15 kg.
--   Junction box: niche 110×110×55 ≈ 0,67 l − box 100×100×50 = 0,50 l → 0,17 l × 1,5 → 0,25-0,3 kg.
--   Chase 20×20 (ruling 2): 400 mm² − a Ø16 conduit 201 mm² (or a flat 3×2,5 cable ~73 mm²) =
--     199-327 mm², ×1,3 for an uneven chase → 0,26-0,43 l/m → 0,4 kg/m.
--   Marshall-Tufflex catalogue (2016) p. 305: fix trunking at least every 375 mm → 3 per metre.
--   A metre of laying is a metre of cable and of conduit (ruling 1); offcuts are the master's
--   WASTE_PERCENT. A surface panel takes four fixings, a surface junction box two.
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 3. ELECTRICAL norms.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'ELECTRICAL', v.name_key, v.unit, m.id, v.qty, 'QUANTITY', v.ord
FROM (VALUES
    -- ---- Boxes -----------------------------------------------------------------------------------
    ('монтаж підрозетника',                  'PIECE', 'BOX_FLUSH',            1::numeric, 1),
    ('монтаж підрозетника',                  'PIECE', 'GYPSUM_BUILDING',      0.15, 2),
    ('монтаж розподільчої коробки в стіну',  'PIECE', 'BOX_JUNCTION_FLUSH',   1,    1),
    ('монтаж розподільчої коробки в стіну',  'PIECE', 'GYPSUM_BUILDING',      0.3,  2),
    ('монтаж розподільчої коробки на стелю', 'PIECE', 'BOX_JUNCTION_SURFACE', 1,    1),
    ('монтаж розподільчої коробки на стелю', 'PIECE', 'DOWEL_NAIL',           2,    2),

    -- ---- Cable and ducts (ruling 1) --------------------------------------------------------------
    ('прокладка кабелю',                         'LINEAR_METER', 'CABLE_COPPER', 1, 1),
    ('прокладка кабелю в гофрі',                 'LINEAR_METER', 'CABLE_COPPER', 1, 1),
    ('прокладка кабелю в гофрі',                 'LINEAR_METER', 'CONDUIT_16',   1, 2),
    ('прокладка кабелю в металорукаві',          'LINEAR_METER', 'CABLE_COPPER', 1, 1),
    ('прокладка кабелю в металорукаві',          'LINEAR_METER', 'METAL_HOSE',   1, 2),
    ('прокладка силового кабелю',                'LINEAR_METER', 'CABLE_POWER',  1, 1),
    ('прокладка силового кабелю в гофрі трубі',  'LINEAR_METER', 'CABLE_POWER',  1, 1),
    ('прокладка силового кабелю в гофрі трубі',  'LINEAR_METER', 'CONDUIT_20',   1, 2),
    ('прокладання кабелю для генератора',        'LINEAR_METER', 'CABLE_POWER',  1, 1),
    ('прокладання кабелю для зарядної станції',  'LINEAR_METER', 'CABLE_POWER',  1, 1),
    ('прокладання кабелю в траншеї в захисній трубі (пнд)', 'LINEAR_METER', 'CABLE_POWER',     1, 1),
    ('прокладання кабелю в траншеї в захисній трубі (пнд)', 'LINEAR_METER', 'PIPE_HDPE_CABLE', 1, 2),
    ('прокладка закладної гофри для можливого резервного живлення', 'LINEAR_METER', 'CONDUIT_20', 1, 1),
    ('прокладка закладної гофри для резервного живлення',           'LINEAR_METER', 'CONDUIT_20', 1, 1),
    ('прокладка закладної гофри в штробі для теплої підлоги',       'LINEAR_METER', 'CONDUIT_16', 1, 1),
    ('монтаж кабель каналу',                     'LINEAR_METER', 'TRUNKING',     1, 1),
    ('монтаж кабель каналу',                     'LINEAR_METER', 'DOWEL_NAIL',   3, 2),
    ('прокладка інтернет-кабелю витої пари',     'LINEAR_METER', 'CABLE_UTP',    1, 1),
    ('прокладання кабелю відеонагляду',          'LINEAR_METER', 'CABLE_UTP',    1, 1),
    ('монтаж тв-кабелю',                         'LINEAR_METER', 'CABLE_COAX',   1, 1),
    -- Ruling 2: the usual 20×20 chase.
    ('заробка штроб',                            'LINEAR_METER', 'GYPSUM_BUILDING', 0.4, 1),
    ('опресовка наконечників кабельних',         'PIECE',        'CABLE_LUG',    1, 1),

    -- ---- Panels ----------------------------------------------------------------------------------
    ('монтаж електрощита накладного 12-36 модулів',   'PIECE', 'PANEL_BOX_36',    1, 1),
    ('монтаж електрощита накладного 12-36 модулів',   'PIECE', 'DOWEL_NAIL',      4, 2),
    ('монтаж електрощита накладного 48-60 модулів',   'PIECE', 'PANEL_BOX_60',    1, 1),
    ('монтаж електрощита накладного 48-60 модулів',   'PIECE', 'DOWEL_NAIL',      4, 2),
    ('монтаж електрощита накладного 72-90 модулів',   'PIECE', 'PANEL_BOX_90',    1, 1),
    ('монтаж електрощита накладного 72-90 модулів',   'PIECE', 'DOWEL_NAIL',      4, 2),
    ('монтаж електрощита накладного 120-180 модулів', 'PIECE', 'PANEL_BOX_180',   1, 1),
    ('монтаж електрощита накладного 120-180 модулів', 'PIECE', 'DOWEL_NAIL',      4, 2),
    ('монтаж електрощита в нішу',                     'PIECE', 'PANEL_BOX_FLUSH', 1, 1),

    -- ---- Modular devices (ruling 3), one per position --------------------------------------------
    ('установка автомата однополюсного',    'PIECE', 'MCB_1P', 1, 1),
    ('установка автомата двополюсного',     'PIECE', 'MCB_2P', 1, 1),
    ('установка автомата трьохполюсного',   'PIECE', 'MCB_3P', 1, 1),
    ('установка автомата чотирьохполюсного', 'PIECE', 'MCB_4P', 1, 1),
    ('установка автомата диференційного',   'PIECE', 'RCBO',   1, 1),
    ('установка пзв двополюсний',                                    'PIECE', 'RCD_2P', 1, 1),
    ('установка пристрою захисного відключення (пзв) - двухполюсний', 'PIECE', 'RCD_2P', 1, 1),
    ('установка пзв чотирьохполюсний',                                'PIECE', 'RCD_4P', 1, 1),
    ('установка пристрою захисного відключення (пзв) - чотирьохполюсний', 'PIECE', 'RCD_4P', 1, 1),
    ('установка обмежувача перенапруги (узіп)',               'PIECE', 'SPD',  1, 1),
    ('установка пристрою захисту від дугового пробою (пвдп)', 'PIECE', 'AFDD', 1, 1),
    -- An unqualified voltage relay in a flat is the single-phase one.
    ('установка реле напруги',              'PIECE', 'VOLTAGE_RELAY',     1, 1),
    ('установка реле напруги однофазне',    'PIECE', 'VOLTAGE_RELAY',     1, 1),
    ('установка реле напруги трифазне',     'PIECE', 'VOLTAGE_RELAY_3PH', 1, 1),
    ('установка контактора 220в',           'PIECE', 'CONTACTOR_220',     1, 1),
    ('установка контактора 380в',           'PIECE', 'CONTACTOR_380',     1, 1),
    ('установка автомата перекидного двополюсного',     'PIECE', 'CHANGEOVER_2P',       1, 1),
    ('установка автомата перекидного чотирьохполюсного', 'PIECE', 'CHANGEOVER_4P',       1, 1),
    ('установка реле перекидного чотирьохполюсного',    'PIECE', 'CHANGEOVER_RELAY_4P', 1, 1),
    ('установка рубильника реверсивного',   'PIECE', 'REVERSING_SWITCH',   1, 1),
    ('нульові шини',                        'PIECE', 'NEUTRAL_BAR',        1, 1),
    ('встановлення розподільчого блоку на дин-рейку', 'PIECE', 'DISTRIBUTION_BLOCK', 1, 1),
    ('установка крос-модуля',               'PIECE', 'CROSS_MODULE',       1, 1)
) AS v(name_key, unit, code, qty, ord)
JOIN material m ON m.code = v.code;

-- -------------------------------------------------------------------------------------------------
-- 4. «Checked, consumes nothing» — chasing and drilling (a disc and a bit are tool wear), bores for
--    boxes, tests and continuity checks, layout, set-up, the panel schedule, and taking a fitting
--    off the wall.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'ELECTRICAL', v.name_key, v.unit, NULL, NULL, 'QUANTITY', 0
FROM (VALUES
    ('штроблення під електрокабель 20х20 мм (в бетоні)', 'LINEAR_METER'),
    ('штроблення під електрокабель 20х20 мм (в цеглі)',  'LINEAR_METER'),
    ('штроблення під електрокабель 20х40 мм (в цеглі)',  'LINEAR_METER'),
    ('штроблення під електрокабель 20х60 мм (в цеглі)',  'LINEAR_METER'),
    ('штроблення під електрокабель 40х20 мм (в бетоні)', 'LINEAR_METER'),
    ('штроблення під електрокабель 50х250 мм (в цеглі)', 'LINEAR_METER'),
    ('штроблення під електрокабель 60х20 мм (в бетоні)', 'LINEAR_METER'),
    ('штроблення під електрокабель 20х20 в газоблоці',   'LINEAR_METER'),
    ('штроблення під електрокабель в гіпсокартоні',      'LINEAR_METER'),
    ('штроблення під кабель канал',                      'LINEAR_METER'),
    ('свердління наскрізних отворів (у бетоні), в залежності від діаметра отвору - від',      'PIECE'),
    ('свердління наскрізних отворів (у гіпсокартоні), в залежності від діаметра отвору - від', 'PIECE'),
    ('свердління наскрізних отворів (у цеглі), в залежності від діаметра отвору - від',       'PIECE'),
    ('свердління наскрізних отворів у бетоні',                                    'PIECE'),
    ('свердління наскрізних отворів у гіпсокартоні',                              'PIECE'),
    ('свердління наскрізних отворів у цеглі',                                     'PIECE'),
    ('свердління отвору під кабель в капітальній стіні',                          'PIECE'),
    ('свердління отвору під прокладку електрокабелю в капітальній стіні - від',   'PIECE'),
    ('свердління під встановлення точкового світильника в гіпсокартоні',          'PIECE'),
    ('свердління під встановлення точкового світильника в рейковій стелі',        'PIECE'),
    ('свердління під точковий світильник в гіпсокартоні',                         'PIECE'),
    ('свердління під точковий світильник в рейковій стелі',                       'PIECE'),
    ('отвір для підрозетника в бетоні',              'PIECE'),
    ('отвір для підрозетника в газоблоці',           'PIECE'),
    ('отвір для підрозетника в гіпсокартоні',        'PIECE'),
    ('отвір для підрозетника в цеглі',               'PIECE'),
    ('отвір для розподільчої коробки в бетоні',      'PIECE'),
    ('отвір для розподільчої коробки в гіпсокартоні', 'PIECE'),
    ('отвір для розподільчої коробки в цеглі',       'PIECE'),
    ('вимірювання опору заземлювального пристрою',                      'PIECE'),
    ('вимірювання опору ізоляції кабельних ліній',                      'PIECE'),
    ('перевірка спрацювання пзв та вимірювання опору петлі фаза-нуль',  'PIECE'),
    ('перевірка цілісності електричного кола (продзвонка), будинки - індивідуальний підхід', 'SET'),
    ('перевірка цілісності електричного кола (продзвонка), квартири',   'M2'),
    ('перевірка цілісності кола продзвонка',                            'M2'),
    ('розмітка трас і точок електрики на стінах і стелі',               'M2'),
    ('налаштування хмарного відеодоступу',                              'PIECE'),
    ('налаштування wi-fi роутера точки доступу',                        'PIECE'),
    ('налаштування хаба та сценаріїв розумного дому',                   'SET'),
    ('маркування ліній і складання схеми щита та кабельного журналу',   'SET'),
    ('демонтаж розетки, вимикача або світильника',                      'PIECE')
) AS v(name_key, unit);

-- -------------------------------------------------------------------------------------------------
-- 5. Self-checks, each about THIS migration's own work (review B-50).
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    orphans   text;
    ambiguous text;
    normed    int;
BEGIN
    -- 5a. Every ELECTRICAL key names a live ELECTRICAL WORK position (a typo here is mine).
    SELECT string_agg(DISTINCT n.name_key || ' [' || n.unit || ']', ', ')
      INTO orphans
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.trade = 'ELECTRICAL'
       AND NOT EXISTS (
            SELECT 1 FROM catalog_templates t
             WHERE t.trade = 'ELECTRICAL' AND t.type = 'WORK' AND t.unit = n.unit
               AND lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) = n.name_key
       );
    IF orphans IS NOT NULL THEN
        RAISE EXCEPTION 'V153: ELECTRICAL norms name no live catalog position: %', orphans;
    END IF;

    -- 5b. Two trades norming one (name, unit) cannot be resolved.
    SELECT string_agg(name_key || ' [' || unit || ']', ', ' ORDER BY name_key)
      INTO ambiguous
      FROM (SELECT name_key, unit FROM material_norm
             WHERE owner_id IS NULL
             GROUP BY name_key, unit
            HAVING count(DISTINCT trade) > 1
                OR (count(DISTINCT trade) = 1 AND count(trade) <> count(*))) AS a;
    IF ambiguous IS NOT NULL THEN
        RAISE EXCEPTION 'V153: a name and unit two trades both norm cannot be resolved: %', ambiguous;
    END IF;

    -- 5c. Row count: 48 positions with a material + 41 recorded verdicts, so a half-applied list cannot
    --     pass quietly. With the trade-less organizational rows, 92 of 177 positions answer.
    SELECT count(DISTINCT name_key || '|' || unit) INTO normed
      FROM material_norm WHERE owner_id IS NULL AND trade = 'ELECTRICAL';
    IF normed <> 89 THEN
        RAISE EXCEPTION 'V153: expected 89 normed ELECTRICAL positions, found %', normed;
    END IF;
END $$;
