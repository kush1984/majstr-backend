-- Corrects V98 and extends V96, in one migration because V96-V98 are already applied on the
-- database this runs against next — per project rule, an applied migration is never edited.
--
-- ============ PART 1: V98's DELETE was wrong, revert it ========================================
-- V98 replaced the 19 pre-existing PAINTER bundles with 6 new phase-ordered ones on the theory
-- that templates are curated, not reference data, the same way V84 replaced tiling's. That theory
-- was wrong for a trade whose OLD templates were themselves real, detailed, non-trivial bundles
-- («ВНУТРІШНЄ ОЗДОБЛЕННЯ ПРИМІЩЕНЬ» alone carried 61 lines) — deleting them lost real curated work,
-- not stale tetris leftovers. Caught by the user immediately after building locally (2026-08-10):
-- "для чого ми прибрали всі шаблони... треба ревертнути".
--
-- Corrected approach, and it is the SAME "additive, not destructive" rule V96 already used for the
-- catalog: restore the 19, and fold the new phase-3/4/5's positions into whichever of the 19
-- already covers that scope, rather than shipping a pile of small near-duplicate bundles
-- ("краще трохи більші шаблони ніж купа маленьких" — user, 2026-08-10). Only the two phases with
-- no existing match (room-prep/protection, hidden-door work) get their own new bundle.
--
--   ШТУКАТУРКА                      += the plaster/reinforcement phase (armouring, leveling,
--                                       corner beads, window-reveal mounting, wiring backfill)
--   Стіни під фарбування            += the full wall-prep cycle AND the finish-coat phase (both
--                                       are literally a more detailed version of what this bundle
--                                       already does, in the same order)
--   Багети молдінги                 += the molding/decor phase
--   Захист і підготовка приміщення  -- NEW, no existing bundle covers room protection/prep alone
--   Приховані двері та тіньові шви  -- NEW, no existing bundle covers hidden-door work at all
--
-- The 19 restored below are copied verbatim from what V1-V95 actually produce (queried on a fresh
-- throwaway database built the same way Flyway builds one, not hand-transcribed) — same discipline
-- as V96's "planted, not picked" test data.
--
-- ============ PART 2: split-pair naming was wrong too ==========================================
-- V96 named each †split position twice, once per unit, disambiguated by a "(м²)"/"(м.п.)" suffix
-- IN THE NAME. Flagged as a real bug, not a style question, before touching it: every price lookup
-- and every multi-template-apply dedup in EstimateTemplateService joins purely on
-- lower(trim(name)) — no unit in the key at all (see `nameKey`). Two catalog rows sharing a name
-- collide there: the catalog→price map keeps only one (Collectors.toMap's (a,b)->a), and the
-- multi-select apply's `seen` dedup drops the second bundle line outright. That is exactly why the
-- suffix existed. Resolved with the user (2026-08-10): drop the LINEAR_METER half of each pair
-- entirely and keep only the M2 half, unsuffixed — "якщо є однакові позиції тоді залишаємо одну з
-- них, м², але щоб воно йшло як одиниця, а не в тайтлі" — the unit already has its own column and
-- its own place in the UI; it does not belong inside the name text too.
--
-- This does lose the ability to bill a †split position per running metre (a narrow strip primed
-- rather than a whole wall, for instance) — an accepted trade-off, not an oversight; the master
-- can always add a one-off LINEAR_METER line by hand if a job genuinely needs it.
--
-- ============ PART 3: organizational services, the same category tiling ships ==================
-- Prompted directly (2026-08-10, with a screenshot of tiling's "ОРГАНІЗАЦІЙНІ ПОСЛУГИ"): PAINTER
-- had essentially none of this — no site-visit/consultation/transport/cleanup/warranty-callout
-- positions at all (checked: only one loosely related row, "Збирання сміття в мішки після
-- демонтажу", a different scope). 11 positions added, mirroring tiling's category, adapted for
-- painting wording. All ship at price 0 — unlike the rest of V96, none of these came from the 4
-- real painter price lists that were this rework's actual source data, so a made-up number here
-- would be exactly the mistake V82 already decided against for its own zero-priced positions.
-- Logged in open-questions for the master to price for real later.
--
-- The three positions a painter bills on nearly every job — cleanup, waste removal, the warranty
-- callout — are appended to EVERY default PAINTER bundle that doesn't already carry them, the same
-- "always-billed four" pattern V84 used for tiling (there it was four positions including film
-- covering; PAINTER already has its own more granular protection items per-bundle, so film covering
-- is not repeated here).
--
-- added_in_version = 11 for the organizational-services rows (V96 used 10).

-- ---- Part 1a: the 22 †split LINEAR_METER halves go, the M2 halves lose their suffix -----------

DELETE FROM catalog_templates
WHERE trade = 'PAINTER' AND added_in_version = 10 AND unit = 'LINEAR_METER'
  AND lower(trim(name)) IN (
    'обезпилення поверхні (м.п.)', 'грунтування (м.п.)', 'грунтування кварцгрунтом (м.п.)',
    'грунт-фарба (праймер під фарбу) (м.п.)', 'обклеювання приміщення (захист) (м.п.)',
    'відведення лінії фарби довкола вікон/дверей (м.п.)', 'демонтажні роботи (м.п.)',
    'вирівнювання стін (м.п.)', 'штукатурні роботи (від) (м.п.)', 'армування стін сіткою (м.п.)',
    'армування фасадною сіткою (м.п.)', 'армування стін скловолокном (склохолст) (м.п.)',
    'армування стель скловолокном (м.п.)', 'шпаклювання стін (старт, за потреби) (м.п.)',
    'базове шпаклювання під скловолокно (м.п.)', 'шпаклювання фінішне (2–4 рази) (м.п.)',
    'шпаклювання стелі (м.п.)', 'шліфування штукатурки (м.п.)',
    'шліфування під скловолокно/склохолст (м.п.)', 'шліфування стін/стель (фінішне) (м.п.)',
    'фарбування стін/стель (білий) (м.п.)', 'фарбування стін/стель (у кольорі) (м.п.)'
  );

UPDATE catalog_templates
SET name = regexp_replace(name, '\s*\(м²\)$', '')
WHERE trade = 'PAINTER' AND added_in_version = 10 AND unit = 'M2'
  AND name ~ '\(м²\)$';

-- Mirror both changes onto the one registered painter's own catalog copy — same rows V97 pushed
-- moments ago, still LIBRARY-sourced at our unchanged price (baseline-protected exactly like V97).
DELETE FROM catalog_items
WHERE trade = 'PAINTER' AND source = 'LIBRARY' AND unit = 'LINEAR_METER'
  AND lower(trim(name)) IN (
    'обезпилення поверхні (м.п.)', 'грунтування (м.п.)', 'грунтування кварцгрунтом (м.п.)',
    'грунт-фарба (праймер під фарбу) (м.п.)', 'обклеювання приміщення (захист) (м.п.)',
    'відведення лінії фарби довкола вікон/дверей (м.п.)', 'демонтажні роботи (м.п.)',
    'вирівнювання стін (м.п.)', 'штукатурні роботи (від) (м.п.)', 'армування стін сіткою (м.п.)',
    'армування фасадною сіткою (м.п.)', 'армування стін скловолокном (склохолст) (м.п.)',
    'армування стель скловолокном (м.п.)', 'шпаклювання стін (старт, за потреби) (м.п.)',
    'базове шпаклювання під скловолокно (м.п.)', 'шпаклювання фінішне (2–4 рази) (м.п.)',
    'шпаклювання стелі (м.п.)', 'шліфування штукатурки (м.п.)',
    'шліфування під скловолокно/склохолст (м.п.)', 'шліфування стін/стель (фінішне) (м.п.)',
    'фарбування стін/стель (білий) (м.п.)', 'фарбування стін/стель (у кольорі) (м.п.)'
  );

UPDATE catalog_items
SET name = regexp_replace(name, '\s*\(м²\)$', '')
WHERE trade = 'PAINTER' AND source = 'LIBRARY' AND unit = 'M2' AND name ~ '\(м²\)$'
  AND EXISTS (
      SELECT 1 FROM catalog_templates ct
      WHERE ct.trade = 'PAINTER' AND ct.unit = 'M2'
        AND lower(trim(ct.name)) = lower(trim(regexp_replace(catalog_items.name, '\s*\(м²\)$', ''))));

-- ---- Part 1b: undo V98 --------------------------------------------------------------------------

DELETE FROM estimate_templates WHERE is_default AND trade = 'PAINTER';

-- Restored verbatim (name/unit/sort_order) from what V1-V95 actually seed. Багети молдінги,
-- Стіни під фарбування and ШТУКАТУРКА are extended below with the new-catalog phases.

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Багети молдінги', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Монтаж стельових багетів пінопласт до 8см', 'LINEAR_METER', 0),
  ('Монтаж молдінга з поліуретану', 'LINEAR_METER', 1),
  ('Фарбування стельових багетів пінопласт', 'LINEAR_METER', 2),
  -- extended (phase: Молдинги, багети, декор)
  ('Фарбування молдинга/багета до 6 см', 'LINEAR_METER', 3),
  ('Фарбування молдинга/багета 6–10 см', 'LINEAR_METER', 4),
  ('Фарбування молдинга/багета 10+ см', 'LINEAR_METER', 5),
  ('Фарбування плінтуса прихованого монтажу (перед монтажем)', 'LINEAR_METER', 6),
  ('Фарбування гіпсових світильників', 'PIECE', 7)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'ВНУТРІШНЄ ОЗДОБЛЕННЯ ПРИМІЩЕНЬ', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Виконання робіт на висоті понад 3м', 'PERCENT', 0),
  ('Чищення бетонних плит /підготовчі роботи/', 'M2', 1),
  ('Заробка стиків у бетоні', 'LINEAR_METER', 2),
  ('Заробка тріщин раковин шліфування', 'M2', 3),
  ('Збирання сміття в мішки після демонтажу', 'PIECE', 4),
  ('Захист підлоги картоном', 'M2', 5),
  ('Захист вікна плівкою картоном', 'PIECE', 6),
  ('Захист вхідних дверей картоном', 'PIECE', 7),
  ('Грунтовка поверхонь перед шпаклівкою, фарбуванням, поклейкою тощо', 'M2', 8),
  ('Паробар''єр гідробар''єр', 'M2', 9),
  ('Стелі Грильято', 'M2', 10),
  ('Стеля Армстронг', 'M2', 11),
  ('Монтаж на стелю пластикової вагонки', 'M2', 12),
  ('Монтаж дерев''яної вагонки на стіну', 'M2', 13),
  ('Монтаж дерев''яної вагонки на стелю', 'M2', 14),
  ('Фарбування дерев''яної вагонки', 'M2', 15),
  ('Монтаж нашовної планки на стики вагонки', 'LINEAR_METER', 16),
  ('Фарбування нашовної планки', 'LINEAR_METER', 17),
  ('Обрешітка дерев''яною рейкою', 'M2', 18),
  ('Обшивка орієнтовано-стружковою плитою (ОСП), фанерою', 'M2', 19),
  ('Обшивка стелі шалівкою (кроком від 40-60 см)', 'M2', 20),
  ('Укладання ламінату на стіну на обрешітку', 'M2', 21),
  ('Поклейка ламінату на стіну', 'M2', 22),
  ('Вирівнювання стін (зроблених не нами, за погодженням)', 'M2', 23),
  ('Дефектовка стін (зроблених не нами, за погодженням)', 'M2', 24),
  ('Ошкурення стін після шпаклівки (зробленої не нами, за погодженням)', 'M2', 25),
  ('Виведення кутів без стельового багета шпаклівкою', 'LINEAR_METER', 26),
  ('Виведення кутів пофарбуванням', 'LINEAR_METER', 27),
  ('Поклейка сітки в кути', 'LINEAR_METER', 28),
  ('Шпаклівка старт по цементній штукатурці', 'M2', 29),
  ('Шпаклівка стін під склополотно шпалери', 'M2', 30),
  ('Шпаклівка стелі під склополотно шпалери', 'M2', 31),
  ('Шпаклівка стелі під фарбування', 'M2', 32),
  ('Шпаклівка стін під фарбування', 'M2', 33),
  ('Шпаклівка коробів, укосів, ніш та виступів під фарбування', 'LINEAR_METER', 34),
  ('Поклейка склополотна', 'M2', 35),
  ('Поклейка шпалер шириною 100 см на стіну (без підбору)', 'M2', 36),
  ('Поклейка шпалер шириною 100 см на стіну (з підбором)', 'M2', 37),
  ('Поклейка шпалер шириною 50 см на стіну (без підбору)', 'M2', 38),
  ('Поклейка шпалер шириною 50 см на стіну (з підбором)', 'M2', 39),
  ('Поклейка фотошпалер', 'M2', 40),
  ('Фарбування грунт-фарбою', 'M2', 41),
  ('Фарбування стін водоемульсійною фарбою', 'M2', 42),
  ('Фарбування стелі водоемульсійною фарбою', 'M2', 43),
  ('Установка перфорованих кутів на укоси, кути', 'LINEAR_METER', 44),
  ('Монтаж стельових багетів (простих - з пінопласту і т.п.) до 8 см', 'LINEAR_METER', 45),
  ('Монтаж стельових багетів поліуретанових до 6см', 'LINEAR_METER', 46),
  ('Фарбування стельових багетів (простих - з пінопласту) до 8 см', 'LINEAR_METER', 47),
  ('Фарбування стельових багетів, молдінгів (поліуретанових) до 6 см', 'LINEAR_METER', 48),
  ('Монтаж молдінга з поліуретану', 'LINEAR_METER', 49),
  ('Вирівнювання пройми під підвіконня', 'LINEAR_METER', 50),
  ('Установка підвіконня з підготовкою', 'LINEAR_METER', 51),
  ('Заробка штроби під підвіконням', 'LINEAR_METER', 52),
  ('Монтаж віконного профіля з гумою', 'LINEAR_METER', 53),
  ('Герметизація швів, стиків акрилом, спеціальною мастикою', 'LINEAR_METER', 54),
  ('Фарбування дверей прихованого монтажу (підготовка і фарбування з двох сторін)', 'PIECE', 55),
  ('Опуск дверної пройми', 'PIECE', 56),
  ('Установка люка з драбиною на горище', 'PIECE', 57),
  ('Фарбування труб (газової, опалення і ін.)', 'LINEAR_METER', 58),
  ('Монтаж 3 Д панелей з підготовкою під фарбування', 'M2', 59),
  ('Фарбування 3 Д панелей з підготовкою', 'M2', 60)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Вагонка дерев''яна', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Обрешітка дерев''яною рейкою', 'M2', 0),
  ('Монтаж дерев''яної вагонки на стіну', 'M2', 1),
  ('Фарбування дерев''яної вагонки', 'M2', 2)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Венеціанська штукатурка декор', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Грунтовка поверхонь перед шпаклівкою фарбуванням', 'M2', 0),
  ('Шпаклівка стін під фарбування', 'M2', 1),
  ('Грунтовка поверхонь бетоноконтактом', 'M2', 2),
  ('Венеціанська штукатурка', 'M2', 3),
  ('Мармурова крихта', 'M2', 4)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'ДЕКОРАТИВНА ШТУКАТУРКА', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Нанесення декоративної штукатурки "китайський шовк", "вельвет", "лава", "сахара"', 'M2', 0),
  ('Нанесення декоративної штукатурки "гротто", "1000 ліній"', 'M2', 1),
  ('Нанесення декоративної штукатурки "травертин"', 'M2', 2),
  ('Нанесення декоративної штукатурки "ефект бетону", "арт бетон"', 'M2', 3),
  ('Нанесення декоративної штукатурки "мікробетон"', 'M2', 4),
  ('Нанесення декоративної штукатурки ферозит "мармурова крихта" - від', 'M2', 5)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Декоративна штукатурка', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Грунтовка поверхонь перед шпаклівкою фарбуванням', 'M2', 0),
  ('Шпаклівка стін під фарбування', 'M2', 1),
  ('Грунтовка поверхонь бетоноконтактом', 'M2', 2),
  ('Декоративна штукатурка китайський шовк вельвет лава сахара', 'M2', 3),
  ('Декоративна штукатурка травертин', 'M2', 4)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Звукоізоляція стін', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Звукоізоляція стін мінеральною ватою', 'M2', 0),
  ('Каркасна звукоізоляція ГКЛ два слоя стін', 'M2', 1),
  ('Герметизація швів стиків герметиком', 'LINEAR_METER', 2)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Кімната під ключ малярка', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Штукатурка стін машинкою до 2см', 'M2', 0),
  ('Виведення геометрії стін кути 90', 'LINEAR_METER', 1),
  ('Шпаклівка старт по цементній штукатурці', 'M2', 2),
  ('Шпаклівка стін під фарбування', 'M2', 3),
  ('Шпаклівка стелі під фарбування', 'M2', 4),
  ('Фарбування стін водоемульсійною фарбою', 'M2', 5),
  ('Фарбування стелі водоемульсійною фарбою', 'M2', 6)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Мікроцемент', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Ґрунтування основи під мікроцемент', 'M2', 0),
  ('Нанесення мікроцементу', 'M2', 1),
  ('Захисне лакування мікроцементу', 'M2', 2)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Стеля натяжна під ключ', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Грунтовка поверхонь перед шпаклівкою фарбуванням', 'M2', 0),
  ('Шпаклівка стелі під фарбування', 'M2', 1),
  ('Натяжна стеля', 'M2', 2)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Стеля під фарбування', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Грунтовка поверхонь перед шпаклівкою фарбуванням', 'M2', 0),
  ('Шпаклівка стелі під фарбування', 'M2', 1),
  ('Фарбування грунт-фарбою', 'M2', 2),
  ('Фарбування стелі водоемульсійною фарбою', 'M2', 3)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Стіни під фарбування', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Грунтовка поверхонь перед штукатуркою армуванням', 'M2', 0),
  ('Штукатурка стін машинкою до 2см', 'M2', 1),
  ('Виведення геометрії стін кути 90', 'LINEAR_METER', 2),
  ('Штукатурка укосів', 'LINEAR_METER', 3),
  ('Грунтовка поверхонь перед шпаклівкою фарбуванням', 'M2', 4),
  ('Шпаклівка старт по цементній штукатурці', 'M2', 5),
  ('Шпаклівка стін під фарбування', 'M2', 6),
  ('Фарбування грунт-фарбою', 'M2', 7),
  ('Фарбування стін водоемульсійною фарбою', 'M2', 8),
  -- extended (phases: full wall-prep cycle + finish coats, from the new price-list catalog)
  ('Шліфування штукатурки', 'M2', 9),
  ('Обезпилення поверхні', 'M2', 10),
  ('Грунтування', 'M2', 11),
  ('Базове шпаклювання під скловолокно', 'M2', 12),
  ('Шліфування під скловолокно/склохолст', 'M2', 13),
  ('Обезпилення поверхні', 'M2', 14),
  ('Армування стін скловолокном (склохолст)', 'M2', 15),
  ('Шпаклювання фінішне (2–4 рази)', 'M2', 16),
  ('Шліфування стін/стель (фінішне)', 'M2', 17),
  ('Обезпилення поверхні', 'M2', 18),
  ('Грунтування', 'M2', 19),
  ('Грунт-фарба (праймер під фарбу)', 'M2', 20),
  ('Фарбування стін/стель (білий)', 'M2', 21),
  ('Фарбування стін/стель (у кольорі)', 'M2', 22),
  ('Розділення кольорів (скотч)', 'LINEAR_METER', 23)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Стіни під шпалери', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Грунтовка поверхонь перед штукатуркою армуванням', 'M2', 0),
  ('Штукатурка стін машинкою до 2см', 'M2', 1),
  ('Виведення геометрії стін кути 90', 'LINEAR_METER', 2),
  ('Шпаклівка старт по цементній штукатурці', 'M2', 3),
  ('Шпаклівка стін під склополотно шпалери', 'M2', 4),
  ('Поклейка шпалер 100см з підбором', 'M2', 5)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Укоси вікон', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Штукатурка укосів', 'LINEAR_METER', 0),
  ('Установка перфорованих кутів на укоси, кути', 'LINEAR_METER', 1),
  ('Грунтовка поверхонь перед шпаклівкою фарбуванням', 'M2', 2),
  ('Фарбування стін водоемульсійною фарбою', 'M2', 3)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'ФАСАДНІ РОБОТИ', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Утеплення фасада. Комплекс (пінопласт, сітка, перетяжка, декор. штукатурка)', 'M2', 0),
  ('Утеплення фасада. Комплекс (вата, сітка, перетяжка, декор. штукатурка)', 'M2', 1),
  ('Монтаж пінопласта клей дюбель', 'M2', 2),
  ('Монтаж вати клей дюбель', 'M2', 3),
  ('Армування фасаду сітка перетяжка', 'M2', 4),
  ('Грунтовка поверхні кварцгрунтом', 'M2', 5),
  ('Декоративна штукатурка фасаду короїд баранець', 'M2', 6),
  ('Фарбування фасаду', 'M2', 7),
  ('Розділення та розмітка двох кольорів декор штукатурки', 'LINEAR_METER', 8),
  ('Утеплення укосів пінопластом', 'LINEAR_METER', 9),
  ('Утеплення укосів ватою', 'LINEAR_METER', 10),
  ('Армування укосів сітка перетяжка', 'LINEAR_METER', 11),
  ('Монтаж стартової планки', 'LINEAR_METER', 12),
  ('Утеплення цоколя пінополістиролом', 'LINEAR_METER', 13),
  ('Облицювання клінкерною плиткою', 'M2', 14),
  ('Облицювання фасаду натуральним або штучним каменем', 'M2', 15),
  ('Монтаж будівельного риштування', 'M2', 16),
  ('Демонтаж будівельного риштування', 'M2', 17),
  ('Монтаж відливів на вікна', 'LINEAR_METER', 18)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Фотошпалери акцент', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Грунтовка поверхонь перед шпаклівкою фарбуванням', 'M2', 0),
  ('Шпаклівка стін під склополотно шпалери', 'M2', 1),
  ('Поклейка фотошпалер', 'M2', 2)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'ШТУКАТУРКА', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Виконання робіт на висоті понад 3м', 'PERCENT', 0),
  ('Грунтовка поверхонь перед штукатуркою армуванням', 'M2', 1),
  ('Грунтовка поверхонь бетоноконтактом', 'M2', 2),
  ('Армування сіткою', 'M2', 3),
  ('Штукатурка стін (до 2 см)', 'M2', 4),
  ('Штукатурка стін (від 2 см)', 'M2', 5),
  ('Штукатурка стін (від 5 см)', 'M2', 6),
  ('Штукатурка стін до 2см об''ємом до 50м2', 'M2', 7),
  ('Штукатурка стін від 2см об''ємом до 50м2', 'M2', 8),
  ('Виведення геометрії стін кути 90', 'LINEAR_METER', 9),
  ('Штукатурка укосів - від', 'LINEAR_METER', 10),
  ('Штукатурка під стельовий профіль по периметру (натяжна стеля)', 'LINEAR_METER', 11),
  ('Шліфування стін після штукатурки (зробленої не нами)', 'M2', 12),
  -- extended (phase: Штукатурка та армування стін, from the new price-list catalog)
  ('Грунтування', 'M2', 13),
  ('Армування стін сіткою', 'M2', 14),
  ('Вирівнювання стін', 'M2', 15),
  ('Монтаж шпаклювальних кутиків', 'LINEAR_METER', 16),
  ('Монтаж віконного примикання', 'LINEAR_METER', 17),
  ('Закидання штраб (ел/сант)', 'LINEAR_METER', 18)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Шпаклівка фінішна', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Грунтовка поверхонь перед шпаклівкою фарбуванням', 'M2', 0),
  ('Шпаклівка старт по цементній штукатурці', 'M2', 1),
  ('Шпаклівка стін під фарбування', 'M2', 2),
  ('Шпаклівка стелі під фарбування', 'M2', 3)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Штукатурка стін механізована', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Грунтовка поверхонь перед штукатуркою армуванням', 'M2', 0),
  ('Машинна штукатурка стін', 'M2', 1),
  ('Виведення геометрії стін кути 90', 'LINEAR_METER', 2),
  ('Штукатурка укосів', 'LINEAR_METER', 3)
) AS v(name, unit, ord);

