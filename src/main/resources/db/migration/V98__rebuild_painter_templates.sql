-- PAINTER estimate templates, rebuilt on top of V96 — mirrors V84, unlike V96/V97 which were
-- additive: templates are curated bundles, not reference data a master might already be quoting
-- from, so the prompt asks for them "перегруповані по фазах" the same way V84 replaced tiling's.
--
-- The 19 that existed named the OLD, pre-V96 catalog by name and would price most lines at 0 after
-- the rebuild. They also did not follow any job sequence a painter actually works in. What
-- replaces them: 6 bundles ordered start-to-finish — protect the room, plaster and reinforce,
-- the full wall-prep cycle (the headline one, drawn from the source price list's 19-step routine),
-- finish coats, moldings/decor, and hidden-door/shadow-gap work as its own bundle (a distinct
-- enough skill that it earns a separate template rather than being folded into finishing).
--
-- Bundle lines carry name + unit and NO quantity and NO price: quantities are per object and the
-- price is substituted from the master's OWN catalog by name at apply-time
-- (EstimateTemplateService), same as tiling. A line repeated within one bundle (Обезпилення /
-- Грунтування appear three times in the wall-prep cycle) is not a bug — priming and dust removal
-- genuinely happen between several coats in that workflow, and each pass is its own billable line.
--
-- ============ WHAT IS NEVER TOUCHED ============================================
--   1. Templates a master saved themselves (is_default = false). The DELETE below is fenced on
--      is_default, so «зберегти кошторис як шаблон» output is untouched.
--   2. estimate_items — no estimate built from an old bundle changes. A template is a starting
--      point that is copied, not a live link.
-- ==============================================================================================
--
-- Every position name below is copied verbatim from V96 and cross-checked against it, because a
-- bundle line naming a position the catalog does not have silently prices at 0 (see
-- CatalogCleanupOnLegacyDataIntegrationTest for why an orphaned line is a warning, never a reason
-- to fail startup — but 0 unresolved is still the goal here).

DELETE FROM estimate_templates WHERE is_default AND trade = 'PAINTER';

-- 1. Захист і підготовка приміщення (6)
WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Захист і підготовка приміщення', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Обезпилення поверхні (м²)', 'M2', 0),
  ('Укривання підлоги плівкою', 'M2', 1),
  ('Обклеювання вікон/дверей плівкою', 'M2', 2),
  ('Поклейка профілю трекових світильників', 'LINEAR_METER', 3),
  ('Розділення кольорів (скотч)', 'LINEAR_METER', 4),
  ('Грунтування (м²)', 'M2', 5)
) AS v(name, unit, ord);

-- 2. Штукатурка та армування стін (6)
WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Штукатурка та армування стін', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Грунтування (м²)', 'M2', 0),
  ('Армування стін сіткою (м²)', 'M2', 1),
  ('Вирівнювання стін (м²)', 'M2', 2),
  ('Монтаж шпаклювальних кутиків', 'LINEAR_METER', 3),
  ('Монтаж віконного примикання', 'LINEAR_METER', 4),
  ('Закидання штраб (ел/сант)', 'LINEAR_METER', 5)
) AS v(name, unit, ord);

-- 3. Стіни під фарбування — повний цикл (13) — головний, з 19-крокового прайсу
WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Стіни під фарбування — повний цикл', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Шліфування штукатурки (м²)', 'M2', 0),
  ('Обезпилення поверхні (м²)', 'M2', 1),
  ('Грунтування (м²)', 'M2', 2),
  ('Базове шпаклювання під скловолокно (м²)', 'M2', 3),
  ('Шліфування під скловолокно/склохолст (м²)', 'M2', 4),
  ('Обезпилення поверхні (м²)', 'M2', 5),
  ('Армування стін скловолокном (склохолст) (м²)', 'M2', 6),
  ('Шпаклювання фінішне (2–4 рази) (м²)', 'M2', 7),
  ('Шліфування стін/стель (фінішне) (м²)', 'M2', 8),
  ('Обезпилення поверхні (м²)', 'M2', 9),
  ('Грунтування (м²)', 'M2', 10),
  ('Грунт-фарба (праймер під фарбу) (м²)', 'M2', 11),
  ('Фарбування стін/стель (білий) (м²)', 'M2', 12)
) AS v(name, unit, ord);

-- 4. Фінішне фарбування (4)
WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Фінішне фарбування', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Грунт-фарба (праймер під фарбу) (м²)', 'M2', 0),
  ('Фарбування стін/стель (білий) (м²)', 'M2', 1),
  ('Фарбування стін/стель (у кольорі) (м²)', 'M2', 2),
  ('Розділення кольорів (скотч)', 'LINEAR_METER', 3)
) AS v(name, unit, ord);

-- 5. Молдинги, багети, декор (5)
WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Молдинги, багети, декор', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Фарбування молдинга/багета до 6 см', 'LINEAR_METER', 0),
  ('Фарбування молдинга/багета 6–10 см', 'LINEAR_METER', 1),
  ('Фарбування молдинга/багета 10+ см', 'LINEAR_METER', 2),
  ('Фарбування плінтуса прихованого монтажу (перед монтажем)', 'LINEAR_METER', 3),
  ('Фарбування гіпсових світильників', 'PIECE', 4)
) AS v(name, unit, ord);

-- 6. Приховані двері та тіньові шви (7)
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
