-- =================================================================================================
-- V176 — the market price review of 2026-10-25: 253 library prices, 14 structural edits,
-- 67 new positions
--
-- Source: docs/price-review-2026-10/price-review-2026-10-decisions.json (the owner's decisions, applied
-- as given) beside majstr-zvirka-tsin-2026-10.xlsx (each figure's sources: URL, what it says, city,
-- date). Work-only prices, Kyiv / Lviv / Ukraine. The comment on each price row names its source
-- domains; «owner ruling» marks the 38 the owner set by hand. Owner rulings on the recon are dated
-- 2026-10-26.
--
-- 1. PRICES: 183 up, 70 down, 14 of them from a zero (TILING's V157 «договірна» zeros the owner now
--    prices). V157's shape: the library row moves; a master still carrying our OLD number on a LIBRARY
--    copy gets a PRICE_DRIFT notice; a price he set himself is never touched or nagged about; a master
--    with a pending notice for the position gets no second one. NEW HERE: 24 of these positions are
--    one row that two or more trades ship, and a master holds ONE catalog row for it, filed under one
--    trade (V118). So the notice goes only to a copy filed under the trade whose price moved
--    (`catalog_items.trade = _reprice.trade`) — «Демонтаж перегородки з гіпсокартону» moves 60 → 140 in
--    DEMOLITION and 60 → 150 in DRYWALL, and a name-only match would hand one row two notices. Shared
--    rows keep a price per trade (V169). Rows that also get a structural edit are repriced FIRST, under
--    their old name and unit; the notice then follows the rename.
--
-- 2. RENAMES (6): V158's shape verbatim — library row, default bundle lines, every master's LIBRARY copy
--    whatever its price, his pending notice, his forks' lines, every norm COPIED to the new key (the old
--    key keeps its rows), recorded in `catalog_renamed_position`.
--
-- 3. UNIT CHANGES (5): «a unit change cannot reach a master's copy» held V159-V164 back; owner ruling:
--    a unit change moves exactly what a rename moves — the library row, default bundle lines, every
--    master's LIBRARY copy (his price kept; a master who already owns the name in the new unit is left
--    alone), his forks' lines where his catalog now carries the new unit. Recorded in
--    `catalog_renamed_position` (same key, the OLD unit). The one position with a norm, «Відведення лінії
--    фарби…», does NOT get its m² coefficient copied (0,5 m of tape per m² means nothing per м.п.):
--    owner ruling, 1,0 m of masking tape per м.п. — one strip along the line, waste added separately.
--    A master's fork of that norm stays on the old unit for the same reason.
--
-- 4. DELETES (2): both duplicates are in ANOTHER unit than their survivor (PIECE 500 beside м.п. 212;
--    SET 825 beside PIECE 20 000), so V167's merge cannot rename a master's copy into the survivor.
--    Owner ruling: the LIBRARY row only — masters' copies and estimate lines are not touched. No bundle
--    uses either, so nothing prices at 0 ₴. The loser's norm stays on its key, recorded in
--    `catalog_renamed_position`.
--
-- 5. NEW POSITIONS (67): BUILDER 8, DEMOLITION 7, DRYWALL 3, ELECTRICAL 12, FLOORING 11, PAINTER 11, PLUMBING 11, TILING 4. ONE new catalog version and
--    NOTHING pushed (owner ruling: 67 positions unasked is too many): a master takes them with «Додати
--    нові з довідника», which offers the templates above his `last_synced_catalog_version` — so that
--    column is NOT advanced here. No notice either: the COUNT notice reads «Ваш каталог оновлено —
--    Додано N позицій», which would be false; an «available in the library» notice is a new feature.
--    Categories are the trade's existing ones plus one new DEMOLITION category, «Зовнішні роботи»
--    (owner ruling). One wording departs from the decision file: «…перекриття (Teriva)» names a brand,
--    which a catalog position never does — shipped as «…(балки та пустотілі блоки)». «Демонтаж
--    змішувача» joins PLUMBING at 300 under the wording DEMOLITION ships at 200: one shared row, a price
--    per trade. V118's ranking is re-run (V120's rule).
--
-- NOT DONE: the 34 «deferred» rows of the decision file — among them PAINTER's eleven organizational
-- zeros, which is why «Прибирання…», «Винесення…» and «Розвантаження…» stay at 0 in PAINTER while
-- TILING prices them.
--
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

CREATE TEMP TABLE _reprice (trade varchar(50), name varchar(255), unit varchar(20),
                            old_price numeric(15,2), new_price numeric(15,2)) ON COMMIT DROP;
CREATE TEMP TABLE _ren (trade varchar(50), old_name varchar(255), new_name varchar(255),
                        unit varchar(20)) ON COMMIT DROP;
CREATE TEMP TABLE _unit (trade varchar(50), name varchar(255), from_unit varchar(20),
                         to_unit varchar(20)) ON COMMIT DROP;
CREATE TEMP TABLE _del (trade varchar(50), name varchar(255), unit varchar(20),
                        survivor varchar(255), survivor_unit varchar(20)) ON COMMIT DROP;
CREATE TEMP TABLE _desc (trade varchar(50), name varchar(255), unit varchar(20),
                         description varchar(500)) ON COMMIT DROP;
CREATE TEMP TABLE _pos (trade varchar(50), category varchar(255), name varchar(255), unit varchar(20),
                        price numeric(15,2)) ON COMMIT DROP;