-- ---- Part 1c: the two genuinely new bundles, no existing template covered this scope ----------

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Захист і підготовка приміщення', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Обезпилення поверхні', 'M2', 0),
  ('Укривання підлоги плівкою', 'M2', 1),
  ('Обклеювання вікон/дверей плівкою', 'M2', 2),
  ('Поклейка профілю трекових світильників', 'LINEAR_METER', 3),
  ('Розділення кольорів (скотч)', 'LINEAR_METER', 4),
  ('Грунтування', 'M2', 5)
) AS v(name, unit, ord);

WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Приховані двері та тіньові шви', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Армування рами дверей прих. монтажу (сітка+американка+скловолокно)', 'LINEAR_METER', 0),
  ('Штукатурка дверей прихованого монтажу', 'LINEAR_METER', 1),
  ('Обклеювання рами дверей прих. монтажу', 'LINEAR_METER', 2),
  ('Шпаклювання довкола дверей/швів/дифузорів/треків/люків', 'LINEAR_METER', 3),
  ('Облаштування тіньового профілю', 'LINEAR_METER', 4),
  ('Фарбування дверей прих. монтажу (одна сторона)', 'PIECE', 5),
  ('Фарбування тіньових швів', 'LINEAR_METER', 6)
) AS v(name, unit, ord);

