-- PAINTER default bundles rebuilt as three ORDERED sequences (2026-08-23), from the three price
-- lists the master collected from working painters plus the numbered 1-19 cycle one of them wrote
-- out by hand.
--
-- ============ THE RULE THIS MIGRATION ESTABLISHES ==============================================
-- A template is a SEQUENCE, not a set: what is done after what, in the order a master actually
-- walks the object. The master's verdict on the old bundles was exactly this — «ВНУТРІШНЄ
-- ОЗДОБЛЕННЯ ПРИМІЩЕНЬ - це просто набір якихось незрозумілих позицій, без будь-якої
-- послідовності». And a bundle has to be worth reaching for: «коли буде заходити майстер на об'єкт
-- і йому треба буде шаблон з 3-х позицій, то він і кошторису на таке не складає». So: three big
-- ordered bundles instead of 21 mostly-tiny sets. (Documented in docs/architecture.md.)
--
-- This does NOT contradict V99's "краще трохи більші шаблони ніж купа маленьких" — it is the same
-- rule taken further. What V99 got wrong is what it kept: it restored the 19 pre-existing bundles
-- verbatim because they were "real curated work", but most of them carry 3-6 positions in no
-- particular order, which is the shape the master has now rejected outright.
--
-- ============ WHAT IS AND IS NOT TOUCHED =======================================================
--   * Only estimate_templates/estimate_template_items with is_default AND trade='PAINTER'.
--   * NOTHING is removed from the catalog — «ми чіпаємо тільки шаблони, з позицій нічого не
--     викидаємо». The ~216 PAINTER catalog positions stay exactly as V96/V99/V109 left them, and
--     no price is recomputed (the current ones are already medians of the same masters' lists —
--     re-medianing them would be a median of medians).
--   * A master's OWN templates (is_default = false) are not referenced at all.
--   * Фасадні роботи: the bundle goes, its positions stay in the catalog. Judgement call, stated
--     for the record — the three source price lists contain nothing facade-related, and the master
--     read the old bundle the same way («фасадні роботи тут думаю не мають місця взагалі - хз чого
--     ми їх сюди додали?»). A painter who does insulate a facade still has every position; what he
--     loses is a bundle nobody assembled on purpose. If facade work later earns a bundle it should
--     be built as its own sequence, not restored.
--
-- ============ NAMES MUST MATCH THE CATALOG CHARACTER-FOR-CHARACTER =============================
-- Template items carry no price: EstimateTemplateService resolves it at apply time from the
-- applying master's own catalog_items, joined on lower(trim(name)) (see V99 PART 2). A typo here
-- does not fail — it silently applies the line at 0 ₴. Every name below is copied from the live
-- catalog_templates PAINTER rows, and PART 4 asserts that all three bundles resolve.
--
-- ============ NEW CATALOG POSITIONS (2, added_in_version = 13) =================================
-- The three lists were otherwise already fully covered by V96/V99/V109.
--   1. «Армування врізних трекових світильників/вентиляційних дифузорів» 360 м.п. — genuinely
--      missing. The catalog reinforces shadow gaps, hidden-door frames and hatches but had nothing
--      for recessed track/diffuser cut-outs.
--   2. «Шпаклювання стін (старт, за потреби) до 60 см» 260 м.п. — the master asked for the start
--      putty as two positions, m² and running metre («150 м2 і 260 то м.п., типу 2 позиції»). It
--      cannot be the same name twice: lower(trim(name)) is the price-join AND the multi-template
--      dedup key, so a same-named pair collides and one half is dropped (the whole reason V99
--      deleted the "(м.п.)"-suffixed halves). The qualifier is therefore a SCOPE, taken from the
--      master's own note on the same sheet — «все, що до 60 см міряється в м.п.» — not a unit
--      spelled into the title, which stays forbidden.
-- Prices are the source's own numbers, untouched, per «по цінах то поки лишаємо як є».

-- ---- PART 1: the two new catalog positions ---------------------------------------------------