INSERT INTO _reprice VALUES
    ('BUILDER',    'Анкерування однієї стіни до іншої',                                                'PIECE',            45.00,     90.00),  -- market review, see the xlsx
    ('BUILDER',    'Армування кладки сіткою арматурою',                                                'M2',               50.00,    100.00),  -- owner ruling
    ('BUILDER',    'Армування фасаду сітка перетяжка',                                                 'M2',              290.00,    240.00),  -- rabotniki.ua
    ('BUILDER',    'Бетонні сходи поворотна сходинка (бетон вручну)',                                  'PIECE',          1350.00,   3450.00),  -- owner ruling
    ('BUILDER',    'Бетонні сходи поворотна сходинка (бетон з міксера)',                               'PIECE',          1350.00,   3000.00),  -- owner ruling
    ('BUILDER',    'Бетонні сходи пряма сходинка (бетон вручну)',                                      'PIECE',          1350.00,   2650.00),  -- owner ruling
    ('BUILDER',    'Бетонні сходи пряма сходинка (бетон з міксера)',                                   'PIECE',          1350.00,   2300.00),  -- owner ruling
    ('BUILDER',    'Вентильований фасад',                                                              'M2',              850.00,   1150.00),  -- rabotniki.ua, bud.arsel.com.ua
    ('BUILDER',    'Виготовлення монолітних колон',                                                    'M3',             3050.00,   6700.00),  -- owner ruling
    ('BUILDER',    'Виготовлення терас веранд комплекс',                                               'M2',            11800.00,   2800.00),  -- market review, see the xlsx
    ('BUILDER',    'Викорчовування пнів',                                                              'PIECE',          1350.00,    650.00),  -- owner ruling
    ('BUILDER',    'Влаштування відмостки бетонної',                                                   'M2',              480.00,    650.00),  -- rabotniki.ua, rbk-alba.com
    ('BUILDER',    'Влаштування монолітного перекриття (армування, бетонування)',                      'M3',             3400.00,   5800.00),  -- rabotniki.ua, rbk-alba.com
    ('BUILDER',    'Влаштування фундаментної плити (армування, бетонування)',                          'M3',             4000.00,   5000.00),  -- rabotniki.ua, rbk-alba.com
    ('BUILDER',    'Доріжки з бетону з армуванням',                                                    'M2',              270.00,    580.00),  -- owner ruling
    ('BUILDER',    'Доріжок з бетону без армування',                                                   'M2',              500.00,    460.00),  -- owner ruling
    ('BUILDER',    'Зварювання каркасу сходів поворотна сходинка',                                     'PIECE',          1350.00,   1750.00),  -- owner ruling
    ('BUILDER',    'Кладка стовпів паркану під розшивку',                                              'PIECE',          2500.00,   3500.00),  -- market review, see the xlsx
    ('BUILDER',    'Кладка стін з газоблоку або піноблоку 300 мм',                                     'M3',             2300.00,   2600.00),  -- rabotniki.ua, rbk-alba.com
    ('BUILDER',    'Кладка стін з керамоблоку 380 мм на теплий розчин або клей-піну',                  'M2',              750.00,   1100.00),  -- market review, see the xlsx
    ('BUILDER',    'Кладка стін з шлакоблоку',                                                         'M3',             1900.00,   2600.00),  -- rabotniki.ua, rbk-alba.com
    ('BUILDER',    'Монолітні стіни',                                                                  'M3',             2350.00,   5750.00),  -- owner ruling
    ('BUILDER',    'Монтаж водостоків жолоб труба',                                                    'LINEAR_METER',    190.00,    350.00),  -- rabotniki.ua, pilo-material.kiev.ua
    ('BUILDER',    'Монтаж лобової вітрової конькової планки',                                         'LINEAR_METER',    240.00,    150.00),  -- owner ruling
    ('BUILDER',    'Монтаж маурлата',                                                                  'LINEAR_METER',    100.00,    200.00),  -- market review, see the xlsx
    ('BUILDER',    'Монтаж перемичок над прорізами',                                                   'PIECE',          1350.00,    550.00),  -- market review, see the xlsx
    ('BUILDER',    'Монтаж профнастилу на паркан',                                                     'M2',              180.00,    260.00),  -- market review, see the xlsx
    ('BUILDER',    'Монтаж утеплювача на перекриття більше 100мм',                                     'M2',              135.00,    250.00),  -- owner ruling
    ('BUILDER',    'Монтаж утеплювача на перекриття до 100мм',                                         'M2',              135.00,    170.00),  -- owner ruling
    ('BUILDER',    'Монтаж утеплювача на покрівлю більше 100мм',                                       'M2',              135.00,    250.00),  -- owner ruling
    ('BUILDER',    'Монтаж утеплювача на покрівлю до 100мм',                                           'M2',              135.00,    170.00),  -- owner ruling
    ('BUILDER',    'Монтаж утеплювача під стяжку',                                                     'M2',              302.00,    160.00),  -- owner ruling
    ('BUILDER',    'Монтаж фальцевої покрівлі',                                                        'M2',              100.00,    600.00),  -- rabotniki.ua, rating-remont-kvartir.com.ua, bud.arsel.com.ua
    ('BUILDER',    'Навіс легкий комплекс',                                                            'M2',              630.00,   1300.00),  -- owner ruling
    ('BUILDER',    'Навіс максимальний комплекс',                                                      'M2',              630.00,   2200.00),  -- owner ruling
    ('BUILDER',    'Навіс середній комплекс',                                                          'M2',              630.00,   1700.00),  -- owner ruling
    ('BUILDER',    'Облаштування ПВХ-мембрани покрівлі',                                               'M2',              145.00,    320.00),  -- rabotniki.ua, rating-remont-kvartir.com.ua, bud.arsel.com.ua
    ('BUILDER',    'Облаштування водостічної воронки',                                                 'PIECE',          1350.00,    850.00),  -- market review, see the xlsx
    ('BUILDER',    'Облаштування дверної пройми',                                                      'PIECE',          1350.00,   1750.00),  -- owner ruling
    ('BUILDER',    'Облицювання фасаду натуральним або штучним каменем',                               'M2',              250.00,    900.00),  -- market review, see the xlsx
    ('BUILDER',    'Приготування бетону для тротуарної плитки',                                        'M3',              250.00,   1000.00),  -- market review, see the xlsx
    ('BUILDER',    'Приготування гарцовки для тротуарної плитки',                                      'M3',              250.00,    900.00),  -- rabotniki.ua, pl2t.com
    ('BUILDER',    'Укладання армованої сітки для тротуарної плитки',                                  'LINEAR_METER',    250.00,     90.00),  -- market review, see the xlsx
    ('BUILDER',    'Укладання бітумної черепиці',                                                      'M2',              460.00,    400.00),  -- rabotniki.ua, pilo-material.kiev.ua, rating-remont-kvartir.com.ua
    ('BUILDER',    'Укладання підкладкового килима',                                                   'M2',              182.00,    110.00),  -- owner ruling
    ('BUILDER',    'Укладання шиферу або ондуліна',                                                    'M2',              182.00,    280.00),  -- owner ruling
    ('BUILDER',    'Установка тротуарного бордюру коло радіус',                                        'LINEAR_METER',    150.00,    400.00),  -- market review, see the xlsx
    ('BUILDER',    'Установка флюгера ковпака на димохід',                                             'PIECE',           200.00,   1200.00),  -- market review, see the xlsx
    ('DEMOLITION', 'Вивезення сміття контейнером',                                                     'PIECE',          4500.00,   3000.00),  -- rating-remont-kvartir.com.ua, demontazh-kyiv.com.ua
    ('DEMOLITION', 'Вирізання прорізу в бетонній стіні товщиною до 100 мм',                            'M2',             1167.00,   1400.00),  -- rabotniki.ua, bud.arsel.com.ua
    ('DEMOLITION', 'Демонтаж вхідних дверей',                                                          'PIECE',           200.00,    550.00),  -- rabotniki.ua, bud.arsel.com.ua, kabanchik.ua
    ('DEMOLITION', 'Демонтаж вікна балконного блоку',                                                  'PIECE',           250.00,    400.00),  -- rabotniki.ua, bud.arsel.com.ua, rbk-alba.com, rating-remont-kvartir.com.ua
    ('DEMOLITION', 'Демонтаж керамічної плитки з підлоги',                                             'M2',               60.00,    150.00),  -- rbk-alba.com, bud.arsel.com.ua, rating-remont-kvartir.com.ua, kabanchik.ua
    ('DEMOLITION', 'Демонтаж ламінату або паркету',                                                    'M2',               25.00,    100.00),  -- rabotniki.ua, rbk-alba.com, rating-remont-kvartir.com.ua, kabanchik.ua
    ('DEMOLITION', 'Демонтаж міжкімнатних дверей з коробкою',                                          'PIECE',           150.00,    400.00),  -- rabotniki.ua, bud.arsel.com.ua, rating-remont-kvartir.com.ua, kabanchik.ua, demontazh-kyiv.com.ua
    ('DEMOLITION', 'Демонтаж перегородки з гіпсокартону',                                              'M2',               60.00,    140.00),  -- rabotniki.ua, rbk-alba.com, bud.arsel.com.ua, rating-remont-kvartir.com.ua, kabanchik.ua
    ('DEMOLITION', 'Демонтаж перегородки з піноблоку або газоблоку',                                   'M2',               60.00,    200.00),  -- rabotniki.ua, rbk-alba.com, bud.arsel.com.ua, rating-remont-kvartir.com.ua
    ('DEMOLITION', 'Демонтаж перегородки з цегли в півцеглини (120 мм)',                               'M2',               70.00,    230.00),  -- rabotniki.ua, rbk-alba.com, bud.arsel.com.ua, rating-remont-kvartir.com.ua, kabanchik.ua
    ('DEMOLITION', 'Демонтаж плитки зі стін',                                                          'M2',               60.00,    170.00),  -- rabotniki.ua, rbk-alba.com, bud.arsel.com.ua, kabanchik.ua
    ('DEMOLITION', 'Демонтаж підвіконня',                                                              'PIECE',           100.00,    300.00),  -- rabotniki.ua, bud.arsel.com.ua, rbk-alba.com
    ('DEMOLITION', 'Демонтаж стяжки бетонної',                                                         'M2',               65.00,    180.00),  -- rabotniki.ua, rbk-alba.com, bud.arsel.com.ua, rating-remont-kvartir.com.ua, remonthub.org
    ('DEMOLITION', 'Демонтаж цегляної стіни в цеглину (250 мм)',                                       'M2',              100.00,    380.00),  -- rabotniki.ua, rbk-alba.com, bud.arsel.com.ua, kabanchik.ua
    ('DEMOLITION', 'Збивання штукатурки зі стін',                                                      'M2',               60.00,    170.00),  -- rabotniki.ua, rbk-alba.com, bud.arsel.com.ua, kabanchik.ua, rating-remont-kvartir.com.ua
    ('DEMOLITION', 'Підйом будматеріалів на поверх',                                                   'PIECE',           263.00,     15.00),  -- market review, see the xlsx
    ('DEMOLITION', 'Розширення дверного прорізу',                                                      'PIECE',           225.00,   1750.00),  -- owner ruling
    ('DEMOLITION', 'Розширення отвору під вхідні двері',                                               'PIECE',          1000.00,   1800.00),  -- rabotniki.ua, bud.arsel.com.ua
    ('DRYWALL',    'Демонтаж гіпсокартонної стелі',                                                    'M2',               60.00,    150.00),  -- market review, see the xlsx
    ('DRYWALL',    'Демонтаж перегородки з гіпсокартону',                                              'M2',               60.00,    150.00),  -- market review, see the xlsx
    ('DRYWALL',    'Монтаж відкосів з гіпсокартону на клей',                                           'LINEAR_METER',    230.00,    350.00),  -- market review, see the xlsx
    ('DRYWALL',    'Монтаж відкосів з гіпсокартону на профілі',                                        'LINEAR_METER',    230.00,    380.00),  -- rabotniki.ua, bud.arsel.com.ua, remonthub.org, kabanchik.ua, budver.com
    ('DRYWALL',    'Монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар',               'M2',              800.00,    650.00),  -- rabotniki.ua, remonthub.org, liv-comfort.kyiv.ua, kabanchik.ua, stroyremdizayn.kiev.ua, budver.com
    ('DRYWALL',    'Монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари',              'M2',              900.00,    780.00),  -- remonthub.org, liv-comfort.kyiv.ua, stroyremdizayn.kiev.ua, rabotniki.ua
    ('DRYWALL',    'Монтаж напівкруглої конструкції ГКЛ',                                              'M2',              450.00,    700.00),  -- market review, see the xlsx
    ('DRYWALL',    'Монтаж профілю тіньового шва по периметру стелі',                                  'LINEAR_METER',    400.00,    600.00),  -- rabotniki.ua
    ('DRYWALL',    'Монтаж треків прихованого карниза',                                                'LINEAR_METER',   1400.00,   1800.00),  -- rabotniki.ua
    ('DRYWALL',    'Облаштування ніші ГКЛ з підсвічуванням',                                           'LINEAR_METER',    400.00,    600.00),  -- rabotniki.ua, kabanchik.ua, budver.com, remonthub.org
    ('DRYWALL',    'Обшивка інсталяції в т.ч. отвори',                                                 'PIECE',           900.00,   2000.00),  -- stroyremdizayn.kiev.ua, rabotniki.ua, budver.com
    ('DRYWALL',    'Шпаклювання та шліфування гіпсокартону (без склополотна)',                         'M2',              130.00,    220.00),  -- kabanchik.ua, rabotniki.ua, budver.com, creativebuild.com.ua
    ('ELECTRICAL', 'Закладення штроб гіпсовою сумішшю',                                                'LINEAR_METER',     88.00,     65.00),  -- rabotniki.ua, bud.arsel.com.ua, budver.com
    ('ELECTRICAL', 'Комутація в підрозетнику',                                                         'PIECE',           400.00,    200.00),  -- owner ruling
    ('ELECTRICAL', 'Монтаж кабель каналу',                                                             'LINEAR_METER',    400.00,    100.00),  -- rabotniki.ua, bud.arsel.com.ua, budver.com, altek.kiev.ua, san-sanych.ua, amperage.com.ua
    ('ELECTRICAL', 'Монтаж розподільчої коробки в стіну',                                              'PIECE',           120.00,     80.00),  -- market review, see the xlsx
    ('ELECTRICAL', 'Монтаж розподільчої коробки на стелю',                                             'PIECE',           120.00,     90.00),  -- market review, see the xlsx
    ('ELECTRICAL', 'Монтаж світлодіодної стрічки в профілі',                                           'LINEAR_METER',    350.00,    300.00),  -- rabotniki.ua, bud.arsel.com.ua, kabanchik.ua, liv-comfort.kyiv.ua, amperage.com.ua, rbk-alba.com, san-sanych.ua
    ('ELECTRICAL', 'Монтаж та підключення ДБЖ',                                                        'POINT',          8000.00,  10000.00),  -- market review, see the xlsx
    ('ELECTRICAL', 'Монтаж та установка домофона',                                                     'PIECE',           800.00,   1000.00),  -- rabotniki.ua, kabanchik.ua, remmo.lviv.ua, san-sanych.ua
    ('ELECTRICAL', 'Монтаж шинопровода трекової системи',                                              'LINEAR_METER',    147.00,    450.00),  -- amperage.com.ua, rbk-alba.com
    ('ELECTRICAL', 'Отвір для розподільчої коробки в гіпсокартоні',                                    'PIECE',           200.00,    100.00),  -- market review, see the xlsx
    ('ELECTRICAL', 'Отвір для розподільчої коробки в цеглі',                                           'PIECE',           200.00,    150.00),  -- rabotniki.ua, bud.arsel.com.ua, kabanchik.ua, altek.kiev.ua
    ('ELECTRICAL', 'Прокладка закладної гофри в штробі для теплої підлоги',                            'LINEAR_METER',    210.00,     60.00),  -- market review, see the xlsx
    ('ELECTRICAL', 'Прокладка кабелю',                                                                 'LINEAR_METER',    210.00,     50.00),  -- rabotniki.ua, bud.arsel.com.ua, rbk-alba.com, amperage.com.ua, kabanchik.ua
    ('ELECTRICAL', 'Прокладка кабелю в металорукаві',                                                  'LINEAR_METER',    210.00,     90.00),  -- market review, see the xlsx
    ('ELECTRICAL', 'Підключення бойлера (водонагрівача)',                                              'PIECE',           650.00,   1000.00),  -- altek.kiev.ua, budver.com
    ('ELECTRICAL', 'Підключення електричного рушникосушника',                                          'PIECE',           500.00,    700.00),  -- rabotniki.ua, bud.arsel.com.ua, budver.com
    ('ELECTRICAL', 'Свердління під точковий світильник в гіпсокартоні',                                'PIECE',            90.00,    150.00),  -- amperage.com.ua, rbk-alba.com, remmo.lviv.ua, san-sanych.ua
    ('ELECTRICAL', 'Складання люстри простої',                                                         'PIECE',           800.00,    500.00),  -- kabanchik.ua, san-sanych.ua, rbk-alba.com
    ('ELECTRICAL', 'Установка ПЗВ двополюсний',                                                        'PIECE',           500.00,    400.00),  -- rabotniki.ua, bud.arsel.com.ua, kabanchik.ua, amperage.com.ua, remmo.lviv.ua, san-sanych.ua
    ('ELECTRICAL', 'Установка автомата диференційного',                                                'PIECE',           500.00,    400.00),  -- kabanchik.ua, rabotniki.ua, budver.com, remmo.lviv.ua, altek.kiev.ua
    ('ELECTRICAL', 'Установка автомата перекидного чотириполюсного',                                   'PIECE',           700.00,   1000.00),  -- market review, see the xlsx
    ('ELECTRICAL', 'Установка блока живлення світлодіодної стрічки',                                   'PIECE',           150.00,    250.00),  -- amperage.com.ua, san-sanych.ua, rabotniki.ua, bud.arsel.com.ua, rbk-alba.com
    ('ELECTRICAL', 'Установка датчика руху або освітленості з підключенням',                           'PIECE',           500.00,    350.00),  -- rabotniki.ua, bud.arsel.com.ua, rbk-alba.com, remmo.lviv.ua, altek.kiev.ua
    ('ELECTRICAL', 'Установка кнопки дверного дзвінка',                                                'PIECE',           120.00,    180.00),  -- rabotniki.ua
    ('ELECTRICAL', 'Установка контактора 380В',                                                        'PIECE',           400.00,    550.00),  -- market review, see the xlsx
    ('ELECTRICAL', 'Установка ландшафтних світильників',                                               'PIECE',           240.00,    350.00),  -- rabotniki.ua, budver.com
    ('ELECTRICAL', 'Установка маршрутизатора комутатора',                                              'PIECE',           738.00,    700.00),  -- market review, see the xlsx
    ('ELECTRICAL', 'Установка реле перекидного чотириполюсного',                                       'PIECE',           400.00,   1000.00),  -- owner ruling
    ('ELECTRICAL', 'Установка стабілізатора напруги',                                                  'PIECE',          1000.00,   1400.00),  -- rabotniki.ua, bud.arsel.com.ua, rbk-alba.com, kabanchik.ua, remmo.lviv.ua, san-sanych.ua
    ('ELECTRICAL', 'Установка та підключення дверного дзвінка бездротового',                           'PIECE',           500.00,    300.00),  -- market review, see the xlsx
    ('ELECTRICAL', 'Установка та підключення дверного дзвінка дротового',                              'PIECE',           600.00,    400.00),  -- rabotniki.ua, kabanchik.ua, rbk-alba.com, san-sanych.ua
    ('ELECTRICAL', 'Установка та підключення електроплити, варочної поверхні',                         'PIECE',          1500.00,    900.00),  -- altek.kiev.ua, san-sanych.ua, remmo.lviv.ua, kabanchik.ua
    ('ELECTRICAL', 'Установка та підключення настінного світильника, бра',                             'PIECE',           450.00,    380.00),  -- rabotniki.ua, bud.arsel.com.ua, kabanchik.ua, rbk-alba.com, altek.kiev.ua, amperage.com.ua, san-sanych.ua
    ('ELECTRICAL', 'Установка та підключення однофазного лічильника електроенергії',                   'PIECE',           600.00,    700.00),  -- rabotniki.ua, bud.arsel.com.ua, kabanchik.ua, rbk-alba.com, amperage.com.ua, altek.kiev.ua, remmo.lviv.ua, san-sanych.ua
    ('ELECTRICAL', 'Установка та підключення прохідних, перехресних вимикачів',                        'PIECE',           320.00,    260.00),  -- kabanchik.ua, amperage.com.ua, remmo.lviv.ua, stroyremdizayn.kiev.ua
    ('ELECTRICAL', 'Установка та підключення підвісного світильника, люстри простої',                  'PIECE',           750.00,    600.00),  -- rabotniki.ua, bud.arsel.com.ua, kabanchik.ua, rbk-alba.com, altek.kiev.ua, san-sanych.ua, amperage.com.ua
    ('ELECTRICAL', 'Установка та підключення світлодіодного світильника',                              'PIECE',           450.00,    280.00),  -- rabotniki.ua, bud.arsel.com.ua, rbk-alba.com
    ('ELECTRICAL', 'Установка та підключення терморегулятора теплої підлоги',                          'PIECE',           600.00,    450.00),  -- rbk-alba.com, amperage.com.ua, altek.kiev.ua, san-sanych.ua, remmo.lviv.ua
    ('ELECTRICAL', 'Установка та підключення трифазного лічильника електроенергії',                    'PIECE',          1200.00,   1500.00),  -- rbk-alba.com, amperage.com.ua, kabanchik.ua, remmo.lviv.ua, san-sanych.ua
    ('ELECTRICAL', 'Штроблення під електрокабель 20х20 в газоблоці',                                   'LINEAR_METER',     50.00,    100.00),  -- remmo.lviv.ua, rbk-alba.com, san-sanych.ua
    ('ELECTRICAL', 'Штроблення під електрокабель 20х20 мм (в бетоні)',                                 'LINEAR_METER',    220.00,    200.00),  -- rabotniki.ua, bud.arsel.com.ua, kabanchik.ua, remmo.lviv.ua, rbk-alba.com
    ('FLOORING',   'Демонтаж лінолеуму ковроліну',                                                     'M2',               20.00,    100.00),  -- market review, see the xlsx
    ('FLOORING',   'Демонтаж плінтуса',                                                                'LINEAR_METER',     30.00,     60.00),  -- market review, see the xlsx
    ('FLOORING',   'Машинна стяжка самовирівнююча',                                                    'M2',              301.00,    350.00),  -- rabotniki.ua, mirafloor.com.ua
    ('FLOORING',   'Монтаж алюмінієвого плінтуса',                                                     'LINEAR_METER',    312.00,    370.00),  -- stroyremdizayn.kiev.ua, bud.arsel.com.ua
    ('FLOORING',   'Монтаж міжкімнатного порога',                                                      'LINEAR_METER',    400.00,    210.00),  -- market review, see the xlsx
    ('FLOORING',   'Монтаж утеплювача під стяжку',                                                     'M2',              302.00,    160.00),  -- market review, see the xlsx
    ('FLOORING',   'Настил лінолеуму ковроліну на клей',                                               'M2',              200.00,    260.00),  -- market review, see the xlsx
    ('FLOORING',   'Настилання підлоги з дошки по лагах',                                              'M2',              400.00,    450.00),  -- rabotniki.ua, stroyremdizayn.kiev.ua
    ('FLOORING',   'Плівкова ізоляція під стяжку',                                                     'M2',              134.00,     60.00),  -- market review, see the xlsx
    ('FLOORING',   'Укладання паркетної / інженерної дошки плаваючим способом',                        'M2',              420.00,    480.00),  -- rabotniki.ua, bud.arsel.com.ua
    ('FLOORING',   'Укладання фанери або OSB на стяжку чи лаги',                                       'M2',              250.00,    210.00),  -- rabotniki.ua, rating-remont-kvartir.com.ua
    ('METAL',      'Виготовлення металевого каркасу сходів (за сходинку)',                             'PIECE',           900.00,   1350.00),  -- market review, see the xlsx
    ('METAL',      'Виготовлення хвіртки металевої',                                                   'PIECE',          3500.00,   7000.00),  -- rabotniki.ua
    ('METAL',      'Демонтаж металоконструкцій',                                                       'KG',               12.00,      4.00),  -- rabotniki.ua, rating-remont-kvartir.com.ua
    ('METAL',      'Зварювальні роботи (електродом)',                                                  'HOUR',            400.00,    600.00),  -- duga.in.ua, rabotniki.ua
    ('METAL',      'Монтаж воріт',                                                                     'PIECE',          3000.00,   5000.00),  -- rabotniki.ua
    ('METAL',      'Монтаж перил та огородження',                                                      'LINEAR_METER',    400.00,   1000.00),  -- rabotniki.ua
    ('METAL',      'Монтаж стовпів для паркану',                                                       'PIECE',           500.00,    400.00),  -- rabotniki.ua
    ('PAINTER',    'Акрилення примикань',                                                              'LINEAR_METER',    100.00,    140.00),  -- market review, see the xlsx
    ('PAINTER',    'Венеціанська штукатурка',                                                          'M2',              400.00,    650.00),  -- rabotniki.ua, budver.com
    ('PAINTER',    'Герметизація швів, стиків акрилом, спеціальною мастикою',                          'LINEAR_METER',    220.00,    140.00),  -- market review, see the xlsx
    ('PAINTER',    'Грунтування',                                                                      'M2',               33.00,     40.00),  -- owner ruling
    ('PAINTER',    'Грунтування основи перед штукатуркою, армуванням',                                 'M2',               35.00,     40.00),  -- owner ruling
    ('PAINTER',    'Грунтування перед шпаклівкою',                                                     'M2',               50.00,     40.00),  -- owner ruling
    ('PAINTER',    'Змивання побілки зі стелі',                                                        'M2',               60.00,    100.00),  -- market review, see the xlsx
    ('PAINTER',    'Зняття шпалер',                                                                    'M2',               30.00,     60.00),  -- market review, see the xlsx
    ('PAINTER',    'Машинна штукатурка стель',                                                         'M2',              300.00,    450.00),  -- rabotniki.ua, kabanchik.ua, budver.com
    ('PAINTER',    'Нанесення декоративної штукатурки "гротто", "1000 ліній"',                         'M2',              700.00,    520.00),  -- rabotniki.ua, stroyremdizayn.kiev.ua
    ('PAINTER',    'Нанесення декоративної штукатурки "китайський шовк", "вельвет", "лава", "сахара"', 'M2',              650.00,    500.00),  -- rabotniki.ua, stroyremdizayn.kiev.ua
    ('PAINTER',    'Нанесення рідких шпалер',                                                          'M2',              180.00,    260.00),  -- rabotniki.ua, bud.arsel.com.ua, vikna.tv
    ('PAINTER',    'Обшивка стелі шалівкою',                                                           'M2',               80.00,    450.00),  -- market review, see the xlsx
    ('PAINTER',    'Поклейка шпалер шириною 100 см на стіну (без підбору)',                            'M2',              240.00,    200.00),  -- rabotniki.ua, bud.arsel.com.ua, remonthub.org, kabanchik.ua, stroyremdizayn.kiev.ua, vikna.tv
    ('PAINTER',    'Поклейка шпалер шириною 50 см на стіну (з підбором)',                              'M2',              300.00,    240.00),  -- kabanchik.ua, stroyremdizayn.kiev.ua, rabotniki.ua, bud.arsel.com.ua
    ('PAINTER',    'Підвісна стеля Армстронг',                                                         'M2',              170.00,    300.00),  -- market review, see the xlsx
    ('PAINTER',    'Стелі Грильято',                                                                   'M2',               80.00,    350.00),  -- market review, see the xlsx
    ('PAINTER',    'Утеплення укосів пінопластом',                                                     'LINEAR_METER',    600.00,    300.00),  -- market review, see the xlsx
    ('PAINTER',    'Фарбування нашовної планки',                                                       'LINEAR_METER',    260.00,     80.00),  -- market review, see the xlsx
    ('PAINTER',    'Шліфування штукатурки',                                                            'M2',               50.00,     90.00),  -- rabotniki.ua, stroyremdizayn.kiev.ua
    ('PAINTER',    'Штукатурка Марморіно',                                                             'M2',              500.00,    750.00),  -- rabotniki.ua, budver.com
    ('PAINTER',    'Штукатурка Марсельський віск',                                                     'M2',              500.00,    650.00),  -- rabotniki.ua, budver.com
    ('PAINTER',    'Штукатурка стін від 2см об''ємом до 50м2',                                         'M2',              280.00,    430.00),  -- market review, see the xlsx
    ('PAINTER',    'Штукатурка стін до 2см об''ємом до 50м2',                                          'M2',              280.00,    400.00),  -- market review, see the xlsx
    ('PAINTER',    'Штукатурка стін машинкою до 2см',                                                  'M2',              270.00,    340.00),  -- rabotniki.ua, stroyremdizayn.kiev.ua, kabanchik.ua, budver.com, domremonta.com.ua
    ('PAINTER',    'Штукатурка стін машинкою понад 2см',                                               'M2',              300.00,    380.00),  -- stroyremdizayn.kiev.ua, rabotniki.ua
    ('PLUMBING',   'Врізка в стояк водопроводу',                                                       'PIECE',          1500.00,   1100.00),  -- rabotniki.ua, bud.arsel.com.ua
    ('PLUMBING',   'Демонтаж радіатора опалення',                                                      'PIECE',           350.00,    450.00),  -- rbk-alba.com, liv-comfort.kyiv.ua, santehprofi.kyiv.ua, rimeik.com.ua
    ('PLUMBING',   'Демонтаж умивальника раковини',                                                    'PIECE',           200.00,    320.00),  -- rabotniki.ua, bud.arsel.com.ua, rbk-alba.com
    ('PLUMBING',   'Демонтаж унітазу',                                                                 'PIECE',           350.00,    450.00),  -- rabotniki.ua, bud.arsel.com.ua, rbk-alba.com, kabanchik.ua
    ('PLUMBING',   'Монтаж буферної ємності',                                                          'PIECE',          5190.00,   7000.00),  -- rabotniki.ua
    ('PLUMBING',   'Монтаж вентиляційної решітки',                                                     'PIECE',          1300.00,    250.00),  -- owner ruling
    ('PLUMBING',   'Монтаж гасника гідроудару (гідрокомпенсатора)',                                    'PIECE',          1300.00,    800.00),  -- owner ruling
    ('PLUMBING',   'Монтаж гідрострілки опалення',                                                     'PIECE',          1450.00,   1900.00),  -- rabotniki.ua
    ('PLUMBING',   'Монтаж зворотного клапана каналізації Ø110',                                       'PIECE',          1300.00,   1800.00),  -- rabotniki.ua, bud.arsel.com.ua
    ('PLUMBING',   'Монтаж змішувального вузла теплої підлоги',                                        'PIECE',          1200.00,   1600.00),  -- rabotniki.ua, santehprofi.kyiv.ua, rating-remont-kvartir.com.ua
    ('PLUMBING',   'Монтаж колектора водопостачання (гребінки) з кранами на виходах',                  'SET',            1300.00,   2000.00),  -- market review, see the xlsx
    ('PLUMBING',   'Монтаж колектора гребінки',                                                        'SET',            1281.00,   2000.00),  -- rabotniki.ua, santehprofi.kyiv.ua
    ('PLUMBING',   'Монтаж котельної (котел, бойлер, насоси, крани, фільтра) - від',                   'PIECE',         20000.00,  35000.00),  -- rabotniki.ua, santehprofi.kyiv.ua
    ('PLUMBING',   'Монтаж кульового крана',                                                           'PIECE',           300.00,    400.00),  -- rabotniki.ua, bud.arsel.com.ua, remonthub.org, budver.com
    ('PLUMBING',   'Монтаж кухонного подрібнювача',                                                    'PIECE',           900.00,   1250.00),  -- rabotniki.ua, bud.arsel.com.ua, budver.com, rating-remont-kvartir.com.ua
    ('PLUMBING',   'Монтаж магістрального фільтра механічного очищення',                               'PIECE',           620.00,   1000.00),  -- rabotniki.ua, kabanchik.ua
    ('PLUMBING',   'Монтаж металевого сифона',                                                         'PIECE',          1300.00,    800.00),  -- market review, see the xlsx
    ('PLUMBING',   'Монтаж насоса свердловини труби трос оголовок',                                    'SET',             825.00,   7500.00),  -- market review, see the xlsx
    ('PLUMBING',   'Монтаж насосної групи',                                                            'PIECE',          1203.00,   1500.00),  -- rabotniki.ua
    ('PLUMBING',   'Монтаж насосної станції',                                                          'PIECE',          1700.00,   3000.00),  -- rabotniki.ua
    ('PLUMBING',   'Монтаж панелі з клавішами змиву',                                                  'PIECE',          1300.00,    500.00),  -- market review, see the xlsx
    ('PLUMBING',   'Монтаж прихованої частини душової системи (вбудований корпус)',                    'PIECE',          1300.00,   2200.00),  -- rabotniki.ua, bud.arsel.com.ua, rating-remont-kvartir.com.ua, budver.com
    ('PLUMBING',   'Монтаж прихованої частини змішувача (душ, біде)',                                  'PIECE',          1500.00,   2000.00),  -- rabotniki.ua, bud.arsel.com.ua, budver.com
    ('PLUMBING',   'Монтаж редуктора тиску води',                                                      'PIECE',           500.00,    800.00),  -- rabotniki.ua, bud.arsel.com.ua, remonthub.org, santehprofi.kyiv.ua
    ('PLUMBING',   'Монтаж системи від протікання води',                                               'PIECE',           850.00,   2000.00),  -- market review, see the xlsx
    ('PLUMBING',   'Монтаж сололіфта',                                                                 'PIECE',          1300.00,   2400.00),  -- rabotniki.ua, budver.com
    ('PLUMBING',   'Монтаж трубної системи тепла підлога з утеплювачем',                               'M2',              200.00,    350.00),  -- rabotniki.ua, rbk-alba.com, santehprofi.kyiv.ua, news.telegraf.com.ua, remonthub.org
    ('PLUMBING',   'Монтаж фільтра зворотного осмосу',                                                 'PIECE',           850.00,   1600.00),  -- rabotniki.ua, kabanchik.ua, budver.com
    ('PLUMBING',   'Монтаж фільтра самопромивного',                                                    'POINT',           400.00,    900.00),  -- market review, see the xlsx
    ('PLUMBING',   'Монтаж інсталяції для унітазу',                                                    'PIECE',          2600.00,   2300.00),  -- rabotniki.ua, bud.arsel.com.ua, remonthub.org, kabanchik.ua, santehprofi.kyiv.ua, budver.com
    ('PLUMBING',   'Опресування (гідравлічне випробування) опалення',                                  'PIECE',          1800.00,   1300.00),  -- rabotniki.ua
    ('PLUMBING',   'Прокладання труб з ізоляцією',                                                     'LINEAR_METER',    100.00,    130.00),  -- rabotniki.ua, bud.arsel.com.ua, rating-remont-kvartir.com.ua
    ('PLUMBING',   'Прокладання труб на хомутах (каналізація)',                                        'LINEAR_METER',    220.00,    170.00),  -- market review, see the xlsx
    ('PLUMBING',   'Прокладка труб каналізації',                                                       'LINEAR_METER',    220.00,    170.00),  -- rabotniki.ua, bud.arsel.com.ua, rating-remont-kvartir.com.ua
    ('PLUMBING',   'Установка УФ знезаражувача води',                                                  'PIECE',          2532.00,   3000.00),  -- market review, see the xlsx
    ('PLUMBING',   'Установка ванни із гідромасажем',                                                  'PIECE',          7500.00,   6000.00),  -- rabotniki.ua, rating-remont-kvartir.com.ua, budver.com
    ('PLUMBING',   'Установка гідроакумулятора',                                                       'PIECE',          1200.00,   1500.00),  -- rabotniki.ua
    ('PLUMBING',   'Установка душової кабіни бокс',                                                    'PIECE',          2000.00,   4000.00),  -- market review, see the xlsx
    ('PLUMBING',   'Установка душової кабіни простої',                                                 'PIECE',          3000.00,   3500.00),  -- rabotniki.ua, bud.arsel.com.ua, santehprofi.kyiv.ua, budver.com
    ('PLUMBING',   'Установка електрокотла до 28 кВт',                                                 'PIECE',          2500.00,   4000.00),  -- rabotniki.ua, santehprofi.kyiv.ua, rbk-alba.com
    ('PLUMBING',   'Установка конденсаційного котла',                                                  'PIECE',          3000.00,   4500.00),  -- rabotniki.ua
    ('PLUMBING',   'Установка люка-ревізії простого',                                                  'PIECE',           400.00,    800.00),  -- owner ruling
    ('PLUMBING',   'Установка настінного двоконтурного котла',                                         'PIECE',          3000.00,   4000.00),  -- rabotniki.ua, santehprofi.kyiv.ua, vencon.ua
    ('PLUMBING',   'Установка настінного одноконтурного котла з бойлером',                             'PIECE',          3000.00,   5200.00),  -- rabotniki.ua
    ('PLUMBING',   'Установка пелетного котла',                                                        'PIECE',         10056.00,  13000.00),  -- market review, see the xlsx
    ('PLUMBING',   'Установка скляних розсувних дверей',                                               'PIECE',           300.00,    700.00),  -- owner ruling
    ('PLUMBING',   'Установка та підключення душового піддону (готового)',                             'PIECE',          4500.00,   2300.00),  -- rabotniki.ua, bud.arsel.com.ua, kabanchik.ua, santehprofi.kyiv.ua
    ('PLUMBING',   'Установка та підключення пральної, сушильної машини',                              'PIECE',          1000.00,    800.00),  -- rabotniki.ua, bud.arsel.com.ua, remonthub.org, santehprofi.kyiv.ua, pricesua.com
    ('PLUMBING',   'Установка твердопаливного котла',                                                  'PIECE',          6000.00,   9500.00),  -- rabotniki.ua, santehprofi.kyiv.ua
    ('PLUMBING',   'Установка унітазу на готову інсталяцію',                                           'PIECE',          1300.00,   1600.00),  -- rabotniki.ua, bud.arsel.com.ua, santehprofi.kyiv.ua
    ('PLUMBING',   'Установка штанги',                                                                 'PIECE',           300.00,    800.00),  -- rabotniki.ua
    ('PLUMBING',   'Штроблення в бетоні (каналізація)',                                                'LINEAR_METER',    200.00,    450.00),  -- market review, see the xlsx
    ('PLUMBING',   'Штроблення в газобетоні цеглі (каналізація)',                                      'LINEAR_METER',    150.00,    300.00),  -- market review, see the xlsx
    ('PLUMBING',   'Штроблення під трубу 100мм в бетоні',                                              'LINEAR_METER',    200.00,    450.00),  -- remonthub.org, rbk-alba.com, rimeik.com.ua
    ('PLUMBING',   'Штроблення під трубу 100мм в газобетоні цеглі',                                    'LINEAR_METER',    150.00,    300.00),  -- remonthub.org, rabotniki.ua, rbk-alba.com
    ('TILING',     'Винесення та вивезення будівельного сміття',                                       'M3',                0.00,    600.00),  -- market review, see the xlsx
    ('TILING',     'Виріз отворів до 70 мм',                                                           'PIECE',           196.00,    240.00),  -- rabotniki.ua, kabanchik.ua, plitochnik.kiev.ua, bud.arsel.com.ua, liv-comfort.kyiv.ua, stroyremdizayn.kiev.ua, remmo.lviv.ua, plitka.ua, 24tv.ua, remonthub.org
    ('TILING',     'Влаштування деформаційних швів',                                                   'LINEAR_METER',      0.00,    300.00),  -- owner ruling
    ('TILING',     'Гідроізоляція плівкова',                                                           'M2',                0.00,     60.00),  -- market review, see the xlsx
    ('TILING',     'Гідроізоляція сухою сумішшю',                                                      'M2',                0.00,    200.00),  -- market review, see the xlsx
    ('TILING',     'Заміна силіконового шва (видалення старого, нанесення нового)',                    'LINEAR_METER',    180.00,    280.00),  -- market review, see the xlsx
    ('TILING',     'Запил плитки під 45°',                                                             'LINEAR_METER',    367.00,    460.00),  -- rabotniki.ua, budver.com, kabanchik.ua, plitochnik.kiev.ua, bud.arsel.com.ua, liv-comfort.kyiv.ua, stroyremdizayn.kiev.ua, remmo.lviv.ua, plitka.ua
    ('TILING',     'Заповнення швів герметиком',                                                       'LINEAR_METER',    130.00,    220.00),  -- rabotniki.ua, plitochnik.kiev.ua, kabanchik.ua, stroyremdizayn.kiev.ua, plitka.ua, 24tv.ua
    ('TILING',     'Заповнення швів епоксидною сумішшю',                                               'M2',              241.00,    290.00),  -- budver.com, plitochnik.kiev.ua, 24tv.ua, remonthub.org, plitka.ua, liv-comfort.kyiv.ua
    ('TILING',     'Заповнення швів цементною сумішшю',                                                'M2',              116.00,    170.00),  -- rabotniki.ua, budver.com, kabanchik.ua, plitochnik.kiev.ua, bud.arsel.com.ua, remonthub.org, 24tv.ua, remmo.lviv.ua
    ('TILING',     'Затирання швів у декоративній плитці, мозаїці',                                    'M2',                0.00,    300.00),  -- market review, see the xlsx
    ('TILING',     'Монтаж лінійного душового каналу',                                                 'PIECE',             0.00,   1600.00),  -- owner ruling
    ('TILING',     'Монтаж трапу та формування ухилу до нього',                                        'PIECE',             0.00,   1500.00),  -- rabotniki.ua
    ('TILING',     'Облицювання плиткою короба',                                                       'M2',                0.00,    900.00),  -- market review, see the xlsx
    ('TILING',     'Поклейка гідроізоляційних муфт на водорозетки',                                    'PIECE',             0.00,    250.00),  -- market review, see the xlsx
    ('TILING',     'Прибирання приміщення після робіт',                                                'M2',                0.00,     40.00),  -- market review, see the xlsx
    ('TILING',     'Розвантаження матеріалу',                                                          'T',              1271.00,    750.00),  -- plitochnik.kiev.ua
    ('TILING',     'Сортування плитки та підбір малюнка',                                              'M2',              247.00,    100.00),  -- market review, see the xlsx
    ('TILING',     'Стяжка маякова цементна',                                                          'M2',              342.00,    400.00),  -- market review, see the xlsx
    ('TILING',     'Укладання декоративної плитки під «цеглу» або «камінь» (гіпсова, бетонна)',        'M2',                0.00,   1000.00),  -- market review, see the xlsx
    ('TILING',     'Укладання керамічного паркету, дрібної дошки',                                     'M2',              866.00,   1000.00),  -- plitochnik.kiev.ua, plitka.ua, rabotniki.ua, budver.com
    ('TILING',     'Укладання клінкерної підлогової плитки',                                           'M2',              891.00,   1100.00),  -- rabotniki.ua, budver.com, plitochnik.kiev.ua, stroyremdizayn.kiev.ua, remmo.lviv.ua
    ('TILING',     'Укладання плитки 1200х1200 мм',                                                    'M2',             1130.00,   1300.00),  -- plitochnik.kiev.ua, stroyremdizayn.kiev.ua
    ('TILING',     'Укладання плитки 300х300',                                                         'M2',              641.00,    800.00),  -- plitka.ua, plitochnik.kiev.ua, rabotniki.ua, kabanchik.ua, bud.arsel.com.ua, liv-comfort.kyiv.ua
    ('TILING',     'Укладання плитки 300х600',                                                         'M2',              594.00,    850.00),  -- rabotniki.ua, kabanchik.ua, plitochnik.kiev.ua, plitka.ua, bud.arsel.com.ua, liv-comfort.kyiv.ua, remmo.lviv.ua, remonthub.org, stroyremdizayn.kiev.ua
    ('TILING',     'Укладання плитки 600х1200',                                                        'M2',              732.00,    880.00),  -- plitka.ua, stroyremdizayn.kiev.ua, plitochnik.kiev.ua
    ('TILING',     'Укладання плитки 600х600',                                                         'M2',              631.00,    830.00),  -- rabotniki.ua, kabanchik.ua, plitochnik.kiev.ua, stroyremdizayn.kiev.ua, plitka.ua, bud.arsel.com.ua, liv-comfort.kyiv.ua, remmo.lviv.ua
    ('TILING',     'Укладання плитки 800х800',                                                         'M2',              779.00,    900.00),  -- stroyremdizayn.kiev.ua, plitka.ua, plitochnik.kiev.ua
    ('TILING',     'Укладання плитки кабанчик, «цегла»',                                               'M2',              998.00,   1300.00),  -- rabotniki.ua, budver.com, kabanchik.ua, stroyremdizayn.kiev.ua, plitka.ua
    ('TILING',     'Укладання плитки на відкос (плюс % до м.кв.)',                                     'PERCENT',          88.00,     60.00),  -- market review, see the xlsx
    ('TILING',     'Укладання плитки на шар клею більше 1 см',                                         'M2',                0.00,    150.00),  -- market review, see the xlsx
    ('TILING',     'Укладання плитки ялинкою (плюс % до м.кв.)',                                       'PERCENT',           0.00,     30.00),  -- market review, see the xlsx
    ('TILING',     'Укриття плівкою підлоги, дверей, сантехніки',                                      'M2',                0.00,     30.00),  -- market review, see the xlsx
    ('TILING',     'Формування кута з епоксидної суміші',                                              'M2',              287.00,    320.00),  -- market review, see the xlsx
    ('TILING',     'Шліфування стяжки (зняття перепадів до 5 мм)',                                     'M2',              392.00,    230.00)   -- market review, see the xlsx