-- ---- Part 3a: organizational services, new catalog positions -----------------------------------

INSERT INTO catalog_templates (id, trade, name, type, unit, suggested_price, category, added_in_version) VALUES
(gen_random_uuid(), 'PAINTER', 'Винесення та вивезення будівельного сміття'      , 'WORK', 'M3'   , 0.00, 'Організаційні послуги', 11),
(gen_random_uuid(), 'PAINTER', 'Витратні матеріали'                              , 'WORK', 'PERCENT', 0.00, 'Організаційні послуги', 11),
(gen_random_uuid(), 'PAINTER', 'Виїзд для прорахунку вартості робіт та матеріалів', 'WORK', 'PIECE', 0.00, 'Організаційні послуги', 11),
(gen_random_uuid(), 'PAINTER', 'Виїзд майстра в магазин для підбору матеріалів'  , 'WORK', 'PIECE', 0.00, 'Організаційні послуги', 11),
(gen_random_uuid(), 'PAINTER', 'Виїзд спеціаліста для консультації'              , 'WORK', 'PIECE', 0.00, 'Організаційні послуги', 11),
(gen_random_uuid(), 'PAINTER', 'Гарантійний повторний виїзд'                     , 'WORK', 'PIECE', 0.00, 'Організаційні послуги', 11),
(gen_random_uuid(), 'PAINTER', 'Замір приміщення'                                , 'WORK', 'PIECE', 0.00, 'Організаційні послуги', 11),
(gen_random_uuid(), 'PAINTER', 'Прибирання приміщення після робіт'               , 'WORK', 'M2'   , 0.00, 'Організаційні послуги', 11),
(gen_random_uuid(), 'PAINTER', 'Підняття матеріалу по сходах'                    , 'WORK', 'T'    , 0.00, 'Організаційні послуги', 11),
(gen_random_uuid(), 'PAINTER', 'Розвантаження матеріалу'                         , 'WORK', 'T'    , 0.00, 'Організаційні послуги', 11),
(gen_random_uuid(), 'PAINTER', 'Транспортні витрати за містом'                   , 'WORK', 'KM'   , 0.00, 'Організаційні послуги', 11);