INSERT INTO catalog_templates (id, trade, name, type, unit, suggested_price, category, added_in_version) VALUES
(gen_random_uuid(), 'PAINTER', 'Армування врізних трекових світильників/вентиляційних дифузорів', 'WORK', 'LINEAR_METER', 360.00, 'Приховані двері, тіньові шви, треки, люки', 13),
(gen_random_uuid(), 'PAINTER', 'Шпаклювання стін (старт, за потреби) до 60 см'                  , 'WORK', 'LINEAR_METER', 260.00, 'Шпаклювання та шліфування', 13);

-- ---- PART 2: the 21 old default bundles go ---------------------------------------------------
-- estimate_template_items and template_trade_override both cascade off this.

DELETE FROM estimate_templates WHERE is_default AND trade = 'PAINTER';

-- ---- PART 3: three ordered bundles ------------------------------------------------------------

-- Bundle 1 — «Малярні роботи»: the full cycle from bare wall to paint, ordered along the master's
-- own numbered 1-19 list (шліфування → обезпилення → грунтовання → базове шпаклювання →
-- скловолокно → фініш → грунт-фарба → фарбування), with the third price list's remaining positions
-- folded into the stage each belongs to. Repeated stages (the cycle dusts and primes four times)
-- appear once — quantity is the master's to enter, the bundle is the running order.
WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Малярні роботи', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  -- демонтаж і захист
  ('Демонтажні роботи'                                    , 'M2'          ,  0),
  ('Демонтаж батарей'                                     , 'PIECE'       ,  1),
  ('Обклеювання приміщення (захист)'                      , 'M2'          ,  2),
  ('Закидання штраб (ел/сант)'                            , 'LINEAR_METER',  3),
  -- підготовка основи
  ('Шліфування штукатурки'                                , 'M2'          ,  4),
  ('Обезпилення поверхні'                                 , 'M2'          ,  5),
  ('Грунтування'                                          , 'M2'          ,  6),
  ('Грунтування кварцгрунтом'                             , 'M2'          ,  7),
  -- штукатурка та армування
  ('Штукатурні роботи (від)'                              , 'M2'          ,  8),
  ('Монтаж віконного примикання'                          , 'LINEAR_METER',  9),
  ('Армування стиків ГКЛ'                                 , 'LINEAR_METER', 10),
  ('Монтаж кутника внутрішнього/мет.'                     , 'LINEAR_METER', 11),
  ('Поклейка стрічки «американка»'                        , 'LINEAR_METER', 12),
  ('Армування фасадною сіткою'                            , 'M2'          , 13),
  ('Вирівнювання стін'                                    , 'M2'          , 14),
  -- скловолокно
  ('Базове шпаклювання під скловолокно'                   , 'M2'          , 15),
  ('Шліфування під скловолокно/склохолст'                 , 'M2'          , 16),
  ('Армування стін скловолокном (склохолст)'              , 'M2'          , 17),
  -- фініш
  ('Шпаклювання фінішне (2–4 рази)'                       , 'M2'          , 18),
  ('Шліфування стін/стель (фінішне)'                      , 'M2'          , 19),
  ('Акрилення примикань'                                  , 'LINEAR_METER', 20),
  -- фарбування
  ('Грунт-фарба (праймер під фарбу)'                      , 'M2'          , 21),
  ('Розділення кольорів (скотч)'                          , 'LINEAR_METER', 22),
  ('Фарбування стін/стель (білий)'                        , 'M2'          , 23),
  ('Фарбування стін/стель (у кольорі)'                    , 'M2'          , 24),
  -- елементи, що йдуть після фарбування
  ('Облаштування тіньового профілю'                       , 'LINEAR_METER', 25),
  ('Відтяжка'                                             , 'LINEAR_METER', 26)
) AS v(name, unit, ord);

