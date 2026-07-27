-- Clean up what V50 (tetris import) left behind in the DEFAULT catalog and templates.
--
-- V50 stated it deduped "punctuation-insensitive, no duplicates introduced". That held for the
-- position NAMES it compared, but the pre-existing v1 rows had punctuation AND connecting words
-- stripped ("Монтаж котельної котел бойлер насоси крани фільтра"), so the comparison could not
-- see them. Three separate consequences; only the ones with a single correct answer are fixed
-- here. The duplicate positions and the placeholder prices need a pricing decision from the
-- owner and are deliberately left alone (see seed-audit/ for the reviewed lists).
--
-- Nothing in here touches a master's OWN catalog_items. Default catalog data is reference data
-- copied by value; changing it only affects what NEW copies receive. The added positions carry
-- version 8 so existing masters are offered them by "Додати нові позиції".

-- ---------------------------------------------------------------------------------------------
-- 1. Template positions that could never get a price.
--
-- A master's catalog is seeded from catalog_templates for THEIR trades only (findByTradeIn), and
-- apply-template substitutes the price by lower(name). These 26 positions are referenced by a
-- default template of one trade while existing in the catalog of ANOTHER, so they landed in the
-- estimate at price 0 — worst of all in ГІДРОІЗОЛЯЦІЯ (4 of 4 positions) and ФАСАДНІ РОБОТИ
-- (15 of 19), which were effectively empty price-wise.
--
-- Fixed by making the position exist in the referencing template's trade too, copying name, type,
-- unit, price and category verbatim from where it already lives. This is safe for a master who
-- has BOTH trades: the per-user copy dedups on lower(trim(name))|type|unit
-- (CatalogTemplateService.key, backed by ux_catalog_items_owner_name_type_unit), so they still
-- receive exactly one row. Moving the template or the position instead would have taken the
-- position away from the masters who have it today.
INSERT INTO catalog_templates (id, trade, name, type, unit, suggested_price, category, added_in_version) VALUES
  (gen_random_uuid(), 'BUILDER', 'Гідроізоляція плівкова', 'WORK', 'M2', 60.00, 'Підготовка', 8),
  (gen_random_uuid(), 'BUILDER', 'Гідроізоляція підлоги стін в санвузлі мастика', 'WORK', 'M2', 150.00, 'Підготовка', 8),
  (gen_random_uuid(), 'BUILDER', 'Гідроізоляція сухою сумішшю', 'WORK', 'M2', 130.00, 'Підготовка', 8),
  (gen_random_uuid(), 'BUILDER', 'Кладка цегляної підстави під ванну', 'WORK', 'PIECE', 900.00, 'Кладка', 8),
  (gen_random_uuid(), 'BUILDER', 'Монтаж гідроізоляційної стрічки', 'WORK', 'LINEAR_METER', 100.00, 'Підготовка', 8),
  (gen_random_uuid(), 'BUILDER', 'Облаштування дверного пройому звуження розширення', 'WORK', 'PIECE', 800.00, 'Кладка', 8),
  (gen_random_uuid(), 'DRYWALL', 'Безкаркасна звукоізоляція стелі', 'WORK', 'M2', 80.00, 'Звукоізоляція', 8),
  (gen_random_uuid(), 'DRYWALL', 'Безкаркасна звукоізоляція стін', 'WORK', 'M2', 60.00, 'Звукоізоляція', 8),
  (gen_random_uuid(), 'DRYWALL', 'Герметизація швів стиків герметиком', 'WORK', 'LINEAR_METER', 130.00, 'Звукоізоляція', 8),
  (gen_random_uuid(), 'DRYWALL', 'Звукоізоляція стін мінеральною ватою', 'WORK', 'M2', 60.00, 'Звукоізоляція', 8),
  (gen_random_uuid(), 'FLOORING', 'Фарбування терасної дошки', 'WORK', 'M2', 120.00, 'Споруди', 8),
  (gen_random_uuid(), 'PAINTER', 'Армування укосів сітка перетяжка', 'WORK', 'LINEAR_METER', 300.00, 'Фасад', 8),
  (gen_random_uuid(), 'PAINTER', 'Армування фасаду сітка перетяжка', 'WORK', 'M2', 290.00, 'Фасад', 8),
  (gen_random_uuid(), 'PAINTER', 'Грунтовка поверхні кварцгрунтом', 'WORK', 'M2', 25.00, 'Фасад', 8),
  (gen_random_uuid(), 'PAINTER', 'Декоративна штукатурка фасаду короїд баранець', 'WORK', 'M2', 330.00, 'Фасад', 8),
  (gen_random_uuid(), 'PAINTER', 'Демонтаж будівельного риштування', 'WORK', 'M2', 50.00, 'Фасад', 8),
  (gen_random_uuid(), 'PAINTER', 'Монтаж будівельного риштування', 'WORK', 'M2', 50.00, 'Фасад', 8),
  (gen_random_uuid(), 'PAINTER', 'Монтаж вати клей дюбель', 'WORK', 'M2', 500.00, 'Фасад', 8),
  (gen_random_uuid(), 'PAINTER', 'Монтаж відливів на вікна', 'WORK', 'LINEAR_METER', 240.00, 'Фасад', 8),
  (gen_random_uuid(), 'PAINTER', 'Монтаж пінопласта клей дюбель', 'WORK', 'M2', 300.00, 'Фасад', 8),
  (gen_random_uuid(), 'PAINTER', 'Монтаж стартової планки', 'WORK', 'LINEAR_METER', 240.00, 'Фасад', 8),
  (gen_random_uuid(), 'PAINTER', 'Облицювання клінкерною плиткою', 'WORK', 'M2', 250.00, 'Фасад', 8),
  (gen_random_uuid(), 'PAINTER', 'Утеплення укосів ватою', 'WORK', 'LINEAR_METER', 300.00, 'Фасад', 8),
  (gen_random_uuid(), 'PAINTER', 'Утеплення укосів пінопластом', 'WORK', 'LINEAR_METER', 600.00, 'Фасад', 8),
  (gen_random_uuid(), 'PAINTER', 'Утеплення цоколя пінополістиролом', 'WORK', 'LINEAR_METER', 300.00, 'Фасад', 8),
  (gen_random_uuid(), 'PAINTER', 'Фарбування фасаду', 'WORK', 'M2', 220.00, 'Фасад', 8);