;

INSERT INTO _ren VALUES
    ('ELECTRICAL', 'Установка АВР',
                'Установка модуля АВР (перемикача) на DIN-рейку', 'PIECE'),
    ('ELECTRICAL', 'Свердління отвору під прокладку електрокабелю в капітальній стіні - від',
                'Свердління отвору під прокладку електрокабелю в капітальній стіні понад 30 см - від', 'PIECE'),
    ('PLUMBING', 'Обв''язка радіатора один радіатор 2 точки',
                'Обв''язка радіатора (за точку; радіатор = 2 точки)', 'POINT'),
    ('PLUMBING', 'Установка штанги',
                'Установка душової штанги з лійкою', 'PIECE'),
    ('PLUMBING', 'Опломбування лічильника води',
                'Організація опломбування лічильника води', 'PIECE'),
    ('DEMOLITION', 'Підйом будматеріалів на поверх',
                'Підйом будматеріалів на поверх (за мішок, за кожен поверх)', 'PIECE');

INSERT INTO _unit VALUES
    ('TILING', 'Формування кута з епоксидної суміші', 'M2', 'LINEAR_METER'),
    ('PAINTER', 'Відведення лінії фарби довкола вікон/дверей', 'M2', 'LINEAR_METER'),
    ('PLUMBING', 'Установка фільтра питної води під мийку', 'POINT', 'PIECE'),
    ('PLUMBING', 'Монтаж фільтра самопромивного', 'POINT', 'PIECE'),
    ('ELECTRICAL', 'Монтаж та підключення ДБЖ', 'POINT', 'PIECE');

