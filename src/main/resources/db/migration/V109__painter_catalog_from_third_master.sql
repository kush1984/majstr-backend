-- PAINTER default catalog — a THIRD master's price list folded in (2026-08-20), same additive rule
-- as V96/V99: median the price where a position already exists, add only what is genuinely missing,
-- distribute the new positions into the bundles that already cover that scope (V99: "краще трохи
-- більші шаблони ніж купа маленьких"). 28 source positions triaged against the live 216-row PAINTER
-- catalog.
--
-- Vocabulary note: the source list says «відкоси»; this catalog (and its templates) already use
-- «укоси» for the same thing («Штукатурка укосів», the «Укоси вікон» bundle) — so the new slope rows
-- are named «...укосів» to stay findable and to resolve their template price (the template→catalog
-- price join is on lower(trim(name)), see V99 PART 2). Spelling normalised to the catalog's «Грунт-»
-- / «Штукатурка», not the source's «Ґрунт-» / «Оштукатурення».
--
-- ===== ALREADY IN THE CATALOG → median(our, theirs), rounded to whole ₴ (6 repriced) ===========
--   Армування стиків ГКЛ                        100 & 50  → 75   (src «Армування швів ГК»)
--   Монтаж шпаклювальних кутиків                 100 & 120 → 110  (src «Монтаж малярних кутників»)
--   Грунтовка поверхонь перед штукатуркою армув.  40 & 30  → 35   (src «...перед оштукатуренням»)
--   Поклейка стрічки «американка»                100 & 120 → 110  (src «...еластичної стрічки на кути»)
--   Закидання штраб (ел/сант)                    115 & 100 → 108  (src «Зароблення комунікаційних штроб»)
--   Грунтування                                   35 & 30  → 33   (src «Ґрунтування стін та стелі...»)
-- Already present at the SAME price, left untouched: «Штукатурка укосів» 350 (src «Оштукатурення
-- відкосів»), «Штукатурні роботи (від)» 350 (src «Оштукатурення стін»), «Фарбування стін/стель» 180
-- (src «Фарбування стін та стелі»).
--
-- ===== COVERED by an existing position → NOT added, NOT repriced (5) ===========================
--   «Шпаклювання відкосів 3 рази...»      ⊂ «Шпаклівка коробів, укосів, ніш та виступів...» (400)
--   «Шпаклювання стін та стелі 3 рази...» ⊂ finish-putty family («Шпаклівка стін/стелі під
--                                             фарбування» 380/420, «Шпаклювання фінішне (2–4)» 240)
--   «Ґрунтування відкосів перед оштукат.» ⊂ merged into the one «Грунтування укосів» row below
--   «Захист вікон/дверей/підлоги плівкою» ⊂ «Обклеювання вікон/дверей плівкою» + «Укривання підлоги
--                                             плівкою» + «Обклеювання приміщення (захист)»
--   «Обезпилення+грунтування стін/стелі»  ⊂ «Обезпилення поверхні» (25) + «Грунтування» (33)
--
-- ===== NEW positions (14) — inserted below, added_in_version = 12 ==============================

UPDATE catalog_templates SET suggested_price =  75.00 WHERE trade='PAINTER' AND unit='LINEAR_METER' AND name='Армування стиків ГКЛ';
UPDATE catalog_templates SET suggested_price = 110.00 WHERE trade='PAINTER' AND unit='LINEAR_METER' AND name='Монтаж шпаклювальних кутиків';
UPDATE catalog_templates SET suggested_price =  35.00 WHERE trade='PAINTER' AND unit='M2'           AND name='Грунтовка поверхонь перед штукатуркою армуванням';
UPDATE catalog_templates SET suggested_price = 110.00 WHERE trade='PAINTER' AND unit='LINEAR_METER' AND name='Поклейка стрічки «американка»';
UPDATE catalog_templates SET suggested_price = 108.00 WHERE trade='PAINTER' AND unit='LINEAR_METER' AND name='Закидання штраб (ел/сант)';
UPDATE catalog_templates SET suggested_price =  33.00 WHERE trade='PAINTER' AND unit='M2'           AND name='Грунтування';

INSERT INTO catalog_templates (id, trade, name, type, unit, suggested_price, category, added_in_version) VALUES
-- Укоси (slopes), м.п. — the catalog had no slope grounding/dedust/painting rows (3)
(gen_random_uuid(), 'PAINTER', 'Грунтування укосів'                                              , 'WORK', 'LINEAR_METER',  30.00, 'Підготовка та захист', 12),
(gen_random_uuid(), 'PAINTER', 'Обезпилення та грунтування укосів перед фарбуванням'             , 'WORK', 'LINEAR_METER',  50.00, 'Підготовка та захист', 12),
(gen_random_uuid(), 'PAINTER', 'Фарбування укосів'                                               , 'WORK', 'LINEAR_METER', 180.00, 'Фарбування', 12),
-- Криволінійні площини (curved surfaces), м² — none existed (4)
(gen_random_uuid(), 'PAINTER', 'Штукатурка криволінійних площин'                                 , 'WORK', 'M2'          , 500.00, 'Штукатурка та армування', 12),
(gen_random_uuid(), 'PAINTER', 'Підготовка криволінійних площин під скловолокно'                 , 'WORK', 'M2'          , 300.00, 'Штукатурка та армування', 12),
(gen_random_uuid(), 'PAINTER', 'Приклеювання скловолокна на криволінійні площини'                , 'WORK', 'M2'          , 250.00, 'Штукатурка та армування', 12),
(gen_random_uuid(), 'PAINTER', 'Шпаклювання криволінійних площин 3 рази зі шліфуванням'           , 'WORK', 'M2'          , 500.00, 'Шпаклювання та шліфування', 12),
-- Приховані двері (hidden doors) — distinct from the plaster/armour rows already there (2)
(gen_random_uuid(), 'PAINTER', 'Монтаж ГКЛ у кілька шарів на укоси дверей прихованого монтажу та примикання', 'WORK', 'LINEAR_METER', 450.00, 'Приховані двері, тіньові шви, треки, люки', 12),
(gen_random_uuid(), 'PAINTER', 'Шпаклювання з армуванням навколо дверей прихованого монтажу'      , 'WORK', 'LINEAR_METER', 200.00, 'Приховані двері, тіньові шви, треки, люки', 12),
-- Решта нового (5)
(gen_random_uuid(), 'PAINTER', 'Шпаклювання швів ГКЛ та шурупів зі шліфуванням'                   , 'WORK', 'M2'          , 100.00, 'Шпаклювання та шліфування', 12),
(gen_random_uuid(), 'PAINTER', 'Місцевий ремонт цементно-вапняної штукатурки (перетяжка)'          , 'WORK', 'M2'          , 180.00, 'Штукатурка та армування', 12),
(gen_random_uuid(), 'PAINTER', 'Шліфування бетонних стін та стель від напливів бетону'            , 'WORK', 'M2'          , 300.00, 'Підготовка та захист', 12),
(gen_random_uuid(), 'PAINTER', 'Шліфування торців бетонних колон від напливів бетону'             , 'WORK', 'LINEAR_METER', 300.00, 'Підготовка та захист', 12),
(gen_random_uuid(), 'PAINTER', 'Лакування бетонних стін'                                          , 'WORK', 'M2'          , 150.00, 'Фарбування', 12);

-- ===== Distribute the new positions into the bundles that already cover that scope =============
-- Names match the catalog rows above verbatim so the template→catalog price join resolves. Appended
-- after each bundle's current max sort_order. «Лакування бетонних стін» stays catalog-only (no
-- bundle is a natural home).
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.sort_order
FROM estimate_templates t
JOIN (VALUES
    ('Укоси вікон',                   'Грунтування укосів'                                           , 'LINEAR_METER',  7),
    ('Укоси вікон',                   'Обезпилення та грунтування укосів перед фарбуванням'          , 'LINEAR_METER',  8),
    ('Укоси вікон',                   'Фарбування укосів'                                            , 'LINEAR_METER',  9),
    ('Приховані двері та тіньові шви', 'Монтаж ГКЛ у кілька шарів на укоси дверей прихованого монтажу та примикання', 'LINEAR_METER', 10),
    ('Приховані двері та тіньові шви', 'Шпаклювання з армуванням навколо дверей прихованого монтажу'  , 'LINEAR_METER', 11),
    ('ШТУКАТУРКА',                    'Шпаклювання швів ГКЛ та шурупів зі шліфуванням'               , 'M2'          , 22),
    ('ШТУКАТУРКА',                    'Місцевий ремонт цементно-вапняної штукатурки (перетяжка)'      , 'M2'          , 23),
    ('ШТУКАТУРКА',                    'Шліфування бетонних стін та стель від напливів бетону'        , 'M2'          , 24),
    ('ШТУКАТУРКА',                    'Шліфування торців бетонних колон від напливів бетону'         , 'LINEAR_METER', 25),
    ('ШТУКАТУРКА',                    'Штукатурка криволінійних площин'                              , 'M2'          , 26),
    ('ШТУКАТУРКА',                    'Підготовка криволінійних площин під скловолокно'              , 'M2'          , 27),
    ('ШТУКАТУРКА',                    'Приклеювання скловолокна на криволінійні площини'             , 'M2'          , 28),
    ('ШТУКАТУРКА',                    'Шпаклювання криволінійних площин 3 рази зі шліфуванням'        , 'M2'          , 29)
) AS v(bundle, name, unit, sort_order) ON t.name = v.bundle
WHERE t.trade = 'PAINTER' AND t.owner_id IS NULL;