-- ---------------------------------------------------------------------------------------------
-- 2. A unit the template preview showed but the estimate did not use.
--
-- V48 removed the DRYWALL LINEAR_METER variant of "Армування кладки сіткою арматурою" and kept
-- the BUILDER M2 one, but left three template items declaring LINEAR_METER. The preview showed
-- м.пог while apply-template wrote м² from the catalog — the master was shown one unit and got
-- another. Align the templates with the surviving catalog row.
UPDATE estimate_template_items i
SET unit = 'M2'
WHERE lower(i.name) = 'армування кладки сіткою арматурою'
  AND i.unit = 'LINEAR_METER'
  AND EXISTS (SELECT 1 FROM estimate_templates et
              WHERE et.id = i.template_id AND et.is_default AND et.trade = 'BUILDER');

-- ---------------------------------------------------------------------------------------------
-- 3. Categories that duplicated an existing bucket.
--
-- The catalog screen groups by category, so a master with these trades saw TWO sections with the
-- same name — "Кладка" and "КЛАДКА", "Штукатурка" and "ШТУКАТУРКА". V50 wrote its categories in
-- caps while the existing catalog uses sentence case. Category is display-only grouping; no
-- matching depends on it, which is what makes this mechanical.
--
-- Only merges INTO a bucket that already exists in the SAME trade are done here.
UPDATE catalog_templates SET category = 'Кладка'                 WHERE trade='BUILDER'  AND category='КЛАДКА';
UPDATE catalog_templates SET category = 'Паркан'                 WHERE trade='BUILDER'  AND category='ПАРКАН';
UPDATE catalog_templates SET category = 'Фундамент'              WHERE trade='BUILDER'  AND category='ФУНДАМЕНТ';
UPDATE catalog_templates SET category = 'Підлога'                WHERE trade='FLOORING' AND category='ПІДЛОГА';
UPDATE catalog_templates SET category = 'Стяжка'                 WHERE trade='FLOORING' AND category='СТЯЖКА';
UPDATE catalog_templates SET category = 'Декоративна штукатурка' WHERE trade='PAINTER'  AND category='ДЕКОРАТИВНА ШТУКАТУРКА';
UPDATE catalog_templates SET category = 'Штукатурка'             WHERE trade='PAINTER'  AND category='ШТУКАТУРКА';

-- Same thing, where the existing bucket is worded differently rather than just cased differently.
UPDATE catalog_templates SET category = 'Земляні'     WHERE trade='BUILDER' AND category='ЗЕМЛЯНІ РОБОТИ';
UPDATE catalog_templates SET category = 'Благоустрій' WHERE trade='BUILDER' AND category='БЛАГОУСТРІЙ ТЕРИТОРІЇ';
UPDATE catalog_templates SET category = 'Кладка'      WHERE trade='BUILDER' AND category='КЛАДОЧНІ РОБОТИ';
UPDATE catalog_templates SET category = 'Покрівля'    WHERE trade='BUILDER' AND category='ПОКРІВЕЛЬНІ РОБОТИ';
UPDATE catalog_templates SET category = 'Зварювання'  WHERE trade='BUILDER' AND category='ЗВАРЮВАЛЬНІ РОБОТИ';
-- The catalog screen groups by category NAME, not by trade, so a master holding both PAINTER and
-- DRYWALL saw "Звукоізоляція" and "ЗВУКОІЗОЛЯЦІЯ" as two sections of the same thing.
UPDATE catalog_templates SET category = 'Звукоізоляція' WHERE trade='DRYWALL' AND category='ЗВУКОІЗОЛЯЦІЯ';

-- Left as they are on purpose:
--   ЗІЗ (DEMOLITION) — an acronym, caps is correct;
--   САНТЕХНІКА (38), ЕЛЕКТРИКА (34), ПЛИТОЧНІ РОБОТИ (31), ВНУТРІШНЄ ОЗДОБЛЕННЯ ПРИМІЩЕНЬ (23) —
--     these repeat the TRADE name, so they are not categories at all but unsorted heaps holding a
--     fifth of their trade. Splitting them across the real buckets (Розетки / Щит / Освітлення /
--     Штроблення …) is a per-position judgement, not a rename;
--   ГІПСОКАРТОН (8, DRYWALL) and ФАСАДНІ РОБОТИ (4, PAINTER) — plausible targets exist
--     (Конструкції, a new Фасад) but neither is the obvious single answer.
