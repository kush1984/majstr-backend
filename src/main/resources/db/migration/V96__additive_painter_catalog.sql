-- The PAINTER default catalog, EXTENDED — deliberately not the tiling pattern (V82: DELETE + full
-- rebuild). PAINTER already carries 152 real, mostly non-zero-priced positions across 16
-- categories (Фасад, Шпалери, Мікроцемент, Звукоізоляція, Підвіконня, Вагонка, Стелі, Декор,
-- Оздоблення, Декоративна штукатурка and more) that the new price list below does not mention at
-- all. A DELETE FROM catalog_templates WHERE trade = 'PAINTER' the way V82 did it for tiling would
-- silently erase ~85-90 real positions no master asked to lose. Decision (2026-08-10): expand, only
-- remove where an exact duplicate is found.
--
-- Where the new positions come from: 4 real price lists (the master's own + a colleague's, the
-- master signed off on the defaults), median price where sources agree, single source otherwise.
-- ⚠-questions resolved: Фарбування (білий) 160 / (у кольорі) 180 [sources 130/160/220 — median];
-- Поклейка дифузорів 200 [range 100/200/300]; Фарбування 3D панелей 400 [300-500]; roofing work
-- dropped (not a painter's trade). Positions marked «†split» in the source spec (same price/
-- category quoted per m² AND per running metre) become two rows here, «(м²)» / «(м.п.)».
--
-- ============ DEDUPLICATION (two levels, per the tetris rule) =================================
--   1. WITHIN this spec: no two canonical positions share (normalized name, type, unit).
--      «Грунтування» kept singular (not duplicated into the painting branch); «Улаштування
--      скловолокна» merged into «Армування стін скловолокном» — one position, not two.
--   2. AGAINST the live catalog (fuzzy, punctuation-insensitive): exactly ONE canonical position
--      from the new spec already exists verbatim — «Грунтовка поверхонь бетоноконтактом»
--      (Штукатурка, M2, 100.00, shipped since V27) — so it is NOT re-inserted below.
--      Nothing else in the new spec collides with the unique index
--      (owner, lower(trim(name)), type, unit) — every other row is either genuinely new or
--      close-but-not-identical wording, which stays live and is logged below instead of merged.
--
--   Four PRE-EXISTING duplicate pairs were found INCIDENTALLY while doing this comparison — not
--   part of the new spec, just two waves of catalog import (V27/V31, added_in_version 1, vs the
--   V50 tetris import, added_in_version 5) describing the same "work done by someone else, by
--   agreement" job under different wording. Decision (2026-08-10, confirmed twice): keep the
--   higher-priced row of each pair, drop the lower one. Matched by CONTENT below (trade/name/type/
--   unit/price), not by id — catalog_templates rows are seeded with gen_random_uuid(), so the ids
--   differ per environment; only the name text is stable across a fresh install, CI, and this
--   production database alike. Neither row of either pair is referenced by any
--   price_insight_candidate on this production database (checked before this migration was
--   written — zero cascade risk here; a fresh install has no price_insight_candidate rows at all):
--     KEEP  Вирівнювання стін (зроблених не нами, за погодженням)              Штукатурка  M2  300.00
--     DROP  Вирівнювання стін не нами за погодженням                          Шпаклівка   M2   60.00
--     KEEP  Дефектовка стін (зроблених не нами, за погодженням)                Підготовка  M2  100.00
--     DROP  Дефектовка стін не нами за погодженням                            Шпаклівка   M2   60.00
--     KEEP  Шліфування стін після штукатурки (зробленої не нами)               Штукатурка  M2  120.00
--     DROP  Шліфування стін після штукатурки не нами                          Штукатурка  M2   60.00
--     KEEP  Ошкурення стін після шпаклівки (зробленої не нами, за погодженням) Шпаклівка   M2  100.00
--     DROP  Ошкурення стін після шпаклівки не нами                            Шпаклівка   M2   60.00
--
--   3. NEAR-DUPLICATES against the live catalog — different wording/scope, NOT merged, flagged for
--      a human to review (the tetris migration lost 110 near-dups this way; this one keeps them
--      visible instead of silently dropping either side):
--       new: Армування стін сіткою (150)         vs live: Армування сіткою (140, Штукатурка)
--       new: Армування фасадною сіткою (120)      vs live: Армування фасаду сітка перетяжка
--                                                           (290, Фасад) — large price gap
--       new: Грунтування (35)                     vs live: 3 differently-scoped "Грунтовка
--                                                           поверхонь перед..." rows (40/45/50)
--       new: Грунтування кварцгрунтом (90)        vs live: Грунтовка поверхні кварцгрунтом
--                                                           (25, Фасад) — large price gap, review
--       new: Грунт-фарба (праймер під фарбу) (80)  vs live: Фарбування грунт-фарбою (100)
--       new: Фарбування стін/стель (білий) (160)   vs live: Фарбування стін/стелі водоемульсійною
--                                                           фарбою (180/220)
--       new: Розділення кольорів (скотч) (85)      vs live: Розділення та розмітка двох кольорів
--                                                           декор штукатурки (100, Фасад)
--       new: Шпаклювання довкола дверей/швів/... (260) vs live: Шпаклівка коробів укосів ніш під
--                                                           фарбування (260) / ...та виступів (400)
--       new: Шпаклювання стін старт (150)          vs live: Шпаклівка старт по цементній
--                                                           штукатурці (200)
--       new: Шпаклювання стелі (270)                vs live: Шпаклівка стелі під фарбування (420)
--                                                           / під склополотно шпалери (360)
--       new: Шпаклювання фінішне (240)              vs live: Шпаклівка стін під фарбування (380)
--                                                           / під склополотно шпалери (330)
--       new: Штукатурні роботи від (350)            vs live: 12 thickness/method-specific
--                                                           «Штукатурка стін ...» rows (270-500)
--       new: Фарбування 3D панелей (400)            vs live: Фарбування 3 Д панелей з підготовкою
--                                                           (600) / Монтаж 3 Д панелей з
--                                                           підготовкою під фарбування (4500,
--                                                           different job — прибрано з порівняння)
--       new: Фарбування молдинга до 6см (120)       vs live: 4 differently-scoped «Багети» rows
--                                                           (100-140)
--       new: Фарбування дверей прих. монтажу, одна сторона (2500) vs live: ...з двох сторін (4000)
--       new: Акрилення примикань (100)              vs live: Герметизація швів стиків акрилом
--                                                           мастикою (130/220)
--       new: Демонтажні роботи (120)                vs live: Демонтаж будівельного риштування
--                                                           (50, Фасад — different scope, scaffold)
--   None of the above collide on (lower(trim(name)), type, unit) so none block the INSERT; they
--   simply describe the same trade from two different price lists at different granularity.
-- ==============================================================================================
--
-- Baseline for V97: only the 4 rows this migration removes, captured before the DELETE, so V97 can
-- tell "the master's own copy of this exact leftover, still at our old price" (cleanup candidate)
-- from "the master repriced it" (their row, keep it) — same logic as tiling_v9_baseline, scoped to
-- what actually changed instead of the whole trade. Matched by content (name/type/unit/price), not
-- id, for the same reason as the DELETE below.
CREATE TABLE painter_v10_removed_baseline AS
SELECT lower(trim(name)) AS name_key, type, unit, suggested_price
FROM catalog_templates
WHERE trade = 'PAINTER' AND type = 'WORK' AND unit = 'M2' AND suggested_price = 60.00
  AND lower(trim(name)) IN (
      'вирівнювання стін не нами за погодженням',
      'дефектовка стін не нами за погодженням',
      'шліфування стін після штукатурки не нами',
      'ошкурення стін після шпаклівки не нами'
  );