-- Bundle 2 — «Шпаклювання»: the putty list, ordered демонтаж → штраби → грунт → вирівнювання →
-- кутики/примикання → приховані елементи (їх армують до того, як стіна піде під скловолокно) →
-- старт → скловолокно → фініш.
WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Шпаклювання', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  ('Демонтаж батарей'                                     , 'PIECE'       ,  0),
  ('Закидання штраб (ел/сант)'                            , 'LINEAR_METER',  1),
  ('Грунтування'                                          , 'M2'          ,  2),
  ('Вирівнювання стін'                                    , 'M2'          ,  3),
  -- кутики, примикання, сітка
  ('Монтаж віконного примикання'                          , 'LINEAR_METER',  4),
  ('Армування стиків ГКЛ'                                 , 'LINEAR_METER',  5),
  ('Монтаж шпаклювальних кутиків'                         , 'LINEAR_METER',  6),
  ('Монтаж шпаклювальних кутиків (арочних)'               , 'LINEAR_METER',  7),
  ('Монтаж кутника внутрішнього/мет.'                     , 'LINEAR_METER',  8),
  ('Армування стін сіткою'                                , 'M2'          ,  9),
  -- приховані двері, тіньові шви, треки
  ('Обклеювання тіньового шва перед монтажем'             , 'LINEAR_METER', 10),
  ('Армування тіньового шва/люків стрічкою'               , 'LINEAR_METER', 11),
  ('Армування врізних трекових світильників/вентиляційних дифузорів', 'LINEAR_METER', 12),
  ('Обклеювання рами дверей прих. монтажу'                , 'LINEAR_METER', 13),
  ('Армування рами дверей прих. монтажу (сітка+американка+скловолокно)', 'LINEAR_METER', 14),
  ('Штукатурка дверей прихованого монтажу'                , 'LINEAR_METER', 15),
  ('Зароблення швів на дверях/тіньових профілів'          , 'LINEAR_METER', 16),
  ('Шпаклювання довкола дверей/швів/дифузорів/треків/люків', 'LINEAR_METER', 17),
  -- старт і скловолокно
  ('Шпаклювання стін (старт, за потреби)'                 , 'M2'          , 18),
  ('Шпаклювання стін (старт, за потреби) до 60 см'        , 'LINEAR_METER', 19),
  ('Базове шпаклювання під скловолокно'                   , 'M2'          , 20),
  ('Шліфування під скловолокно/склохолст'                 , 'M2'          , 21),
  ('Армування стін скловолокном (склохолст)'              , 'M2'          , 22),
  ('Армування стель скловолокном'                         , 'M2'          , 23),
  -- фініш
  ('Шпаклювання фінішне (2–4 рази)'                       , 'M2'          , 24),
  ('Шпаклювання стелі'                                    , 'M2'          , 25),
  ('Шліфування стін/стель (фінішне)'                      , 'M2'          , 26)
) AS v(name, unit, ord);

-- Bundle 3 — «Фарбування»: the painting list, ordered захист → обклеювання елементів → обезпилення
-- і грунт → фарба → декоративні елементи (їх фарбують після площин) → погодинні дороботи.
WITH t AS (
  INSERT INTO estimate_templates (id, name, trade, is_default)
  VALUES (gen_random_uuid(), 'Фарбування', 'PAINTER', true) RETURNING id
)
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, 'WORK', v.unit, v.ord FROM t, (VALUES
  -- захист приміщення
  ('Обклеювання приміщення (захист)'                      , 'M2'          ,  0),
  ('Укривання підлоги картоном'                           , 'M2'          ,  1),
  ('Укривання підлоги плівкою'                            , 'M2'          ,  2),
  ('Укривання підлоги МДФ'                                , 'M2'          ,  3),
  ('Обклеювання вікон/дверей плівкою'                     , 'M2'          ,  4),
  -- обклеювання елементів і межі кольору
  ('Поклейка профілю трекових світильників'               , 'LINEAR_METER',  5),
  ('Поклейка повітряних дифузорів'                        , 'LINEAR_METER',  6),
  ('Обклеювання рами дверей прих. монтажу'                , 'LINEAR_METER',  7),
  ('Акрилення примикань'                                  , 'LINEAR_METER',  8),
  ('Відведення лінії фарби довкола вікон/дверей'          , 'M2'          ,  9),
  ('Розділення кольорів (скотч)'                          , 'LINEAR_METER', 10),
  -- підготовка під фарбу
  ('Пилосмоктання підлоги перед фарбуванням'              , 'M2'          , 11),
  ('Обезпилення поверхні'                                 , 'M2'          , 12),
  ('Грунтування'                                          , 'M2'          , 13),
  ('Грунт-фарба (праймер під фарбу)'                      , 'M2'          , 14),
  -- фарбування площин
  ('Фарбування стін/стель (білий)'                        , 'M2'          , 15),
  ('Фарбування стін/стель (у кольорі)'                    , 'M2'          , 16),
  ('Фарбування 3D панелей'                                , 'M2'          , 17),
  -- декоративні елементи
  ('Фарбування молдинга/багета до 6 см'                   , 'LINEAR_METER', 18),
  ('Фарбування молдинга/багета 6–10 см'                   , 'LINEAR_METER', 19),
  ('Фарбування молдинга/багета 10+ см'                    , 'LINEAR_METER', 20),
  ('Фарбування гіпсових світильників'                     , 'PIECE'       , 21),
  ('Фарбування тіньових швів'                             , 'LINEAR_METER', 22),
  ('Фарбування плінтуса прихованого монтажу (перед монтажем)', 'LINEAR_METER', 23),
  ('Фарбування дверей прих. монтажу (одна сторона)'       , 'PIECE'       , 24),
  ('Додаткові роботи'                                     , 'HOUR'        , 25)
) AS v(name, unit, ord);