INSERT INTO _del VALUES
    ('FLOORING', 'Приклеювання стиків в місцях порожків, герметизація силіконом', 'PIECE',
                'Приклеювання стиків порожків герметизація', 'LINEAR_METER'),
    ('PLUMBING', 'Монтаж котельної котел бойлер насоси крани фільтра', 'SET',
                'Монтаж котельної (котел, бойлер, насоси, крани, фільтра) - від', 'PIECE');

INSERT INTO _desc VALUES
    ('PLUMBING', 'Прокладка труб в траншеї', 'LINEAR_METER', 'Без копання траншеї (земляні роботи — окремо)');

INSERT INTO _pos VALUES
    ('FLOORING', 'Підлога', 'Монтаж електричної теплої підлоги (мат/кабель)', 'M2', 300.00),
    ('FLOORING', 'Підлога', 'Підключення теплої підлоги до терморегулятора', 'PIECE', 350.00),
    ('TILING', 'Укладання плитки', 'Облицювання кухонного фартуха плиткою', 'M2', 850.00),
    ('TILING', 'Укладання плитки', 'Облицювання відкосів плиткою (за м.п.)', 'LINEAR_METER', 800.00),
    ('FLOORING', 'Підлога', 'Демонтаж ламінату', 'M2', 130.00),
    ('FLOORING', 'Підлога', 'Демонтаж паркету / дерев''яної підлоги', 'M2', 150.00),
    ('FLOORING', 'Підлога', 'Демонтаж ПВХ-плитки / кварцвінілу', 'M2', 250.00),
    ('TILING', 'Підготовчі роботи', 'Демонтаж керамічного плінтуса', 'LINEAR_METER', 260.00),
    ('FLOORING', 'Стяжка', 'Встановлення маяків для стяжки', 'M2', 120.00),
    ('FLOORING', 'Стяжка', 'Стяжка з пінополістиролбетону', 'M2', 450.00),
    ('FLOORING', 'Підлога', 'Укладання кварцвінілу ялинкою', 'M2', 700.00),
    ('FLOORING', 'Підлога', 'Радіусна (фігурна) підрізка ламінату', 'LINEAR_METER', 150.00),
    ('FLOORING', 'Підлога', 'Укладання ламінату на стіну/стелю (на клей)', 'M2', 530.00),
    ('FLOORING', 'Плінтус', 'Монтаж плінтуса поліуретанового (під фарбування)', 'LINEAR_METER', 250.00),
    ('TILING', 'Укладання плитки', 'Укладання плитки на стелю (плюс % до м.кв.)', 'PERCENT', 50.00),
    ('PAINTER', 'Шпалери', 'Поклейка шпалер на стелю', 'M2', 240.00),
    ('PAINTER', 'Штукатурка', 'Ручна штукатурка стелі по маяках', 'M2', 410.00),
    ('PAINTER', 'Шпаклівка', 'Машинне нанесення фінішної шпаклівки', 'M2', 280.00),
    ('PAINTER', 'Штукатурка', 'Штукатурка ніш під радіатори', 'M2', 470.00),
    ('PAINTER', 'Штукатурка', 'Монтаж маяків / розмітка площин стелі', 'M2', 140.00),
    ('PAINTER', 'Декоративна штукатурка', 'Фактурна штукатурка «американка»', 'M2', 460.00),
    ('PAINTER', 'Фарбування', 'Фарбування вікон (дерев''яних рам)', 'M2', 450.00),
    ('PAINTER', 'Фарбування', 'Фарбування плінтусів та лиштви', 'LINEAR_METER', 90.00),
    ('PAINTER', 'Демонтаж', 'Збивання старої штукатурки зі стін', 'M2', 250.00),
    ('PAINTER', 'Демонтаж', 'Збивання старої штукатурки зі стелі', 'M2', 200.00),
    ('PAINTER', 'Демонтаж', 'Очищення старої олійної фарби', 'M2', 250.00),
    ('DRYWALL', 'Каркас і обшивка', 'Перепад рівня стелі з ГКЛ (прямий)', 'LINEAR_METER', 550.00),
    ('DRYWALL', 'Каркас і обшивка', 'Перепад рівня стелі з ГКЛ (радіусний)', 'LINEAR_METER', 670.00),
    ('DRYWALL', 'Каркас і обшивка', 'Обшивка стелі ГКЛ у 2 шари', 'M2', 600.00),
    ('ELECTRICAL', 'Освітлення', 'Установка та підключення світильника для стелі Армстронг', 'PIECE', 300.00),
    ('ELECTRICAL', 'Освітлення', 'Складання та монтаж люстри складної (кришталевої, великої)', 'PIECE', 900.00),
    ('ELECTRICAL', 'Щит', 'Перенесення електрощитка в квартиру', 'PIECE', 2700.00),
    ('ELECTRICAL', 'Підготовка', 'Розробка схеми електропроводки', 'M2', 150.00),
    ('ELECTRICAL', 'Підготовка', 'Пошук прихованої проводки (детектором)', 'PIECE', 1000.00),
    ('ELECTRICAL', 'Освітлення', 'Установка та підключення прожектора', 'PIECE', 400.00),
    ('ELECTRICAL', 'Розетки', 'Установка діммера (світлорегулятора)', 'PIECE', 300.00),
    ('ELECTRICAL', 'Підключення техніки', 'Підключення кухонної витяжки', 'PIECE', 500.00),
    ('ELECTRICAL', 'Розетки', 'Установка силової розетки для електроплити (32/40 А)', 'PIECE', 300.00),
    ('ELECTRICAL', 'Слаботочка', 'Установка електромагнітного (електромеханічного) замка', 'PIECE', 1400.00),
    ('ELECTRICAL', 'Щит', 'Заміна пробок на автоматичні вимикачі', 'PIECE', 550.00),
    ('ELECTRICAL', 'Інше', 'Година роботи електрика', 'HOUR', 450.00),
    ('PLUMBING', 'Каналізація', 'Монтаж каналізаційної насосної станції (КНС)', 'PIECE', 3800.00),
    ('PLUMBING', 'Сантехприлади', 'Установка пісуара (навісного)', 'PIECE', 1250.00),
    ('PLUMBING', 'Сантехприлади', 'Установка проточного водонагрівача', 'PIECE', 1300.00),
    ('PLUMBING', 'Сантехприлади', 'Заміна арматури в бачку унітазу', 'PIECE', 700.00),
    ('PLUMBING', 'Сантехприлади', 'Демонтаж змішувача', 'PIECE', 300.00),
    ('PLUMBING', 'Каналізація', 'Механічне прочищення каналізації', 'LINEAR_METER', 300.00),
    ('PLUMBING', 'Котел', 'Пусконалагодження (запуск) котла', 'PIECE', 1200.00),
    ('PLUMBING', 'Котел', 'Монтаж коаксіального (турбо) димоходу котла', 'PIECE', 900.00),
    ('PLUMBING', 'Опалення', 'Перенесення радіатора опалення', 'PIECE', 2100.00),
    ('PLUMBING', 'Котел', 'Монтаж газової колонки', 'PIECE', 2100.00),
    ('PLUMBING', 'Котел', 'Установка кімнатного термостата / контролера котла', 'PIECE', 730.00),
    ('DEMOLITION', 'Зовнішні роботи', 'Демонтаж покрівлі (металочерепиця, профнастил)', 'M2', 225.00),
    ('DEMOLITION', 'Зовнішні роботи', 'Демонтаж покрівлі з шиферу', 'M2', 150.00),
    ('DEMOLITION', 'Зовнішні роботи', 'Демонтаж тротуарної плитки', 'M2', 170.00),
    ('DEMOLITION', 'Зовнішні роботи', 'Демонтаж бордюру', 'LINEAR_METER', 120.00),
    ('DEMOLITION', 'Зовнішні роботи', 'Демонтаж фасаду (утеплювач, облицювання)', 'M2', 285.00),
    ('DEMOLITION', 'Отвори', 'Вирізання прорізу в бетонній стіні 100–150 мм', 'M2', 1900.00),
    ('DEMOLITION', 'Отвори', 'Вирізання прорізу в бетонній стіні 150–200 мм', 'M2', 2500.00),
    ('BUILDER', 'Фасад', 'Облицювання фасаду сайдингом', 'M2', 420.00),
    ('BUILDER', 'Покрівля', 'Укладання цементно-піщаної черепиці', 'M2', 480.00),
    ('BUILDER', 'Покрівля', 'Монтаж мансардного вікна (у процесі будівництва)', 'PIECE', 4400.00),
    ('BUILDER', 'Покрівля', 'Монтаж мансардного вікна в готову покрівлю', 'PIECE', 5400.00),
    ('BUILDER', 'Покрівля', 'Монтаж снігозатримувачів', 'LINEAR_METER', 250.00),
    ('BUILDER', 'Фундамент', 'В''язка арматури (за тонну)', 'T', 18300.00),
    ('BUILDER', 'Монтажні', 'Монтаж збірно-монолітного перекриття (балки та пустотілі блоки)', 'M2', 720.00),
    ('BUILDER', 'Благоустрій', 'Фігурне укладання тротуарної плитки (візерунки)', 'M2', 720.00);

