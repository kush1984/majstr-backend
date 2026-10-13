-- =================================================================================================
-- V167 — the trade-by-trade review: one job, one wording. Duplicates leave the LIBRARY.
--
-- Source: the «Прибрати / об'єднати» tables of `C:\Work\prompts\trade-review\` 03-07 (PAINTER,
-- FLOORING, BUILDER, ELECTRICAL, PLUMBING), re-read against the V166 library. Owner ruling
-- (2026-10-13), after reading the list: «так, прибирай за списком» — which revisits V112's «з позицій
-- нічого не викидаємо» (TRADES-REVIEW §3 item 4) for the LIBRARY only.
--
-- WHAT IS REMOVED, AND WHAT IS NOT. The losing wording leaves `catalog_templates`, so a NEW master is
-- no longer handed the same job twice at two prices. Nothing a master owns is deleted:
--   * his catalog copy of the loser STAYS (his price, his estimates); if he does NOT also own the
--     survivor, the copy is RENAMED to the survivor — his price kept — because the default bundle line
--     moves to the survivor and a missing name prices at 0 ₴ (V158's reasoning). His pending
--     PRICE_DRIFT notice follows that rename;
--   * every DEFAULT bundle line on the loser moves to the survivor (or is dropped where the bundle
--     already carries the survivor); a FORK's line moves only where his catalog carries the survivor;
--   * norms stay on the loser's key (estimates already written); the survivor gets the loser's shipped
--     norms only when it has none of its own, and a master whose copy was renamed gets his forks
--     copied to the survivor's key. The pair is recorded in `catalog_renamed_position` (V158), which is
--     what tells «every shipped norm names a live position» that the loser's key is kept on purpose;
--   * `price_insight_candidate` rows on a removed library row cascade away (ON DELETE CASCADE, V94) —
--     an admin suggestion about a wording that no longer ships.
--
-- WHICH ONE SURVIVES: the row the bundles use, else the fuller wording, else the one the report named.
-- Prices are NOT touched here: where the bundles' row is the cheaper one («Доріжки з бетону з
-- армуванням» 270 beside 550, «продзвонка» 100 beside 240) it stays at its price; a master's own
-- copies keep theirs either way.
--
-- NOT DONE: cross-trade pairs (a V116-shaped merge each), pairs in different units, pairs where the
-- report could not say which is right (skirting «до 8 см», the balcony block, the doorway), and
-- rollups beside their parts.
--
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

CREATE TEMP TABLE _dup (trade varchar(50), loser varchar(255), survivor varchar(255), unit varchar(20)) ON COMMIT DROP;