-- The three always-billed organizational positions close every bundle, same rule as V99 Part 3b.
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

-- ---- PART 4: every bundle line must resolve against the catalog -------------------------------
-- An unresolved name applies at 0 ₴ instead of failing, so it has to fail here instead.

DO $$
DECLARE
    v_orphans text;
BEGIN
    SELECT string_agg(DISTINCT t.name || ' → ' || ti.name, '; ')
    INTO v_orphans
    FROM estimate_templates t
    JOIN estimate_template_items ti ON ti.template_id = t.id
    WHERE t.is_default AND t.trade = 'PAINTER'
      AND NOT EXISTS (
          SELECT 1 FROM catalog_templates ct
          WHERE ct.trade = 'PAINTER'
            AND lower(trim(ct.name)) = lower(trim(ti.name))
            AND ct.type = ti.type AND ct.unit = ti.unit);

    IF v_orphans IS NOT NULL THEN
        RAISE EXCEPTION 'V112: template lines with no catalog match: %', v_orphans;
    END IF;
END $$;

-- ---- PART 5: push the two new positions to registered painters --------------------------------
-- Same shape as V97/V99 Part 3c: keyed like ux_catalog_items_owner_name_type_unit, so an existing
-- row of the master's own blocks the insert rather than overwriting it.
--
-- Version 12 rides along: V109 added 14 catalog positions but shipped no push at all, so every
-- painter registered before it is still missing them. Leaving that alone while stamping
-- last_synced_catalog_version = 13 would make the stamp a lie, and the backfill is NOT EXISTS-
-- guarded like every other one, so it can only add what is genuinely absent.

DO $$
DECLARE
    v_added int;
BEGIN
    CREATE TEMP TABLE _added ON COMMIT DROP AS
    SELECT gen_random_uuid() AS id, t.user_id AS owner_id, ct.name, ct.type, ct.unit,
           ct.suggested_price AS default_price, ct.category
    FROM (SELECT DISTINCT user_id FROM user_trades WHERE trade = 'PAINTER') t
    CROSS JOIN catalog_templates ct
    WHERE ct.trade = 'PAINTER' AND ct.added_in_version IN (12, 13)
      AND NOT EXISTS (
          SELECT 1 FROM catalog_items ci
          WHERE ci.owner_id = t.user_id
            AND lower(trim(ci.name)) = lower(trim(ct.name))
            AND ci.type = ct.type AND ci.unit = ct.unit);

    INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, category, trade, source)
    SELECT id, owner_id, name, type, unit, default_price, category, 'PAINTER', 'LIBRARY' FROM _added;
    GET DIAGNOSTICS v_added = ROW_COUNT;

    INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed)
    SELECT gen_random_uuid(), owner_id, 'COUNT', count(*), 0
    FROM _added GROUP BY owner_id;

    UPDATE users u
    SET last_synced_catalog_version = (SELECT MAX(added_in_version) FROM catalog_templates)
    WHERE EXISTS (SELECT 1 FROM user_trades ut WHERE ut.user_id = u.id AND ut.trade = 'PAINTER');

    RAISE NOTICE 'V112 painter: 3 ordered bundles, +% catalog positions pushed', v_added;
END $$;