CREATE TEMP TABLE _key ON COMMIT DROP AS
SELECT DISTINCT n AS name,
       lower(btrim(replace(replace(regexp_replace(n, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) AS k
  FROM (SELECT old_name FROM _ren UNION SELECT new_name FROM _ren UNION SELECT name FROM _unit
        UNION SELECT name FROM _del UNION SELECT survivor FROM _del UNION SELECT name FROM _pos) s(n);

-- -------------------------------------------------------------------------------------------------
-- 0. Preconditions: each row is where we think it is, so nothing below lands on a guess.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE v_bad text;
BEGIN
    -- The ruling was given about these numbers; a moved one would make the notices lie.
    SELECT string_agg(r.trade || ' / ' || r.name, '; ') INTO v_bad FROM _reprice r
     WHERE (SELECT count(*) FROM catalog_templates ct
             WHERE ct.trade = r.trade AND ct.type = 'WORK' AND ct.unit = r.unit
               AND lower(trim(ct.name)) = lower(trim(r.name)) AND ct.suggested_price = r.old_price) <> 1;
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V176: repriced position(s) not found exactly once at the expected old price: %', v_bad;
    END IF;

    SELECT string_agg(x.name, '; ') INTO v_bad FROM (
        SELECT r.trade, r.old_name AS name, r.unit FROM _ren r
        UNION ALL SELECT u.trade, u.name, u.from_unit FROM _unit u
        UNION ALL SELECT d.trade, d.name, d.unit FROM _del d
        UNION ALL SELECT d.trade, d.survivor, d.survivor_unit FROM _del d
        UNION ALL SELECT d.trade, d.name, d.unit FROM _desc d) x
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct
                        WHERE ct.trade = x.trade AND ct.type = 'WORK' AND ct.unit = x.unit
                          AND lower(trim(ct.name)) = lower(trim(x.name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V176: structural edit(s) on a position not in the library: %', v_bad;
    END IF;

    -- A name another trade ships too would be split in two by a one-trade edit.
    SELECT string_agg(x.name, '; ') INTO v_bad FROM (
        SELECT r.trade, r.old_name AS name, r.unit FROM _ren r
        UNION ALL SELECT u.trade, u.name, u.from_unit FROM _unit u
        UNION ALL SELECT d.trade, d.name, d.unit FROM _del d) x
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.trade <> x.trade AND ct.type = 'WORK' AND ct.unit = x.unit
                      AND lower(trim(ct.name)) = lower(trim(x.name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V176: edited position(s) are shipped by another trade too: %', v_bad;
    END IF;

    SELECT string_agg(x.name, '; ') INTO v_bad FROM (
        SELECT r.new_name AS name, r.unit FROM _ren r
        UNION ALL SELECT u.name, u.to_unit FROM _unit u) x
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.type = 'WORK' AND ct.unit = x.unit
                      AND lower(trim(ct.name)) = lower(trim(x.name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V176: a new name or unit is already in the library: %', v_bad;
    END IF;

    SELECT string_agg(p.trade || ' / ' || p.name, '; ') INTO v_bad FROM _pos p JOIN _key kp ON kp.name = p.name
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.trade = p.trade AND ct.type = 'WORK' AND ct.unit = p.unit
                      AND lower(btrim(replace(replace(regexp_replace(ct.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) = kp.k);
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V176: a new position is already in its trade: %', v_bad;
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 1. Prices, and the notices — at most one per master's row, by the trade the row is filed under.
-- -------------------------------------------------------------------------------------------------
UPDATE catalog_templates ct
   SET suggested_price = r.new_price
  FROM _reprice r
 WHERE ct.trade = r.trade AND ct.type = 'WORK' AND ct.unit = r.unit
   AND lower(trim(ct.name)) = lower(trim(r.name));

CREATE TEMP TABLE _notified (user_id uuid, position_name varchar(255)) ON COMMIT DROP;

WITH ins AS (
    INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed,
                                        position_name, old_price, new_price)
    SELECT gen_random_uuid(), ci.owner_id, 'PRICE_DRIFT', 0, 0, r.name, r.old_price, r.new_price
      FROM _reprice r
      JOIN catalog_items ci
        ON lower(trim(ci.name)) = lower(trim(r.name))
       AND ci.type = 'WORK' AND ci.unit = r.unit AND ci.trade = r.trade
     WHERE ci.source = 'LIBRARY' AND ci.default_price = r.old_price
       AND NOT EXISTS (SELECT 1 FROM catalog_update_notices n
                        WHERE n.user_id = ci.owner_id AND n.kind = 'PRICE_DRIFT'
                          AND n.dismissed_at IS NULL
                          AND lower(trim(n.position_name)) = lower(trim(r.name)))
    RETURNING user_id, position_name)
INSERT INTO _notified SELECT user_id, position_name FROM ins;

-- -------------------------------------------------------------------------------------------------
-- 2. Renames (V158's statements).
-- -------------------------------------------------------------------------------------------------
UPDATE catalog_templates ct
   SET name = r.new_name
  FROM _ren r
 WHERE ct.trade = r.trade AND ct.type = 'WORK' AND ct.unit = r.unit
   AND lower(trim(ct.name)) = lower(trim(r.old_name));

UPDATE estimate_template_items i
   SET name = r.new_name
  FROM _ren r, estimate_templates t
 WHERE t.id = i.template_id AND t.owner_id IS NULL AND t.is_default
   AND i.type = 'WORK' AND i.unit = r.unit
   AND lower(trim(i.name)) = lower(trim(r.old_name));

UPDATE catalog_update_notices n
   SET position_name = r.new_name
  FROM _ren r, catalog_items ci
 WHERE n.kind = 'PRICE_DRIFT' AND n.dismissed_at IS NULL
   AND lower(trim(n.position_name)) = lower(trim(r.old_name))
   AND ci.owner_id = n.user_id AND ci.source = 'LIBRARY' AND ci.type = 'WORK' AND ci.unit = r.unit
   AND lower(trim(ci.name)) = lower(trim(r.old_name))
   AND NOT EXISTS (SELECT 1 FROM catalog_items x
                    WHERE x.owner_id = ci.owner_id AND x.type = 'WORK' AND x.unit = r.unit
                      AND lower(trim(x.name)) = lower(trim(r.new_name)));

UPDATE catalog_items ci
   SET name = r.new_name
  FROM _ren r
 WHERE ci.source = 'LIBRARY' AND ci.type = 'WORK' AND ci.unit = r.unit
   AND lower(trim(ci.name)) = lower(trim(r.old_name))
   AND NOT EXISTS (SELECT 1 FROM catalog_items x
                    WHERE x.owner_id = ci.owner_id AND x.type = 'WORK' AND x.unit = r.unit
                      AND lower(trim(x.name)) = lower(trim(r.new_name)));

UPDATE estimate_template_items i
   SET name = r.new_name
  FROM _ren r, estimate_templates t, template_default_override o
 WHERE t.id = i.template_id AND t.owner_id IS NOT NULL
   AND o.forked_template_id = t.id AND o.user_id = t.owner_id
   AND i.type = 'WORK' AND i.unit = r.unit
   AND lower(trim(i.name)) = lower(trim(r.old_name))
   AND EXISTS (SELECT 1 FROM catalog_items ci
                WHERE ci.owner_id = t.owner_id AND ci.type = 'WORK' AND ci.unit = r.unit
                  AND lower(trim(ci.name)) = lower(trim(r.new_name)))
   AND NOT EXISTS (SELECT 1 FROM estimate_template_items x
                    WHERE x.template_id = i.template_id AND x.unit = r.unit
                      AND lower(trim(x.name)) = lower(trim(r.new_name)));

INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id, qty_per_unit,
                           waste_percent, sort_order, basis, default_param, baseline_param)
SELECT gen_random_uuid(), n.owner_id, n.trade, kn.k, n.unit, n.material_id, n.qty_per_unit,
       n.waste_percent, n.sort_order, n.basis, n.default_param, n.baseline_param
  FROM _ren r
  JOIN _key ko ON ko.name = r.old_name
  JOIN _key kn ON kn.name = r.new_name
  JOIN material_norm n ON n.unit = r.unit AND n.name_key = ko.k
 WHERE NOT EXISTS (SELECT 1 FROM material_norm x
                    WHERE x.owner_id IS NOT DISTINCT FROM n.owner_id
                      AND x.trade IS NOT DISTINCT FROM n.trade
                      AND x.unit = n.unit
                      AND x.material_id IS NOT DISTINCT FROM n.material_id
                      AND x.name_key = kn.k);

INSERT INTO catalog_renamed_position (old_name_key, new_name_key, unit, renamed_in)
SELECT ko.k, kn.k, r.unit, 'V176'
  FROM _ren r JOIN _key ko ON ko.name = r.old_name JOIN _key kn ON kn.name = r.new_name
ON CONFLICT DO NOTHING;

-- -------------------------------------------------------------------------------------------------
-- 3. Unit changes — the same rows a rename moves (see the header).
-- -------------------------------------------------------------------------------------------------
UPDATE catalog_templates ct
   SET unit = u.to_unit
  FROM _unit u
 WHERE ct.trade = u.trade AND ct.type = 'WORK' AND ct.unit = u.from_unit
   AND lower(trim(ct.name)) = lower(trim(u.name));

UPDATE estimate_template_items i
   SET unit = u.to_unit
  FROM _unit u, estimate_templates t
 WHERE t.id = i.template_id AND t.owner_id IS NULL AND t.is_default
   AND i.type = 'WORK' AND i.unit = u.from_unit
   AND lower(trim(i.name)) = lower(trim(u.name));

UPDATE catalog_items ci
   SET unit = u.to_unit
  FROM _unit u
 WHERE ci.source = 'LIBRARY' AND ci.type = 'WORK' AND ci.unit = u.from_unit
   AND lower(trim(ci.name)) = lower(trim(u.name))
   AND NOT EXISTS (SELECT 1 FROM catalog_items x
                    WHERE x.owner_id = ci.owner_id AND x.type = 'WORK' AND x.unit = u.to_unit
                      AND lower(trim(x.name)) = lower(trim(u.name)));

UPDATE estimate_template_items i
   SET unit = u.to_unit
  FROM _unit u, estimate_templates t, template_default_override o
 WHERE t.id = i.template_id AND t.owner_id IS NOT NULL
   AND o.forked_template_id = t.id AND o.user_id = t.owner_id
   AND i.type = 'WORK' AND i.unit = u.from_unit
   AND lower(trim(i.name)) = lower(trim(u.name))
   AND EXISTS (SELECT 1 FROM catalog_items ci
                WHERE ci.owner_id = t.owner_id AND ci.type = 'WORK' AND ci.unit = u.to_unit
                  AND lower(trim(ci.name)) = lower(trim(u.name)))
   AND NOT EXISTS (SELECT 1 FROM estimate_template_items x
                    WHERE x.template_id = i.template_id AND x.unit = u.to_unit
                      AND lower(trim(x.name)) = lower(trim(u.name)));

-- Owner ruling: one strip of masking tape along the line, 1,0 m per м.п.
INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id, qty_per_unit,
                           waste_percent, sort_order, basis, default_param, baseline_param)
SELECT gen_random_uuid(), NULL, n.trade, n.name_key, 'LINEAR_METER', n.material_id, 1.0,
       n.waste_percent, n.sort_order, n.basis, n.default_param, n.baseline_param
  FROM material_norm n
  JOIN material m ON m.id = n.material_id AND m.code = 'TAPE_MASKING'
 WHERE n.owner_id IS NULL AND n.unit = 'M2'
   AND n.name_key = 'відведення лінії фарби довкола вікон/дверей';

INSERT INTO catalog_renamed_position (old_name_key, new_name_key, unit, renamed_in)
SELECT k.k, k.k, u.from_unit, 'V176' FROM _unit u JOIN _key k ON k.name = u.name
ON CONFLICT DO NOTHING;

-- -------------------------------------------------------------------------------------------------
-- 4. Descriptions: the library row, and a master's LIBRARY copy only while it still says what the
--    library said (or nothing) — a description he wrote is his.
-- -------------------------------------------------------------------------------------------------
CREATE TEMP TABLE _desc_old ON COMMIT DROP AS
SELECT ct.trade, ct.name, ct.unit, ct.description AS old_description, d.description
  FROM _desc d
  JOIN catalog_templates ct
    ON ct.trade = d.trade AND ct.type = 'WORK' AND ct.unit = d.unit
   AND lower(trim(ct.name)) = lower(trim(d.name));

UPDATE catalog_templates ct
   SET description = d.description
  FROM _desc_old d
 WHERE ct.trade = d.trade AND ct.type = 'WORK' AND ct.unit = d.unit
   AND lower(trim(ct.name)) = lower(trim(d.name));

UPDATE catalog_items ci
   SET description = d.description
  FROM _desc_old d
 WHERE ci.source = 'LIBRARY' AND ci.type = 'WORK' AND ci.unit = d.unit
   AND lower(trim(ci.name)) = lower(trim(d.name))
   AND (ci.description IS NULL OR btrim(ci.description) = ''
        OR ci.description = d.old_description);

-- -------------------------------------------------------------------------------------------------
-- 5. Duplicates in another unit leave the LIBRARY only; the loser's norms stay on its key.
-- -------------------------------------------------------------------------------------------------
INSERT INTO catalog_renamed_position (old_name_key, new_name_key, unit, renamed_in)
SELECT kl.k, ks.k, d.unit, 'V176'
  FROM _del d JOIN _key kl ON kl.name = d.name JOIN _key ks ON ks.name = d.survivor
ON CONFLICT DO NOTHING;

DELETE FROM catalog_templates ct
 USING _del d
 WHERE ct.trade = d.trade AND ct.type = 'WORK' AND ct.unit = d.unit
   AND lower(trim(ct.name)) = lower(trim(d.name));

-- -------------------------------------------------------------------------------------------------
-- 6. New positions under ONE new version; nothing pushed, `last_synced_catalog_version` untouched.
-- -------------------------------------------------------------------------------------------------
INSERT INTO catalog_templates (id, trade, category, name, type, unit, suggested_price, added_in_version)
SELECT gen_random_uuid(), p.trade, p.category, p.name, 'WORK', p.unit, p.price, v.version
  FROM _pos p
 CROSS JOIN (SELECT COALESCE(MAX(added_in_version), 0) + 1 AS version FROM catalog_templates) v;

-- -------------------------------------------------------------------------------------------------
-- 7. RE-RANK, exactly as V118 PART 3/4 did (V120's rule: any migration that INSERTs a template).
-- -------------------------------------------------------------------------------------------------
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               ORDER BY
                   array_position(ARRAY['ELECTRICAL','PLUMBING','TILING','BUILDER','PAINTER',
                                        'DRYWALL','FLOORING','DEMOLITION','METAL','GENERAL','OTHER'],
                                  trade::text),
                   CASE
                       WHEN trade = 'DRYWALL' THEN
                           CASE category
                               WHEN 'Підготовка та захист'       THEN 1
                               WHEN 'Каркас і обшивка'           THEN 2
                               WHEN 'Звукоізоляція та утеплення' THEN 3
                               WHEN 'Оздоблення під фарбування'  THEN 4
                               WHEN 'Надбавки'                   THEN 8
                               ELSE 5
                           END
                       WHEN category = 'Демонтаж' OR category LIKE 'Демонтаж %' THEN 1
                       WHEN category IN ('Підготовка', 'Підготовка та захист',
                                         'Підготовчі роботи', 'Земляні')        THEN 2
                       WHEN category IN ('Надбавки', 'Організаційні послуги', 'Сміття') THEN 8
                       WHEN category = 'Інше'                                   THEN 9
                       WHEN category IS NULL                                    THEN 10
                       ELSE 5
                   END,
                   category,
                   lower(name),
                   id) AS position
    FROM catalog_templates
)
UPDATE catalog_templates ct SET sort_order = ranked.position
FROM ranked WHERE ct.id = ranked.id;

WITH item_key AS (
    SELECT ci.id,
           ci.owner_id,
           ci.name,
           COALESCE(
               (SELECT ct.sort_order FROM catalog_templates ct
                 WHERE ct.trade = ci.trade
                   AND lower(trim(ct.name)) = lower(trim(ci.name))
                   AND ct.type = ci.type AND ct.unit = ci.unit
                 LIMIT 1),
               (SELECT min(ct.sort_order) FROM catalog_templates ct
                 WHERE ct.trade = ci.trade
                   AND ct.category IS NOT DISTINCT FROM ci.category),
               1000000) AS rank_key
    FROM catalog_items ci
),
ordered AS (
    SELECT id, ROW_NUMBER() OVER (
               PARTITION BY owner_id
               ORDER BY rank_key, lower(name), id) - 1 AS position
    FROM item_key
)
UPDATE catalog_items ci SET sort_order = ordered.position
FROM ordered WHERE ci.id = ordered.id;

-- -------------------------------------------------------------------------------------------------

-- -------------------------------------------------------------------------------------------------
-- 8. Self-checks, each about THIS migration's own work (review B-50).
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    v_bad     text;
    v_n       int;
    v_version int := (SELECT MAX(added_in_version) FROM catalog_templates);
BEGIN
    -- Each price landed, under the name and unit the row carries now.
    SELECT string_agg(r.trade || ' / ' || r.name, '; ') INTO v_bad FROM _reprice r
     WHERE NOT EXISTS (
            SELECT 1 FROM catalog_templates ct
             WHERE ct.trade = r.trade AND ct.type = 'WORK' AND ct.suggested_price = r.new_price
               AND lower(trim(ct.name)) = lower(trim(COALESCE(
                       (SELECT x.new_name FROM _ren x WHERE x.trade = r.trade AND x.unit = r.unit
                           AND lower(trim(x.old_name)) = lower(trim(r.name))), r.name)))
               AND ct.unit = COALESCE(
                       (SELECT x.to_unit FROM _unit x WHERE x.trade = r.trade AND x.from_unit = r.unit
                           AND lower(trim(x.name)) = lower(trim(r.name))), r.unit));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V176: price(s) did not land: %', v_bad;
    END IF;

    SELECT string_agg(DISTINCT d.position_name, '; ') INTO v_bad FROM (
        SELECT n.user_id, lower(trim(n.position_name)) AS position_name FROM _notified n
         GROUP BY 1, 2 HAVING count(*) > 1) d;
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V176: a master was handed two notices for one position: %', v_bad;
    END IF;

    SELECT string_agg(DISTINCT i.name, '; ') INTO v_bad
      FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
     WHERE t.owner_id IS NULL AND t.is_default
       AND (EXISTS (SELECT 1 FROM _ren r WHERE i.unit = r.unit AND lower(trim(i.name)) = lower(trim(r.old_name)))
            OR EXISTS (SELECT 1 FROM _unit u WHERE i.unit = u.from_unit AND lower(trim(i.name)) = lower(trim(u.name))));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V176: default bundle lines still on an old name or unit: %', v_bad;
    END IF;

    -- Every default bundle line this migration touched still names a library position.
    SELECT string_agg(DISTINCT i.name, '; ') INTO v_bad
      FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
     WHERE t.owner_id IS NULL AND t.is_default
       AND (i.name IN (SELECT new_name FROM _ren) OR i.name IN (SELECT name FROM _unit))
       AND NOT EXISTS (SELECT 1 FROM catalog_templates ct WHERE ct.type = i.type AND ct.unit = i.unit
                         AND lower(trim(ct.name)) = lower(trim(i.name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V176: a bundle line names no library position: %', v_bad;
    END IF;

    SELECT string_agg(DISTINCT ci.name, '; ') INTO v_bad
      FROM catalog_items ci
     WHERE ci.source = 'LIBRARY' AND ci.type = 'WORK'
       AND (EXISTS (SELECT 1 FROM _ren r WHERE ci.unit = r.unit AND lower(trim(ci.name)) = lower(trim(r.old_name))
                      AND NOT EXISTS (SELECT 1 FROM catalog_items x WHERE x.owner_id = ci.owner_id AND x.type = 'WORK'
                                        AND x.unit = r.unit AND lower(trim(x.name)) = lower(trim(r.new_name))))
            OR EXISTS (SELECT 1 FROM _unit u WHERE ci.unit = u.from_unit AND lower(trim(ci.name)) = lower(trim(u.name))
                      AND NOT EXISTS (SELECT 1 FROM catalog_items x WHERE x.owner_id = ci.owner_id AND x.type = 'WORK'
                                        AND x.unit = u.to_unit AND lower(trim(x.name)) = lower(trim(u.name)))));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V176: a master''s library copy was left on an old name or unit: %', v_bad;
    END IF;

    SELECT string_agg(DISTINCT r.new_name, '; ') INTO v_bad
      FROM _ren r JOIN _key ko ON ko.name = r.old_name JOIN _key kn ON kn.name = r.new_name
      JOIN material_norm n ON n.owner_id IS NULL AND n.unit = r.unit AND n.name_key = ko.k
     WHERE NOT EXISTS (SELECT 1 FROM material_norm x WHERE x.owner_id IS NULL AND x.unit = r.unit
                         AND x.material_id IS NOT DISTINCT FROM n.material_id AND x.name_key = kn.k);
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V176: renamed position(s) lost a norm: %', v_bad;
    END IF;

    -- A unit change answers in the new unit whenever it answered in the old one.
    SELECT string_agg(DISTINCT u.name, '; ') INTO v_bad
      FROM _unit u JOIN _key k ON k.name = u.name
     WHERE EXISTS (SELECT 1 FROM material_norm n WHERE n.owner_id IS NULL AND n.unit = u.from_unit AND n.name_key = k.k)
       AND NOT EXISTS (SELECT 1 FROM material_norm n WHERE n.owner_id IS NULL AND n.unit = u.to_unit AND n.name_key = k.k);
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V176: a unit change lost its norm: %', v_bad;
    END IF;

    SELECT string_agg(d.name, '; ') INTO v_bad FROM _del d
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct WHERE ct.trade = d.trade AND ct.unit = d.unit
                     AND lower(trim(ct.name)) = lower(trim(d.name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V176: a removed duplicate is still in the library: %', v_bad;
    END IF;

    SELECT string_agg(d.name, '; ') INTO v_bad FROM _desc d
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct
                        WHERE ct.trade = d.trade AND ct.unit = d.unit
                          AND lower(trim(ct.name)) = lower(trim(d.name))
                          AND ct.description = d.description);
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V176: description(s) did not land: %', v_bad;
    END IF;

    SELECT count(*) INTO v_n FROM catalog_templates ct JOIN _pos p
        ON ct.trade = p.trade AND ct.name = p.name AND ct.unit = p.unit AND ct.added_in_version = v_version;
    IF v_n <> (SELECT count(*) FROM _pos) THEN
        RAISE EXCEPTION 'V176: % of % new position(s) landed under version %', v_n, (SELECT count(*) FROM _pos), v_version;
    END IF;

    RAISE NOTICE 'V176: % prices, % price notice(s), % new position(s) under catalog version %',
        (SELECT count(*) FROM _reprice), (SELECT count(*) FROM _notified), v_n, v_version;
END $$;