-- ---- Part 3b: the three always-billed ones, appended to every default bundle that lacks them ---

INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit,
       COALESCE((SELECT max(ti2.sort_order) + 1 FROM estimate_template_items ti2
                 WHERE ti2.template_id = t.id), 0)
         + row_number() OVER (PARTITION BY t.id ORDER BY v.ord) - 1
FROM estimate_templates t
CROSS JOIN (VALUES
  ('Прибирання приміщення після робіт', 'M2', 0),
  ('Винесення та вивезення будівельного сміття', 'M3', 1),
  ('Гарантійний повторний виїзд', 'PIECE', 2)
) AS v(name, unit, ord)
WHERE t.is_default AND t.trade = 'PAINTER'
  AND NOT EXISTS (
      SELECT 1 FROM estimate_template_items ti
      WHERE ti.template_id = t.id AND lower(trim(ti.name)) = lower(trim(v.name)));

-- ---- Part 3c + 2b: reach the one registered painter -----------------------------------------

DO $$
DECLARE
    v_added int;
BEGIN
    -- the 11 new organizational-service positions
    CREATE TEMP TABLE _added ON COMMIT DROP AS
    SELECT gen_random_uuid() AS id, t.user_id AS owner_id, ct.name, ct.type, ct.unit,
           ct.suggested_price AS default_price, ct.category
    FROM (SELECT DISTINCT user_id FROM user_trades WHERE trade = 'PAINTER') t
    CROSS JOIN catalog_templates ct
    WHERE ct.trade = 'PAINTER' AND ct.added_in_version = 11
      AND NOT EXISTS (
          SELECT 1 FROM catalog_items ci
          WHERE ci.owner_id = t.user_id
            AND lower(trim(ci.name)) = lower(trim(ct.name))
            AND ci.type = ct.type AND ci.unit = ct.unit);

    INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, category, trade, source)
    SELECT id, owner_id, name, type, unit, default_price, category, 'PAINTER', 'LIBRARY' FROM _added;
    GET DIAGNOSTICS v_added = ROW_COUNT;

    -- positions_removed = 22: the LINEAR_METER half of each †split pair, deleted from this same
    -- master's catalog earlier in this migration (Part 2). Fixed literally rather than re-counted
    -- here — that DELETE already ran, so there is nothing left in catalog_items to count against.
    INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed)
    SELECT gen_random_uuid(), owner_id, 'COUNT', count(*), 22
    FROM _added GROUP BY owner_id;

    UPDATE users u
    SET last_synced_catalog_version = (SELECT MAX(added_in_version) FROM catalog_templates)
    WHERE EXISTS (SELECT 1 FROM user_trades ut WHERE ut.user_id = u.id AND ut.trade = 'PAINTER');

    RAISE NOTICE 'V99 painter: +% org-service positions pushed, notices written', v_added;
END $$;
