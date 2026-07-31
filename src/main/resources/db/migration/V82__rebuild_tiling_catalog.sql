-- The tiling default catalog, rebuilt from scratch.
--
-- Why replace rather than extend: the old TILING set was 73 works accumulated across V13, V27,
-- V31, V36 and the V50 "tetris" import, then repaired by V70-V73. It described tiling the way
-- someone guesses at it — a handful of broad positions, several of them duplicates under
-- different wording. Real tilers price the job in far more detail than that, and a master who
-- cannot find their work in the list concludes the list is not for them. Several told us they
-- had not realised positions were editable at all; a catalog that already says what they do is
-- the fix for that, not a better empty state.
--
-- Where it comes from: the public price list at https://plytochnyk.ua/price/ (149 positions,
-- read 2026-07), plus 8 of our own positions that had no equivalent there, plus 10 found by
-- research into what tilers routinely bill for and no published list carries — carrying,
-- rubbish removal, covering with film, the warranty callout. 167 works in 11 categories.
--
-- Prices: 18 positions ship at 0. That is deliberate and it is the honest value — these are
-- jobs quoted per object («договірна»), and a made-up number is worse than a blank the master
-- fills in once. V27 relaxed both price CHECKs to >= 0 for exactly this.
--
-- Materials are absent because V81 removed them from every trade. This catalog is works only.
--
-- ============ WHAT IS NEVER TOUCHED ============================================
--   1. catalog_items — no master's catalog is modified here. Default templates are reference
--      data copied BY VALUE; rewriting them changes what FUTURE copies contain and nothing
--      else. Bringing this to masters who already registered is V83's job, and it is a
--      separate migration precisely so the two decisions can be read apart.
--   2. estimate_items — snapshots with no FK. Every estimate ever written keeps its figures.
-- ==============================================================================
--
-- added_in_version = 9 (V70 used 8). So "Додати нові позиції" offers these to every existing
-- tiler, and CatalogTemplateService.currentVersion() advances for everyone.
--
-- Default estimate templates still name the OLD positions at this point; V84 rewrites them in
-- the same deploy. An orphaned bundle line is a warning, never a startup failure — see
-- CatalogCleanupOnLegacyDataIntegrationTest.

-- Carried over to V83, which cleans the copies masters already hold. To do that safely it has to
-- tell "the master repriced this row" from "this is our leftover, untouched" — and that is a
-- comparison against the price WE shipped, which the DELETE below is about to destroy. So the
-- baseline is captured here, while it still exists, and V83 drops the table when it is done.
CREATE TABLE tiling_v9_baseline AS
SELECT lower(trim(name)) AS name_key, type, unit, suggested_price
FROM catalog_templates
WHERE trade = 'TILING';

DELETE FROM catalog_templates WHERE trade = 'TILING';