INSERT INTO _dup VALUES
    -- ---- BUILDER ---------------------------------------------------------------------------------
    ('BUILDER', 'Доріжок з бетону з армуванням',                       'Доріжки з бетону з армуванням',                       'M2'),
    ('BUILDER', 'Доріжки з бетону без армування',                      'Доріжок з бетону без армування',                      'M2'),
    ('BUILDER', 'Монтаж відливу водовідведення в плитку',              'Монтаж відливу (водовідведення) в тротуарну плитку',  'PIECE'),
    ('BUILDER', 'Облаштування жолоба для зливу води в плитці',         'Облаштування жолоба для зливу води в тротуарній плитці', 'LINEAR_METER'),
    ('BUILDER', 'Планування ущільнення віброплитою під плитку до 20см', 'Планування і ущільнення віброплитою під тротуарну плитку до 20 см', 'M2'),
    ('BUILDER', 'Установка останнього ряду плитки на розчин',          'Установка останнього ряду тротуарної плитки на розчин', 'LINEAR_METER'),
    ('BUILDER', 'Установка піскоприймача в плитку',                    'Установка піскоприймача в тротуарну плитку',          'PIECE'),
    ('BUILDER', 'Зварювання рами розпашних воріт комплекс',            'Зварювання рами розпашних воріт (зварювання, монтаж, фарбування)', 'SET'),
    ('BUILDER', 'Зварювання рами розсувних воріт комплекс',            'Зварювання рами розсувних воріт (закладна, зварювання, монтаж, фарбування)', 'SET'),
    ('BUILDER', 'Зварювання рами розсувних воріт понад 7м комплекс',   'Зварювання рами розсувних воріт (закладна, зварювання, монтаж, фарбування) понад 7 м', 'SET'),
    ('BUILDER', 'Зварювання рами хвіртки комплекс',                    'Зварювання рами хвіртки (зварювання, монтаж, фарбування)', 'SET'),
    ('BUILDER', 'Монтаж двоскатного даху комплекс',                    'Монтаж двоскатного даху (кроква, обрешітка, контррейка, черепиця)', 'M2'),
    ('BUILDER', 'Монтаж односкатного даху комплекс',                   'Монтаж односкатного даху (кроква, обрешітка, контррейка, черепиця)', 'M2'),
    ('BUILDER', 'Монтаж чотирьохскатного даху комплекс',               'Монтаж чотирьохскатного даху (кроква, обрешітка, контррейка, черепиця)', 'M2'),
    ('BUILDER', 'Монтаж маурлєта',                                     'Монтаж маурлата',                                     'LINEAR_METER'),
    ('BUILDER', 'Облаштування покрівель наплавлення руберойду один шар', 'Облаштування покрівель з наплавленням руберойду в один шар', 'M2'),
    ('BUILDER', 'Облаштування покрівель наплавлення руберойду два шари', 'Облаштування покрівель з наплавленням руберойду в два шари', 'M2'),
    ('BUILDER', 'Паркан з профнастилу до 1.6м до 50мп',  'Паркан з профнастилу висотою до 1,6м (траншея, цоколь, стовбчик, рама, фарбування, профнастил) до 50м.п.', 'LINEAR_METER'),
    ('BUILDER', 'Паркан з профнастилу до 1.6м від 50мп', 'Паркан з профнастилу висотою до 1,6м (траншея, цоколь, стовбчик, рама, фарбування, профнастил) від 50м.п.', 'LINEAR_METER'),
    ('BUILDER', 'Паркан з дерева ранчо до 1.6м до 50мп',  'Паркан з дерева типу "Ранчо" висотою до 1,6м (траншея, цоколь, стовбчик, фарбування пульверизатором) до 50м.п.', 'LINEAR_METER'),
    ('BUILDER', 'Паркан з дерева ранчо до 1.6м від 50мп', 'Паркан з дерева типу "Ранчо" висотою до 1,6м (траншея, цоколь, стовбчик, фарбування пульверизатором) від 50м.п.', 'LINEAR_METER'),
    ('BUILDER', 'Паркан з штахет ламель до 1.6м до 50мп',  'Паркан з штахет/ламель висотою до 1,6м (траншея, цоколь, стовбчик, рама, фарбування, ламель) до 50м.п.', 'LINEAR_METER'),
    ('BUILDER', 'Паркан з штахет ламель до 1.6м від 50мп', 'Паркан з штахет/ламель висотою до 1,6м (траншея, цоколь, стовбчик, рама, фарбування, ламель) від 50м.п.', 'LINEAR_METER'),
    ('BUILDER', 'Паркан габіон до 1.6м',                'Паркан "Габіон" (траншея, цоколь, стовбчик, сітка, камінь) висотою до 1,6', 'LINEAR_METER'),
    ('BUILDER', 'Монтаж шлакоблоків для цоколя паркана', 'Монтаж шлакоблоків/євроблоків для цоколя паркана (1 ряд)', 'LINEAR_METER'),
    ('BUILDER', 'Цоколь паркану бетонний 20х50',         'Цоколь паркану бетонний (копка, армування, опалубка) 20х50', 'LINEAR_METER'),

    -- ---- ELECTRICAL ------------------------------------------------------------------------------
    ('ELECTRICAL', 'Перевірка цілісності кола продзвонка',          'Перевірка цілісності електричного кола (продзвонка), квартири', 'M2'),
    ('ELECTRICAL', 'Прокладка закладної гофри для резервного живлення', 'Прокладка закладної гофри для можливого резервного живлення', 'LINEAR_METER'),
    ('ELECTRICAL', 'Прокладка ретро проводу лофт відкрита',         'Прокладка ретро проводу в стилі "лофт" (відкрита)',   'LINEAR_METER'),
    ('ELECTRICAL', 'Свердління наскрізних отворів у бетоні',        'Свердління наскрізних отворів (у бетоні), в залежності від діаметра отвору - від', 'PIECE'),
    ('ELECTRICAL', 'Свердління наскрізних отворів у цеглі',         'Свердління наскрізних отворів (у цеглі), в залежності від діаметра отвору - від', 'PIECE'),
    ('ELECTRICAL', 'Свердління наскрізних отворів у гіпсокартоні',  'Свердління наскрізних отворів (у гіпсокартоні), в залежності від діаметра отвору - від', 'PIECE'),
    ('ELECTRICAL', 'Свердління отвору під кабель в капітальній стіні', 'Свердління отвору під прокладку електрокабелю в капітальній стіні - від', 'PIECE'),
    ('ELECTRICAL', 'Свердління під встановлення точкового світильника в гіпсокартоні', 'Свердління під точковий світильник в гіпсокартоні', 'PIECE'),
    ('ELECTRICAL', 'Свердління під встановлення точкового світильника в рейковій стелі', 'Свердління під точковий світильник в рейковій стелі', 'PIECE'),
    ('ELECTRICAL', 'Монтаж трекового світильника',                  'Установка та підключення трекового світильника',      'PIECE'),
    ('ELECTRICAL', 'Установка трекового світильника',               'Установка та підключення трекового світильника',      'PIECE'),
    ('ELECTRICAL', 'Установка підвісного світильника люстри простої', 'Установка та підключення підвісного світильника, люстри простої', 'PIECE'),
    ('ELECTRICAL', 'Установка настінного світильника бра',          'Установка та підключення настінного світильника, бра', 'PIECE'),
    ('ELECTRICAL', 'Установка світлодіодного світильника',          'Установка та підключення світлодіодного світильника', 'PIECE'),
    ('ELECTRICAL', 'Установка накладних розетки вимикача коробки',  'Установка накладних розетки, вимикача, розподільчої коробки', 'PIECE'),
    ('ELECTRICAL', 'Установка прохідних перехресних вимикачів',     'Установка та підключення прохідних, перехресних вимикачів', 'PIECE'),
    ('ELECTRICAL', 'Установка промислової розетки на фасаді',       'Установка та підключення промислової розетки на фасаді', 'PIECE'),
    ('ELECTRICAL', 'Установка розетки для ТВ розгалуджувача',       'Установка розетки для ТВ, розгалуджувача або підсилювача ТВ', 'PIECE'),
    ('ELECTRICAL', 'Монтаж компʼютерної розетки RJ45',              'Установка інтернет-розетки RJ45',                     'PIECE'),
    ('ELECTRICAL', 'Установка розетки комп''ютерної',               'Установка інтернет-розетки RJ45',                     'PIECE'),
    ('ELECTRICAL', 'Установка терморегулятора теплої підлоги',      'Установка та підключення терморегулятора теплої підлоги', 'PIECE'),
    ('ELECTRICAL', 'Установка дверного дзвінка дротового',          'Установка та підключення дверного дзвінка дротового', 'PIECE'),
    ('ELECTRICAL', 'Установка дверного дзвінка бездротового',       'Установка та підключення дверного дзвінка бездротового', 'PIECE'),
    ('ELECTRICAL', 'Установка пристрою захисного відключення (ПЗВ) - двополюсний',   'Установка ПЗВ двополюсний',   'PIECE'),
    ('ELECTRICAL', 'Установка пристрою захисного відключення (ПЗВ) - чотириполюсний', 'Установка ПЗВ чотириполюсний', 'PIECE'),
    ('ELECTRICAL', 'Установка реле напруги',                        'Установка реле напруги однофазне',                    'PIECE'),
    ('ELECTRICAL', 'Установка та підключення стабілізатора напруги', 'Установка стабілізатора напруги',                    'PIECE'),

    -- ---- PLUMBING --------------------------------------------------------------------------------
    ('PLUMBING', 'Виводи по точках (з встановленням кінцевого елементу)', 'Виводи по точках з кінцевим елементом',          'POINT'),
    ('PLUMBING', 'Монтаж фільтра очищення води (легка система) - 2 точки', 'Установка фільтра питної води під мийку',       'POINT'),
    ('PLUMBING', 'Установка зворотного осмосу',                     'Монтаж фільтра зворотного осмосу',                    'PIECE'),
    ('PLUMBING', 'Монтаж обладнання свердловини комплекс',          'Монтаж обладнання свердловини /запірної арматури (кранів, клапанів), гідроакумулятора та автоматики', 'SET'),
    ('PLUMBING', 'Установка лічильника води простої складності',    'Установка та підключення лічильника води простої складності', 'PIECE'),
    ('PLUMBING', 'Установка лічильника води підвищеної складності', 'Установка та підключення лічильника води підвищеної складності', 'PIECE'),
    ('PLUMBING', 'Установка манометра',                             'Установка та підключення манометра',                  'PIECE'),
    ('PLUMBING', 'Монтаж заміна стояка пластик на пластик одна панель', 'Монтаж (заміна) стояка каналізації (пластиковий на пластиковий) з проходом через одну панель', 'PIECE'),
    ('PLUMBING', 'Монтаж заміна стояка пластик на пластик дві панелі',  'Монтаж (заміна) стояка каналізації (пластиковий на пластиковий) з проходом через дві панелі', 'PIECE'),
    ('PLUMBING', 'Монтаж заміна стояка чавун на пластик одна панель',   'Монтаж (заміна) стояка каналізації (чавунний на пластиковий) з проходом через одну панель', 'PIECE'),
    ('PLUMBING', 'Монтаж заміна стояка чавун на пластик дві панелі',    'Монтаж (заміна) стояка каналізації (чавунний на пластиковий) з проходом через дві панелі', 'PIECE'),
    ('PLUMBING', 'Монтаж бойлера непрямого нагріву',                'Установка та підключення бойлера непрямого нагріву',  'PIECE'),
    ('PLUMBING', 'Установка бойлера непрямого нагріву',             'Установка та підключення бойлера непрямого нагріву',  'PIECE'),
    ('PLUMBING', 'Монтаж гідрострілки',                             'Монтаж гідрострілки опалення',                        'PIECE'),
    ('PLUMBING', 'Виготовлення ніші під шафу колектора в бетоні',   'Виготовлення ніші під шафу колектора/гребінки (в бетоні)', 'PIECE'),
    ('PLUMBING', 'Виготовлення ніші під шафу колектора в цеглі',    'Виготовлення ніші під шафу колектора/гребінки (в цеглі)', 'PIECE'),
    ('PLUMBING', 'Установка радіатора вертикального чавунного',     'Установка чавунного радіатора опалення',              'PIECE'),
    ('PLUMBING', 'Установка рушникосушки',                          'Установка та підключення рушникосушки',               'PIECE'),
    ('PLUMBING', 'Установка душового піддону готового',             'Установка та підключення душового піддону (готового)', 'PIECE'),
    ('PLUMBING', 'Установка пральної сушильної машини',             'Установка та підключення пральної, сушильної машини', 'PIECE'),
    ('PLUMBING', 'Монтаж витяжки',                                  'Монтаж витяжки (без відведення або до існуючого вентканала)', 'PIECE'),

    -- ---- PAINTER ---------------------------------------------------------------------------------
    ('PAINTER', 'Грунтовка поверхонь перед шпаклівкою, фарбуванням, поклейкою тощо', 'Грунтування перед шпаклівкою', 'M2'),
    ('PAINTER', 'Фарбування грунт-фарбою',                          'Грунт-фарба (праймер під фарбу)',                     'M2'),
    ('PAINTER', 'Чищення бетонних плит /підготовчі роботи/',        'Чищення бетонних плит підготовчі',                    'M2'),
    ('PAINTER', 'Герметизація швів стиків акрилом мастикою',        'Герметизація швів, стиків акрилом, спеціальною мастикою', 'LINEAR_METER'),
    ('PAINTER', 'Укривання підлоги картоном',                       'Захист підлоги картоном',                             'M2'),
    ('PAINTER', 'Шпаклівка коробів укосів ніш під фарбування',      'Шпаклівка коробів, укосів, ніш та виступів під фарбування', 'LINEAR_METER'),
    ('PAINTER', 'Поклейка шпалер 50см без підбору',                 'Поклейка шпалер шириною 50 см на стіну (без підбору)', 'M2'),
    ('PAINTER', 'Поклейка шпалер 50см з підбором',                  'Поклейка шпалер шириною 50 см на стіну (з підбором)', 'M2'),
    ('PAINTER', 'Поклейка шпалер 100см без підбору',                'Поклейка шпалер шириною 100 см на стіну (без підбору)', 'M2'),
    ('PAINTER', 'Поклейка шпалер 100см з підбором',                 'Поклейка шпалер шириною 100 см на стіну (з підбором)', 'M2'),
    ('PAINTER', 'Фарбування стельових багетів, молдінгів (поліуретанових) до 6 см', 'Фарбування молдинга/багета до 6 см', 'LINEAR_METER'),
    ('PAINTER', 'Монтаж стельових багетів пінопласт до 8см',        'Монтаж стельових багетів (простих - з пінопласту і т.п.) до 8 см', 'LINEAR_METER'),
    ('PAINTER', 'Декоративна штукатурка гротто 1000 ліній',         'Нанесення декоративної штукатурки "гротто", "1000 ліній"', 'M2'),
    ('PAINTER', 'Декоративна штукатурка ефект бетону арт бетон',    'Нанесення декоративної штукатурки "ефект бетону", "арт бетон"', 'M2'),
    ('PAINTER', 'Штукатурка з ефектом бетону',                      'Нанесення декоративної штукатурки "ефект бетону", "арт бетон"', 'M2'),
    ('PAINTER', 'Декоративна штукатурка китайський шовк вельвет лава сахара', 'Нанесення декоративної штукатурки "китайський шовк", "вельвет", "лава", "сахара"', 'M2'),
    ('PAINTER', 'Декоративна штукатурка мармурова крихта ферозит',  'Нанесення декоративної штукатурки "мармурова крихта"', 'M2'),
    ('PAINTER', 'Мармурова крихта',                                 'Нанесення декоративної штукатурки "мармурова крихта"', 'M2'),
    ('PAINTER', 'Декоративна штукатурка мікробетон',                'Нанесення декоративної штукатурки "мікробетон"',      'M2'),
    ('PAINTER', 'Декоративна штукатурка травертин',                 'Нанесення декоративної штукатурки "травертин"',       'M2'),
    ('PAINTER', 'Обшивка ОСП фанерою',                              'Обшивка орієнтовано-стружковою плитою (ОСП), фанерою', 'M2'),
    ('PAINTER', 'Стеля Армстронг',                                  'Підвісна стеля Армстронг',                            'M2'),
    ('PAINTER', 'Штукатурка під стельовий профіль натяжна стеля',   'Штукатурка під стельовий профіль по периметру (натяжна стеля)', 'LINEAR_METER'),
    ('PAINTER', 'Штукатурка укосів - від',                          'Штукатурка укосів',                                   'LINEAR_METER'),
    ('PAINTER', 'Армування сіткою',                                 'Армування стін сіткою',                               'M2'),
    ('PAINTER', 'Штукатурка стін (до 2 см)',                        'Штукатурка стін ручна до 2см',                        'M2'),
    ('PAINTER', 'Штукатурка стін (від 2 см)',                       'Штукатурка стін ручна від 2см',                       'M2'),
    ('PAINTER', 'Штукатурка стін (від 5 см)',                       'Штукатурка стін ручна понад 5 см (у кілька шарів, по сітці)', 'M2'),
    ('PAINTER', 'Машинна штукатурка стін',                          'Штукатурка стін машинкою до 2см',                     'M2'),

    -- ---- FLOORING --------------------------------------------------------------------------------
    ('FLOORING', 'Підрізка ламінату защільнення',                   'Підрізка ламінату (без плінтуса), защільнення',       'LINEAR_METER'),
    ('FLOORING', 'Укладання кварцвінілу',                           'Укладання замкового кварцвінілу (SPC) на підкладку',  'M2'),
    ('FLOORING', 'Шліфування бетону стяжки',                        'Шліфування бетонної основи',                          'M2'),
    ('FLOORING', 'Чистка підлоги порохотягом підготовка',           'Підготовка поверхні (очищення і т.п.)',               'M2');