DELETE FROM catalog_templates
WHERE trade = 'PAINTER' AND type = 'WORK' AND unit = 'M2' AND suggested_price = 60.00
  AND lower(trim(name)) IN (
      'вирівнювання стін не нами за погодженням',
      'дефектовка стін не нами за погодженням',
      'шліфування стін після штукатурки не нами',
      'ошкурення стін після шпаклівки не нами'
  );

-- added_in_version = 10 (the tiling rebuild used 9). "Грунтовка бетоноконтактом" is deliberately
-- absent — it already exists verbatim (see dedup note above).
INSERT INTO catalog_templates (id, trade, name, type, unit, suggested_price, category, added_in_version) VALUES
-- Підготовка та захист (20)
(gen_random_uuid(), 'PAINTER', 'Обезпилення поверхні (м²)'                                          , 'WORK', 'M2'          ,  25.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Обезпилення поверхні (м.п.)'                                        , 'WORK', 'LINEAR_METER',  25.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Грунтування (м²)'                                                   , 'WORK', 'M2'          ,  35.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Грунтування (м.п.)'                                                 , 'WORK', 'LINEAR_METER',  35.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Грунтування кварцгрунтом (м²)'                                      , 'WORK', 'M2'          ,  90.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Грунтування кварцгрунтом (м.п.)'                                    , 'WORK', 'LINEAR_METER',  90.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Грунт-фарба (праймер під фарбу) (м²)'                               , 'WORK', 'M2'          ,  80.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Грунт-фарба (праймер під фарбу) (м.п.)'                             , 'WORK', 'LINEAR_METER',  80.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Пилосмоктання підлоги перед фарбуванням'                            , 'WORK', 'M2'          ,  20.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Укривання підлоги картоном'                                         , 'WORK', 'M2'          ,  50.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Укривання підлоги плівкою'                                          , 'WORK', 'M2'          ,  50.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Укривання підлоги МДФ'                                              , 'WORK', 'M2'          ,  60.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Обклеювання вікон/дверей плівкою'                                   , 'WORK', 'M2'          ,  75.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Обклеювання приміщення (захист) (м²)'                               , 'WORK', 'M2'          ,  60.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Обклеювання приміщення (захист) (м.п.)'                             , 'WORK', 'LINEAR_METER',  60.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Поклейка профілю трекових світильників'                             , 'WORK', 'LINEAR_METER', 100.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Поклейка повітряних дифузорів'                                      , 'WORK', 'LINEAR_METER', 200.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Відведення лінії фарби довкола вікон/дверей (м²)'                   , 'WORK', 'M2'          , 100.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Відведення лінії фарби довкола вікон/дверей (м.п.)'                 , 'WORK', 'LINEAR_METER', 100.00, 'Підготовка та захист', 10),
(gen_random_uuid(), 'PAINTER', 'Розділення кольорів (скотч)'                                        , 'WORK', 'LINEAR_METER',  85.00, 'Підготовка та захист', 10),
-- Демонтаж (3)
(gen_random_uuid(), 'PAINTER', 'Демонтаж батарей'                                                   , 'WORK', 'PIECE'       , 225.00, 'Демонтаж', 10),
(gen_random_uuid(), 'PAINTER', 'Демонтажні роботи (м²)'                                             , 'WORK', 'M2'          , 120.00, 'Демонтаж', 10),
(gen_random_uuid(), 'PAINTER', 'Демонтажні роботи (м.п.)'                                           , 'WORK', 'LINEAR_METER', 120.00, 'Демонтаж', 10),
-- Штукатурка та армування (19)
(gen_random_uuid(), 'PAINTER', 'Вирівнювання стін (м²)'                                             , 'WORK', 'M2'          , 170.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Вирівнювання стін (м.п.)'                                           , 'WORK', 'LINEAR_METER', 170.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Штукатурні роботи (від) (м²)'                                       , 'WORK', 'M2'          , 350.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Штукатурні роботи (від) (м.п.)'                                     , 'WORK', 'LINEAR_METER', 350.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Закидання штраб (ел/сант)'                                          , 'WORK', 'LINEAR_METER', 115.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Монтаж віконного примикання'                                        , 'WORK', 'LINEAR_METER', 110.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Армування стиків ГКЛ'                                               , 'WORK', 'LINEAR_METER', 100.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Монтаж шпаклювальних кутиків'                                       , 'WORK', 'LINEAR_METER', 100.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Монтаж шпаклювальних кутиків (арочних)'                             , 'WORK', 'LINEAR_METER', 200.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Монтаж кутника внутрішнього/мет.'                                   , 'WORK', 'LINEAR_METER', 100.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Поклейка стрічки «американка»'                                      , 'WORK', 'LINEAR_METER', 100.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Армування стін сіткою (м²)'                                         , 'WORK', 'M2'          , 150.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Армування стін сіткою (м.п.)'                                       , 'WORK', 'LINEAR_METER', 150.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Армування фасадною сіткою (м²)'                                     , 'WORK', 'M2'          , 120.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Армування фасадною сіткою (м.п.)'                                   , 'WORK', 'LINEAR_METER', 120.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Армування стін скловолокном (склохолст) (м²)'                       , 'WORK', 'M2'          , 140.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Армування стін скловолокном (склохолст) (м.п.)'                     , 'WORK', 'LINEAR_METER', 140.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Армування стель скловолокном (м²)'                                  , 'WORK', 'M2'          , 170.00, 'Штукатурка та армування', 10),
(gen_random_uuid(), 'PAINTER', 'Армування стель скловолокном (м.п.)'                                , 'WORK', 'LINEAR_METER', 170.00, 'Штукатурка та армування', 10),
-- Шпаклювання та шліфування (15)
(gen_random_uuid(), 'PAINTER', 'Шпаклювання стін (старт, за потреби) (м²)'                          , 'WORK', 'M2'          , 150.00, 'Шпаклювання та шліфування', 10),
(gen_random_uuid(), 'PAINTER', 'Шпаклювання стін (старт, за потреби) (м.п.)'                        , 'WORK', 'LINEAR_METER', 150.00, 'Шпаклювання та шліфування', 10),
(gen_random_uuid(), 'PAINTER', 'Базове шпаклювання під скловолокно (м²)'                            , 'WORK', 'M2'          , 185.00, 'Шпаклювання та шліфування', 10),
(gen_random_uuid(), 'PAINTER', 'Базове шпаклювання під скловолокно (м.п.)'                          , 'WORK', 'LINEAR_METER', 185.00, 'Шпаклювання та шліфування', 10),
(gen_random_uuid(), 'PAINTER', 'Шпаклювання фінішне (2–4 рази) (м²)'                                , 'WORK', 'M2'          , 240.00, 'Шпаклювання та шліфування', 10),
(gen_random_uuid(), 'PAINTER', 'Шпаклювання фінішне (2–4 рази) (м.п.)'                              , 'WORK', 'LINEAR_METER', 240.00, 'Шпаклювання та шліфування', 10),
(gen_random_uuid(), 'PAINTER', 'Шпаклювання стелі (м²)'                                             , 'WORK', 'M2'          , 270.00, 'Шпаклювання та шліфування', 10),
(gen_random_uuid(), 'PAINTER', 'Шпаклювання стелі (м.п.)'                                           , 'WORK', 'LINEAR_METER', 270.00, 'Шпаклювання та шліфування', 10),
(gen_random_uuid(), 'PAINTER', 'Шпаклювання довкола дверей/швів/дифузорів/треків/люків'             , 'WORK', 'LINEAR_METER', 260.00, 'Шпаклювання та шліфування', 10),
(gen_random_uuid(), 'PAINTER', 'Шліфування штукатурки (м²)'                                         , 'WORK', 'M2'          ,  50.00, 'Шпаклювання та шліфування', 10),
(gen_random_uuid(), 'PAINTER', 'Шліфування штукатурки (м.п.)'                                       , 'WORK', 'LINEAR_METER',  50.00, 'Шпаклювання та шліфування', 10),
(gen_random_uuid(), 'PAINTER', 'Шліфування під скловолокно/склохолст (м²)'                          , 'WORK', 'M2'          ,  60.00, 'Шпаклювання та шліфування', 10),
(gen_random_uuid(), 'PAINTER', 'Шліфування під скловолокно/склохолст (м.п.)'                        , 'WORK', 'LINEAR_METER',  60.00, 'Шпаклювання та шліфування', 10),
(gen_random_uuid(), 'PAINTER', 'Шліфування стін/стель (фінішне) (м²)'                               , 'WORK', 'M2'          , 100.00, 'Шпаклювання та шліфування', 10),
(gen_random_uuid(), 'PAINTER', 'Шліфування стін/стель (фінішне) (м.п.)'                             , 'WORK', 'LINEAR_METER', 100.00, 'Шпаклювання та шліфування', 10),
-- Фарбування (5)
(gen_random_uuid(), 'PAINTER', 'Фарбування стін/стель (білий) (м²)'                                 , 'WORK', 'M2'          , 160.00, 'Фарбування', 10),
(gen_random_uuid(), 'PAINTER', 'Фарбування стін/стель (білий) (м.п.)'                               , 'WORK', 'LINEAR_METER', 160.00, 'Фарбування', 10),
(gen_random_uuid(), 'PAINTER', 'Фарбування стін/стель (у кольорі) (м²)'                             , 'WORK', 'M2'          , 180.00, 'Фарбування', 10),
(gen_random_uuid(), 'PAINTER', 'Фарбування стін/стель (у кольорі) (м.п.)'                           , 'WORK', 'LINEAR_METER', 180.00, 'Фарбування', 10),
(gen_random_uuid(), 'PAINTER', 'Фарбування 3D панелей'                                              , 'WORK', 'M2'          , 400.00, 'Фарбування', 10),
-- Молдинги та декор (5)
(gen_random_uuid(), 'PAINTER', 'Фарбування молдинга/багета до 6 см'                                 , 'WORK', 'LINEAR_METER', 120.00, 'Молдинги та декор', 10),
(gen_random_uuid(), 'PAINTER', 'Фарбування молдинга/багета 6–10 см'                                 , 'WORK', 'LINEAR_METER', 180.00, 'Молдинги та декор', 10),
(gen_random_uuid(), 'PAINTER', 'Фарбування молдинга/багета 10+ см'                                  , 'WORK', 'LINEAR_METER', 220.00, 'Молдинги та декор', 10),
(gen_random_uuid(), 'PAINTER', 'Фарбування плінтуса прихованого монтажу (перед монтажем)'           , 'WORK', 'LINEAR_METER', 150.00, 'Молдинги та декор', 10),
(gen_random_uuid(), 'PAINTER', 'Фарбування гіпсових світильників'                                   , 'WORK', 'PIECE'       , 150.00, 'Молдинги та декор', 10),
-- Приховані двері, тіньові шви, треки, люки (9)
(gen_random_uuid(), 'PAINTER', 'Армування рами дверей прих. монтажу (сітка+американка+скловолокно)', 'WORK', 'LINEAR_METER', 350.00, 'Приховані двері, тіньові шви, треки, люки', 10),
(gen_random_uuid(), 'PAINTER', 'Штукатурка дверей прихованого монтажу'                              , 'WORK', 'LINEAR_METER', 400.00, 'Приховані двері, тіньові шви, треки, люки', 10),
(gen_random_uuid(), 'PAINTER', 'Обклеювання рами дверей прих. монтажу'                              , 'WORK', 'LINEAR_METER', 100.00, 'Приховані двері, тіньові шви, треки, люки', 10),
(gen_random_uuid(), 'PAINTER', 'Зароблення швів на дверях/тіньових профілів'                        , 'WORK', 'LINEAR_METER', 100.00, 'Приховані двері, тіньові шви, треки, люки', 10),
(gen_random_uuid(), 'PAINTER', 'Обклеювання тіньового шва перед монтажем'                           , 'WORK', 'LINEAR_METER', 100.00, 'Приховані двері, тіньові шви, треки, люки', 10),
(gen_random_uuid(), 'PAINTER', 'Армування тіньового шва/люків стрічкою'                             , 'WORK', 'LINEAR_METER', 100.00, 'Приховані двері, тіньові шви, треки, люки', 10),
(gen_random_uuid(), 'PAINTER', 'Облаштування тіньового профілю'                                     , 'WORK', 'LINEAR_METER', 100.00, 'Приховані двері, тіньові шви, треки, люки', 10),
(gen_random_uuid(), 'PAINTER', 'Фарбування тіньових швів'                                           , 'WORK', 'LINEAR_METER', 200.00, 'Приховані двері, тіньові шви, треки, люки', 10),
(gen_random_uuid(), 'PAINTER', 'Фарбування дверей прих. монтажу (одна сторона)'                     , 'WORK', 'PIECE'       ,2500.00, 'Приховані двері, тіньові шви, треки, люки', 10),
-- Інше (3)
(gen_random_uuid(), 'PAINTER', 'Акрилення примикань'                                                , 'WORK', 'LINEAR_METER', 100.00, 'Інше', 10),
(gen_random_uuid(), 'PAINTER', 'Відтяжка'                                                           , 'WORK', 'LINEAR_METER',  40.00, 'Інше', 10),
(gen_random_uuid(), 'PAINTER', 'Додаткові роботи'                                                   , 'WORK', 'HOUR'        , 500.00, 'Інше', 10);