INSERT INTO catalog_templates (id, trade, name, type, unit, suggested_price, category, added_in_version) VALUES
-- Укладання плитки (24)
(gen_random_uuid(), 'TILING', 'Укладання багатокутників: гексагон, стільники'                            , 'WORK', 'M2'          ,   952.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання ексклюзивної дорогої плитки'                                    , 'WORK', 'M2'          ,  1470.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання керамічного паркету, дрібної дошки'                             , 'WORK', 'M2'          ,   866.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 1000х1000'                                               , 'WORK', 'M2'          ,   918.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 100х100'                                                 , 'WORK', 'M2'          ,   911.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 200х200'                                                 , 'WORK', 'M2'          ,   746.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 300х300'                                                 , 'WORK', 'M2'          ,   641.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 300х600'                                                 , 'WORK', 'M2'          ,   594.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 300х900'                                                 , 'WORK', 'M2'          ,   625.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 600х1200'                                                , 'WORK', 'M2'          ,   732.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 600х600'                                                 , 'WORK', 'M2'          ,   631.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 800х1600'                                                , 'WORK', 'M2'          ,  1077.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 800х800'                                                 , 'WORK', 'M2'          ,   779.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки без шва'                                                 , 'WORK', 'M2'          ,  1241.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки дошка до 1200 мм'                                        , 'WORK', 'M2'          ,   756.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки дошка до 1800 мм'                                        , 'WORK', 'M2'          ,   968.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки дошка до 900 мм'                                         , 'WORK', 'M2'          ,   730.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки кабанчик, «цегла»'                                       , 'WORK', 'M2'          ,   998.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки на відкос (коефіцієнт від м.кв.)'                        , 'WORK', 'PERCENT'     ,    88.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки по діагоналі ( плюс % до м.кв.)'                         , 'WORK', 'PERCENT'     ,    33.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки ялинкою (плюс % до м.кв.)'                               , 'WORK', 'PERCENT'     ,     0.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки на шар клею більше 1 см'                                 , 'WORK', 'M2'          ,     0.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання декоративної плитки під «цеглу» або «камінь» (гіпсова, бетонна)', 'WORK', 'M2'          ,     0.00, 'Укладання плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки поверх теплої підлоги (плюс % до м.кв.)'                 , 'WORK', 'PERCENT'     ,     0.00, 'Укладання плитки', 9),
-- Укладання широкоформатної плитки (12)
(gen_random_uuid(), 'TILING', 'Облицювання меблів великим форматом ( плюс % до м.кв.)'                   , 'WORK', 'PERCENT'     ,    50.00, 'Укладання широкоформатної плитки', 9),
(gen_random_uuid(), 'TILING', 'Облицювання стелі великим форматом ( плюс % до м.кв.)'                    , 'WORK', 'PERCENT'     ,    76.00, 'Укладання широкоформатної плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання великоформатної плитки товщиною 15-20 мм ( плюс % до м.кв.)'    , 'WORK', 'PERCENT'     ,    45.00, 'Укладання широкоформатної плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 1000х2000 мм'                                            , 'WORK', 'M2'          ,  1617.00, 'Укладання широкоформатної плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 1000х3000 мм'                                            , 'WORK', 'M2'          ,  2188.00, 'Укладання широкоформатної плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 1200х1200 мм'                                            , 'WORK', 'M2'          ,  1130.00, 'Укладання широкоформатної плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 1200х2400 мм'                                            , 'WORK', 'M2'          ,  1837.00, 'Укладання широкоформатної плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 1200х3200 мм'                                            , 'WORK', 'M2'          ,  2383.00, 'Укладання широкоформатної плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 1500х3000 мм'                                            , 'WORK', 'M2'          ,  2644.00, 'Укладання широкоформатної плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 1600х1600 мм'                                            , 'WORK', 'M2'          ,  1543.00, 'Укладання широкоформатної плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки 1600х3200 мм'                                            , 'WORK', 'M2'          ,  2742.00, 'Укладання широкоформатної плитки', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки більше 3200 мм'                                          , 'WORK', 'M2'          ,  3269.00, 'Укладання широкоформатної плитки', 9),
-- Укладання мозаїки та басейни (10)
(gen_random_uuid(), 'TILING', 'Виготовлення конструкцій з вологостійкої панелі'                          , 'WORK', 'M2'          ,  2387.00, 'Укладання мозаїки та басейни', 9),
(gen_random_uuid(), 'TILING', 'Виготовлення та вкладання смальти'                                        , 'WORK', 'M2'          ,  4025.00, 'Укладання мозаїки та басейни', 9),
(gen_random_uuid(), 'TILING', 'Вкладання копінгового каменю'                                             , 'WORK', 'LINEAR_METER',  1260.00, 'Укладання мозаїки та басейни', 9),
(gen_random_uuid(), 'TILING', 'Гідроізоляція басейну'                                                    , 'WORK', 'M2'          ,   360.00, 'Укладання мозаїки та басейни', 9),
(gen_random_uuid(), 'TILING', 'Комплексне будівництво басейну'                                           , 'WORK', 'PIECE'       , 145000.00, 'Укладання мозаїки та басейни', 9),
(gen_random_uuid(), 'TILING', 'Набір малюнка з мозаїки'                                                  , 'WORK', 'M2'          ,  3637.00, 'Укладання мозаїки та басейни', 9),
(gen_random_uuid(), 'TILING', 'Облицювання басейнів'                                                     , 'WORK', 'M2'          ,  1204.00, 'Укладання мозаїки та басейни', 9),
(gen_random_uuid(), 'TILING', 'Облицювання радіусних поверхонь мозаїкою'                                 , 'WORK', 'M2'          ,  1859.00, 'Укладання мозаїки та басейни', 9),
(gen_random_uuid(), 'TILING', 'Укладання мозаїки'                                                        , 'WORK', 'M2'          ,  1115.00, 'Укладання мозаїки та басейни', 9),
(gen_random_uuid(), 'TILING', 'Штукатурка, стяжка басейну'                                               , 'WORK', 'M2'          ,   440.00, 'Укладання мозаїки та басейни', 9),
-- Укладання натурального каменю (11)
(gen_random_uuid(), 'TILING', 'Виготовлення стільниць з каменю'                                          , 'WORK', 'LINEAR_METER',  9875.00, 'Укладання натурального каменю', 9),
(gen_random_uuid(), 'TILING', 'Викладення узорів з каменю'                                               , 'WORK', 'M2'          ,  4900.00, 'Укладання натурального каменю', 9),
(gen_random_uuid(), 'TILING', 'Встановлення виробів з каменю'                                            , 'WORK', 'PIECE'       ,  4200.00, 'Укладання натурального каменю', 9),
(gen_random_uuid(), 'TILING', 'Облицювання каменем відкосів, підвіконь'                                  , 'WORK', 'LINEAR_METER',  1850.00, 'Укладання натурального каменю', 9),
(gen_random_uuid(), 'TILING', 'Облицювання каменем сходів'                                               , 'WORK', 'LINEAR_METER',  1779.00, 'Укладання натурального каменю', 9),
(gen_random_uuid(), 'TILING', 'Обробка пропиткою, лаком'                                                 , 'WORK', 'M2'          ,   193.00, 'Укладання натурального каменю', 9),
(gen_random_uuid(), 'TILING', 'Полірування каменю'                                                       , 'WORK', 'M2'          ,  1050.00, 'Укладання натурального каменю', 9),
(gen_random_uuid(), 'TILING', 'Укладання "дикого каменю" піщаник, сланець'                               , 'WORK', 'M2'          ,   971.00, 'Укладання натурального каменю', 9),
(gen_random_uuid(), 'TILING', 'Укладання "соломки" з каменю'                                             , 'WORK', 'M2'          ,  1616.00, 'Укладання натурального каменю', 9),
(gen_random_uuid(), 'TILING', 'Укладання великих слябів з каменю'                                        , 'WORK', 'M2'          ,  2740.00, 'Укладання натурального каменю', 9),
(gen_random_uuid(), 'TILING', 'Укладання натурального каменю граніту, мармуру'                           , 'WORK', 'M2'          ,  1333.00, 'Укладання натурального каменю', 9),
-- Оброблення плитки та вироби (32)
(gen_random_uuid(), 'TILING', 'Виготовлення душового піддону без борту'                                  , 'WORK', 'PIECE'       ,  9958.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Виготовлення душового піддону врівень з підлогою'                         , 'WORK', 'PIECE'       , 12083.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Виготовлення душового піддону з бортом'                                   , 'WORK', 'PIECE'       , 12371.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Виготовлення душового піддону з нішею для підсвітки'                      , 'WORK', 'PIECE'       , 26308.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Виготовлення кнопки з плитки'                                             , 'WORK', 'PIECE'       , 13316.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Виготовлення кухонної стільниці з керамограніту'                          , 'WORK', 'LINEAR_METER', 12600.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Виготовлення панелі під ванну з підступом (ніша)'                         , 'WORK', 'PIECE'       ,  5982.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Виготовлення панелі під ванну прямої'                                     , 'WORK', 'PIECE'       ,  4251.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Виготовлення полиці з плитки'                                             , 'WORK', 'PIECE'       ,  6803.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Виготовлення прихованої розетки/вимикача з плитки'                        , 'WORK', 'PIECE'       ,  4313.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Виготовлення умивальника з плитки'                                        , 'WORK', 'PIECE'       , 24200.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Викладання узорів після гідроабразивної порізки'                          , 'WORK', 'M2'          ,  2046.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Вирівнювання плитки під час збору кута 90°'                               , 'WORK', 'LINEAR_METER',   270.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Виріз в плитці Г-образний'                                                , 'WORK', 'PIECE'       ,   447.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Виріз отворів більше 70 мм'                                               , 'WORK', 'PIECE'       ,   283.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Виріз отворів до 70 мм'                                                   , 'WORK', 'PIECE'       ,   196.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Виріз отвору в плитці великого формату'                                   , 'WORK', 'PIECE'       ,   520.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Влаштування люка ревізії на магнітах'                                     , 'WORK', 'PIECE'       ,  1927.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Влаштування люка ревізії на магнітах з полукруглими кутами'               , 'WORK', 'PIECE'       ,  3488.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Встановлення прихованого вентилятора з плитки (з порізкою)'               , 'WORK', 'PIECE'       ,  4550.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Встановлення профілю для плитки'                                          , 'WORK', 'LINEAR_METER',   230.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Встановлення фризу, плінтуса'                                             , 'WORK', 'LINEAR_METER',   397.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Заусовка під 45° кута широкоформатної плитки'                             , 'WORK', 'LINEAR_METER',   620.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Порізка широкоформатної плитки'                                           , 'WORK', 'LINEAR_METER',   438.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Посилення кутів в підлоговій плитці'                                      , 'WORK', 'PIECE'       ,   394.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Різ плітки під 45° заусовка'                                              , 'WORK', 'LINEAR_METER',   367.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Різ плітки під 90° (чорновий різ)'                                        , 'WORK', 'LINEAR_METER',   111.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Різ плітки під 90° зі шліфуванням (чистовий різ)'                         , 'WORK', 'LINEAR_METER',   246.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Різ плітки радіальний, фігурний'                                          , 'WORK', 'LINEAR_METER',   711.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Сортування плитки та підбір малюнка'                                      , 'WORK', 'M2'          ,   247.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Фрезерування плитки під плінтус, сходи'                                   , 'WORK', 'LINEAR_METER',   517.00, 'Оброблення плитки та вироби', 9),
(gen_random_uuid(), 'TILING', 'Виготовлення та монтаж кріплення для раковини (зварювання, фарбування)'   , 'WORK', 'PIECE'       ,     0.00, 'Оброблення плитки та вироби', 9),
-- Затирання швів (12)
(gen_random_uuid(), 'TILING', 'Заповнення товстого шва напівсухою сумішшю'                               , 'WORK', 'M2'          ,   229.00, 'Затирання швів', 9),
(gen_random_uuid(), 'TILING', 'Заповнення шва з будівельного пістолету'                                  , 'WORK', 'M2'          ,   223.00, 'Затирання швів', 9),
(gen_random_uuid(), 'TILING', 'Заповнення швів герметиком'                                               , 'WORK', 'LINEAR_METER',   130.00, 'Затирання швів', 9),
(gen_random_uuid(), 'TILING', 'Заповнення швів епоксидною сумішшю'                                       , 'WORK', 'M2'          ,   241.00, 'Затирання швів', 9),
(gen_random_uuid(), 'TILING', 'Заповнення швів епоксидною сумішшю в один рівень з плиткою'               , 'WORK', 'LINEAR_METER',   360.00, 'Затирання швів', 9),
(gen_random_uuid(), 'TILING', 'Заповнення швів епоксидною сумішшю на відкосах'                           , 'WORK', 'LINEAR_METER',   260.00, 'Затирання швів', 9),
(gen_random_uuid(), 'TILING', 'Заповнення швів цементною сумішшю'                                        , 'WORK', 'M2'          ,   116.00, 'Затирання швів', 9),
(gen_random_uuid(), 'TILING', 'Заповнення швів цементною сумішшю з латексом'                             , 'WORK', 'M2'          ,   218.00, 'Затирання швів', 9),
(gen_random_uuid(), 'TILING', 'Захисне укриття плитки листами ДВП'                                       , 'WORK', 'M2'          ,    70.00, 'Затирання швів', 9),
(gen_random_uuid(), 'TILING', 'Обробка захисною сумішшю, гідрофобізатором'                               , 'WORK', 'M2'          ,   131.00, 'Затирання швів', 9),
(gen_random_uuid(), 'TILING', 'Формування кута з епоксидної суміші'                                      , 'WORK', 'M2'          ,   287.00, 'Затирання швів', 9),
(gen_random_uuid(), 'TILING', 'Затирання швів у декоративній плитці, мозаїці'                            , 'WORK', 'M2'          ,     0.00, 'Затирання швів', 9),
-- Організаційні послуги (13)
(gen_random_uuid(), 'TILING', 'Виїзд для прорахунку вартості робіт та матеріалів'                        , 'WORK', 'PIECE'       ,  1185.00, 'Організаційні послуги', 9),
(gen_random_uuid(), 'TILING', 'Виїзд майстра в магазин для підбору плитки'                               , 'WORK', 'PIECE'       ,  1296.00, 'Організаційні послуги', 9),
(gen_random_uuid(), 'TILING', 'Виїзд спеціаліста для консультації'                                       , 'WORK', 'PIECE'       ,  1109.00, 'Організаційні послуги', 9),
(gen_random_uuid(), 'TILING', 'Витратні матеріали'                                                       , 'WORK', 'PERCENT'     ,    13.00, 'Організаційні послуги', 9),
(gen_random_uuid(), 'TILING', 'Підняття матеріалу по сходах'                                             , 'WORK', 'T'           ,  1642.00, 'Організаційні послуги', 9),
(gen_random_uuid(), 'TILING', 'Підняття широкоформатної плитки по сходах'                                , 'WORK', 'FLOOR'       ,   564.00, 'Організаційні послуги', 9),
(gen_random_uuid(), 'TILING', 'Розвантаження матеріалу'                                                  , 'WORK', 'T'           ,  1271.00, 'Організаційні послуги', 9),
(gen_random_uuid(), 'TILING', 'Транспортні витрати за містом'                                            , 'WORK', 'KM'          ,    21.00, 'Організаційні послуги', 9),
(gen_random_uuid(), 'TILING', 'Винесення та вивезення будівельного сміття'                               , 'WORK', 'M3'          ,     0.00, 'Організаційні послуги', 9),
(gen_random_uuid(), 'TILING', 'Прибирання приміщення після робіт'                                        , 'WORK', 'M2'          ,     0.00, 'Організаційні послуги', 9),
(gen_random_uuid(), 'TILING', 'Укриття плівкою підлоги, дверей, сантехніки'                              , 'WORK', 'M2'          ,     0.00, 'Організаційні послуги', 9),
(gen_random_uuid(), 'TILING', 'Замір приміщення та схема розкладки плитки'                               , 'WORK', 'PIECE'       ,     0.00, 'Організаційні послуги', 9),
(gen_random_uuid(), 'TILING', 'Гарантійний повторний виїзд'                                              , 'WORK', 'PIECE'       ,     0.00, 'Організаційні послуги', 9),
-- Підготовчі роботи (28)
(gen_random_uuid(), 'TILING', 'Армування кутів гідроізоляційної стрічкою'                                , 'WORK', 'LINEAR_METER',   119.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Виготовлення конструкціїї під інсталяцію з ГКЛ в 2 шари'                  , 'WORK', 'PIECE'       ,  2466.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Вирівнювання поверхні шаром клею'                                         , 'WORK', 'M2'          ,   209.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Влаштування наливної підлоги'                                             , 'WORK', 'M2'          ,   255.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Встановлення гідроізоляційної мембрани'                                   , 'WORK', 'M2'          ,   255.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Встановлення мембрани для теплої підлоги'                                 , 'WORK', 'M2'          ,   267.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Встановлення металевого люка скритого монтажу'                            , 'WORK', 'PIECE'       ,  2036.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Ґрунтівка поверхні'                                                       , 'WORK', 'M2'          ,    35.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Демонтаж плитки'                                                          , 'WORK', 'M2'          ,   224.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Демонтаж фарби'                                                           , 'WORK', 'M2'          ,   300.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Демонтаж штукатурки, стяжки'                                              , 'WORK', 'M2'          ,   217.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Закладення штроб'                                                         , 'WORK', 'LINEAR_METER',   113.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Монтаж фальшстіни ГКЛ в 1 шар'                                            , 'WORK', 'M2'          ,   617.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Нанесення двокомпонентної гідроізоляції'                                  , 'WORK', 'M2'          ,   273.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Нанесення однокомпонентної гідроізоляції'                                 , 'WORK', 'M2'          ,   174.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Очищення, обезпилювання'                                                  , 'WORK', 'M2'          ,    41.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Пояс клею під натяжну стелю'                                              , 'WORK', 'LINEAR_METER',   186.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Стяжка маякова цементна'                                                  , 'WORK', 'M2'          ,   342.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Шліфування видалення слабкого шару, молочка'                              , 'WORK', 'M2'          ,   161.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Шліфування стяжки, вирівнювання до 5 мм'                                  , 'WORK', 'M2'          ,   392.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Штукатурка маякова цементна'                                              , 'WORK', 'M2'          ,   349.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Облицювання плиткою короба'                                               , 'WORK', 'M2'          ,     0.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Гідроізоляція плівкова'                                                   , 'WORK', 'M2'          ,     0.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Гідроізоляція сухою сумішшю'                                              , 'WORK', 'M2'          ,     0.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Монтаж трапу та формування ухилу до нього'                                , 'WORK', 'PIECE'       ,     0.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Монтаж лінійного душового каналу'                                         , 'WORK', 'PIECE'       ,     0.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Поклейка гідроізоляційних муфт на водорозетки'                            , 'WORK', 'PIECE'       ,     0.00, 'Підготовчі роботи', 9),
(gen_random_uuid(), 'TILING', 'Влаштування деформаційних швів'                                           , 'WORK', 'LINEAR_METER',     0.00, 'Підготовчі роботи', 9),
-- Дрібний ремонт облицювання (7)
(gen_random_uuid(), 'TILING', 'День роботи майстра'                                                      , 'WORK', 'DAY'         ,  4195.00, 'Дрібний ремонт облицювання', 9),
(gen_random_uuid(), 'TILING', 'Заміна затірки швів'                                                      , 'WORK', 'M2'          ,   727.00, 'Дрібний ремонт облицювання', 9),
(gen_random_uuid(), 'TILING', 'Обсяг замовлення менше 10 м²'                                             , 'WORK', 'M2'          ,  1530.00, 'Дрібний ремонт облицювання', 9),
(gen_random_uuid(), 'TILING', 'Обсяг замовлення менше 25 м²'                                             , 'WORK', 'M2'          ,  1046.00, 'Дрібний ремонт облицювання', 9),
(gen_random_uuid(), 'TILING', 'Ремонт облицювання з каменю'                                              , 'WORK', 'M2'          ,  2500.00, 'Дрібний ремонт облицювання', 9),
(gen_random_uuid(), 'TILING', 'Ремонт облицювання з мозаїки'                                             , 'WORK', 'M2'          ,  3500.00, 'Дрібний ремонт облицювання', 9),
(gen_random_uuid(), 'TILING', 'Ремонт облицювання з плитки'                                              , 'WORK', 'M2'          ,  1910.00, 'Дрібний ремонт облицювання', 9),
-- Облицювання сходів (10)
(gen_random_uuid(), 'TILING', 'Влаштування гідроізоляції'                                                , 'WORK', 'M2'          ,   280.00, 'Облицювання сходів', 9),
(gen_random_uuid(), 'TILING', 'Встановлення капіносу'                                                    , 'WORK', 'LINEAR_METER',   445.00, 'Облицювання сходів', 9),
(gen_random_uuid(), 'TILING', 'Затирання швів від 3 мм цементною сумішшю'                                , 'WORK', 'M2'          ,   174.00, 'Облицювання сходів', 9),
(gen_random_uuid(), 'TILING', 'Затирання швів цементною сумішшю з латексом'                              , 'WORK', 'M2'          ,   300.00, 'Облицювання сходів', 9),
(gen_random_uuid(), 'TILING', 'Коефіцієнт на погодні умови'                                              , 'WORK', 'PERCENT'     ,    20.00, 'Облицювання сходів', 9),
(gen_random_uuid(), 'TILING', 'Облицювання радіусних сходів (без підступка)'                             , 'WORK', 'LINEAR_METER',  1467.00, 'Облицювання сходів', 9),
(gen_random_uuid(), 'TILING', 'Укладання керамограніту 20 мм'                                            , 'WORK', 'M2'          ,  1106.00, 'Облицювання сходів', 9),
(gen_random_uuid(), 'TILING', 'Укладання керамограніту на вулиці'                                        , 'WORK', 'M2'          ,   775.00, 'Облицювання сходів', 9),
(gen_random_uuid(), 'TILING', 'Укладання клінкерної підлогової плитки'                                   , 'WORK', 'M2'          ,   891.00, 'Облицювання сходів', 9),
(gen_random_uuid(), 'TILING', 'Укладання плитки на сходи та підсходинок'                                 , 'WORK', 'LINEAR_METER',   950.00, 'Облицювання сходів', 9),
-- Промислове облицювання (8)
(gen_random_uuid(), 'TILING', 'Облицювання «диким каменем» фасаду будинку, парканів'                     , 'WORK', 'M2'          ,  1310.00, 'Промислове облицювання', 9),
(gen_random_uuid(), 'TILING', 'Облицювання будинків клінкером «під цеглу»'                               , 'WORK', 'M2'          ,  1014.00, 'Промислове облицювання', 9),
(gen_random_uuid(), 'TILING', 'Облицювання каменем обсягів'                                              , 'WORK', 'M2'          ,   883.00, 'Промислове облицювання', 9),
(gen_random_uuid(), 'TILING', 'Облицювання кислотостійкою плиткою'                                       , 'WORK', 'M2'          ,   910.00, 'Промислове облицювання', 9),
(gen_random_uuid(), 'TILING', 'Облицювання мозаїкою обсягів'                                             , 'WORK', 'M2'          ,   869.00, 'Промислове облицювання', 9),
(gen_random_uuid(), 'TILING', 'Облицювання стандартною плиткою обсягів'                                  , 'WORK', 'M2'          ,   547.00, 'Промислове облицювання', 9),
(gen_random_uuid(), 'TILING', 'Облицювання сходових маршів'                                              , 'WORK', 'LINEAR_METER',   754.00, 'Промислове облицювання', 9),
(gen_random_uuid(), 'TILING', 'Облицювання широкоформатною плиткою обсягів'                              , 'WORK', 'M2'          ,  1238.00, 'Промислове облицювання', 9);