-- -------------------------------------------------------------------------------------------------
-- 0. Preconditions: both rows are where we think they are.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE v_bad text;
BEGIN
    SELECT string_agg(d.trade || ' / ' || d.loser || ' → ' || d.survivor, '; ') INTO v_bad FROM _dup d
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct WHERE ct.trade = d.trade AND ct.type = 'WORK'
                         AND ct.unit = d.unit AND lower(trim(ct.name)) = lower(trim(d.loser)))
        OR NOT EXISTS (SELECT 1 FROM catalog_templates ct WHERE ct.trade = d.trade AND ct.type = 'WORK'
                         AND ct.unit = d.unit AND lower(trim(ct.name)) = lower(trim(d.survivor)))
        OR lower(trim(d.loser)) = lower(trim(d.survivor));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V167: a duplicate pair is not in the library as expected: %', v_bad;
    END IF;

    -- A loser another trade still ships is a cross-trade pair — not this migration's shape.
    SELECT string_agg(d.loser, '; ') INTO v_bad FROM _dup d
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct WHERE ct.trade <> d.trade AND ct.type = 'WORK'
                     AND ct.unit = d.unit AND lower(trim(ct.name)) = lower(trim(d.loser)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V167: a loser is shipped by another trade too: %', v_bad;
    END IF;
END $$;

CREATE TEMP TABLE _key ON COMMIT DROP AS
SELECT d.*,
       lower(btrim(replace(replace(regexp_replace(d.loser, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) AS loser_key,
       lower(btrim(replace(replace(regexp_replace(d.survivor, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) AS survivor_key
  FROM _dup d;

INSERT INTO catalog_renamed_position (old_name_key, new_name_key, unit, renamed_in)
SELECT loser_key, survivor_key, unit, 'V167' FROM _key
ON CONFLICT DO NOTHING;

-- -------------------------------------------------------------------------------------------------
-- 1. Norms: a survivor with no shipped norm of its own takes the loser's.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id, qty_per_unit,
                           waste_percent, sort_order, basis, default_param, baseline_param)
SELECT gen_random_uuid(), NULL, n.trade, k.survivor_key, n.unit, n.material_id, n.qty_per_unit,
       n.waste_percent, n.sort_order, n.basis, n.default_param, n.baseline_param
  FROM _key k
  JOIN material_norm n ON n.owner_id IS NULL AND n.name_key = k.loser_key AND n.unit = k.unit
 WHERE NOT EXISTS (SELECT 1 FROM material_norm x WHERE x.owner_id IS NULL
                     AND x.name_key = k.survivor_key AND x.unit = k.unit)
   -- two losers may map to one survivor: take the first loser's set only
   AND k.loser = (SELECT min(k2.loser) FROM _key k2 WHERE k2.trade = k.trade AND k2.survivor = k.survivor
                    AND EXISTS (SELECT 1 FROM material_norm y WHERE y.owner_id IS NULL
                                  AND y.name_key = k2.loser_key AND y.unit = k2.unit));

-- -------------------------------------------------------------------------------------------------
-- 2. A master who owns the loser but not the survivor: his copy becomes the survivor (price kept),
--    his pending notice and his forked norms follow.
-- -------------------------------------------------------------------------------------------------
CREATE TEMP TABLE _moved ON COMMIT DROP AS
SELECT DISTINCT ON (ci.id) ci.id, ci.owner_id, k.loser, k.survivor, k.loser_key, k.survivor_key, k.unit
  FROM _key k
  JOIN catalog_items ci
    ON ci.source = 'LIBRARY' AND ci.type = 'WORK' AND ci.unit = k.unit
   AND lower(trim(ci.name)) = lower(trim(k.loser))
 WHERE NOT EXISTS (SELECT 1 FROM catalog_items x WHERE x.owner_id = ci.owner_id AND x.type = 'WORK'
                     AND x.unit = k.unit AND lower(trim(x.name)) = lower(trim(k.survivor)))
 ORDER BY ci.id, k.loser;

-- Two losers of one survivor in one catalog: only one copy can take the name.
DELETE FROM _moved m
 WHERE EXISTS (SELECT 1 FROM _moved o WHERE o.owner_id = m.owner_id AND o.survivor = m.survivor
                 AND o.unit = m.unit AND o.id::text < m.id::text);

UPDATE catalog_update_notices n
   SET position_name = m.survivor
  FROM _moved m
 WHERE n.user_id = m.owner_id AND n.kind = 'PRICE_DRIFT' AND n.dismissed_at IS NULL
   AND lower(trim(n.position_name)) = lower(trim(m.loser));

UPDATE catalog_items ci SET name = m.survivor FROM _moved m WHERE ci.id = m.id;

INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id, qty_per_unit,
                           waste_percent, sort_order, basis, default_param, baseline_param)
SELECT gen_random_uuid(), n.owner_id, n.trade, m.survivor_key, n.unit, n.material_id, n.qty_per_unit,
       n.waste_percent, n.sort_order, n.basis, n.default_param, n.baseline_param
  FROM _moved m
  JOIN material_norm n ON n.owner_id = m.owner_id AND n.name_key = m.loser_key AND n.unit = m.unit
 WHERE NOT EXISTS (SELECT 1 FROM material_norm x WHERE x.owner_id = m.owner_id
                     AND x.name_key = m.survivor_key AND x.unit = m.unit);

-- -------------------------------------------------------------------------------------------------
-- 3. Bundle lines: default bundles always; a master's fork where his catalog carries the survivor.
--    A bundle that already carries the survivor drops the loser's line instead.
-- -------------------------------------------------------------------------------------------------
DELETE FROM estimate_template_items i
 USING _key k, estimate_templates t
 WHERE t.id = i.template_id AND i.type = 'WORK' AND i.unit = k.unit
   AND lower(trim(i.name)) = lower(trim(k.loser))
   AND (t.owner_id IS NULL OR EXISTS (SELECT 1 FROM template_default_override o
                                        WHERE o.forked_template_id = t.id AND o.user_id = t.owner_id))
   AND EXISTS (SELECT 1 FROM estimate_template_items s
                WHERE s.template_id = i.template_id AND s.unit = k.unit
                  AND lower(trim(s.name)) = lower(trim(k.survivor)));

-- Two losers of one survivor in one bundle: keep the first, so the rename below cannot double it.
DELETE FROM estimate_template_items i
 USING _key k, _key k2, estimate_template_items j
 WHERE i.unit = k.unit AND lower(trim(i.name)) = lower(trim(k.loser))
   AND k2.trade = k.trade AND k2.survivor = k.survivor AND k2.loser <> k.loser
   AND j.template_id = i.template_id AND j.unit = k2.unit AND lower(trim(j.name)) = lower(trim(k2.loser))
   AND (j.sort_order, j.id::text) < (i.sort_order, i.id::text);

UPDATE estimate_template_items i
   SET name = k.survivor
  FROM _key k, estimate_templates t
 WHERE t.id = i.template_id AND t.owner_id IS NULL AND t.is_default
   AND i.type = 'WORK' AND i.unit = k.unit AND lower(trim(i.name)) = lower(trim(k.loser));

UPDATE estimate_template_items i
   SET name = k.survivor
  FROM _key k, estimate_templates t, template_default_override o
 WHERE t.id = i.template_id AND t.owner_id IS NOT NULL
   AND o.forked_template_id = t.id AND o.user_id = t.owner_id
   AND i.type = 'WORK' AND i.unit = k.unit AND lower(trim(i.name)) = lower(trim(k.loser))
   AND EXISTS (SELECT 1 FROM catalog_items ci WHERE ci.owner_id = t.owner_id AND ci.type = 'WORK'
                 AND ci.unit = k.unit AND lower(trim(ci.name)) = lower(trim(k.survivor)));

-- -------------------------------------------------------------------------------------------------
-- 4. The loser leaves the library.
-- -------------------------------------------------------------------------------------------------
DELETE FROM catalog_templates ct
 USING _key k
 WHERE ct.trade = k.trade AND ct.type = 'WORK' AND ct.unit = k.unit
   AND lower(trim(ct.name)) = lower(trim(k.loser));

-- -------------------------------------------------------------------------------------------------
-- 5. Self-checks, each about THIS migration's own work (review B-50).
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE v_bad text;
BEGIN
    SELECT string_agg(DISTINCT i.name, '; ') INTO v_bad
      FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
      JOIN _key k ON lower(trim(i.name)) = lower(trim(k.loser)) AND i.unit = k.unit
     WHERE t.owner_id IS NULL AND t.is_default;
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V167: default bundle lines still on a removed wording: %', v_bad;
    END IF;

    SELECT string_agg(k.loser, '; ') INTO v_bad FROM _key k
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct WHERE ct.trade = k.trade AND ct.unit = k.unit
                     AND lower(trim(ct.name)) = lower(trim(k.loser)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V167: a removed wording is still in the library: %', v_bad;
    END IF;

    -- Every default bundle line still names a library position (a miss prices at 0 ₴).
    SELECT string_agg(DISTINCT i.name, '; ') INTO v_bad
      FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
     WHERE t.owner_id IS NULL AND t.is_default
       AND i.name IN (SELECT survivor FROM _key)
       AND NOT EXISTS (SELECT 1 FROM catalog_templates ct WHERE ct.type = i.type AND ct.unit = i.unit
                         AND lower(trim(ct.name)) = lower(trim(i.name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V167: a bundle line names no library position: %', v_bad;
    END IF;

    -- A survivor answers whenever its loser did.
    SELECT string_agg(DISTINCT k.survivor, '; ') INTO v_bad FROM _key k
     WHERE EXISTS (SELECT 1 FROM material_norm n WHERE n.owner_id IS NULL AND n.name_key = k.loser_key AND n.unit = k.unit)
       AND NOT EXISTS (SELECT 1 FROM material_norm n WHERE n.owner_id IS NULL AND n.name_key = k.survivor_key AND n.unit = k.unit);
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V167: a survivor answers nothing where its loser did: %', v_bad;
    END IF;
END $$;
