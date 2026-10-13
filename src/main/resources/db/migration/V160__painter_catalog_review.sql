-- =================================================================================================
-- V160 — the trade-by-trade review, catalog pass: PAINTER (additions, renames, descriptions)
--
-- Source: `C:\Work\prompts\trade-review\03-PAINTER.md` §1, re-read against the V159 library. V116 and
-- V148 already shipped five of the report's additions (wallpaper stripping, whitewash washing, the Q4
-- local defects / micro-sanding / wet dusting). Owner ruling (2026-10-13): go on the recommendations,
-- never break a master. Shapes: V158's header and docs/iteration-trades-review-catalog.md.
--
-- WHAT THIS DOES
--   1. Eight positions, each with a norm copied from the sibling the report named: cement-lime
--      plaster (THICKNESS, the local-repair figure 1,4 kg/m²/mm, suggested 20 mm), structural glass
--      wallpaper (glass fabric 1,1 m²/m² + 0,3 kg/m² glue — heavier than the cobweb's 0,25), painting
--      paintable wallpaper and painting in wet rooms (the coloured-wall figure 0,22 l/m²), a radiator
--      per SECTION (V155's metal enamel 0,20 kg/m² over ≈0,25 m² a section → 0,05 kg), metal with
--      enamel (0,20 kg/m²), wood with stain or varnish (V138's clear varnish 0,20 l/m²), and an
--      anti-mould treatment (unnormed: no source for the product's spread).
--   2. Twelve renames: the primer steps (each stage of priming needs its own name, V112), the start
--      levelling that «Вирівнювання стін» never said, the over-5-cm plaster, a brand (Ferozit) and a
--      «- від» out of names, the membrane, the abbreviations «/мет.» and «прих.», the sheathing that is
--      a batten grid, and «Демонтажні роботи» said to be by agreement.
--   3. Two category moves (3D panels are not a door; the loft hatch is adjacent work).
--   4. Client descriptions for the report's key positions.
--
-- NOT DONE HERE: the ~30 duplicates (two wordings of one job) — removing them from the library goes
-- against V112's «з позицій нічого не викидаємо», so it waits for the owner's word with the other
-- trades' duplicates; «Відведення лінії фарби» M2 → м.п. (a unit change cannot reach a master's copy);
-- the framed sound insulation, the bark/«баранець» split and the plinth insulation (rows shared with
-- DRYWALL / BUILDER); «Відтяжка» and «Опуск дверної пройми» (their meaning is the owner's to say);
-- «Шпаклівка стін/стелі під фарбування» (what the price includes is the owner's to say).
--
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

CREATE TEMP TABLE _pos (trade varchar(50), category varchar(255), name varchar(255), unit varchar(20),
                        price numeric(15,2), description varchar(500)) ON COMMIT DROP;
CREATE TEMP TABLE _ren (trade varchar(50), old_name varchar(255), new_name varchar(255),
                        unit varchar(20)) ON COMMIT DROP;
CREATE TEMP TABLE _desc (trade varchar(50), name varchar(255), unit varchar(20),
                         description varchar(500)) ON COMMIT DROP;
CREATE TEMP TABLE _cat (trade varchar(50), name varchar(255), unit varchar(20),
                        new_category varchar(255)) ON COMMIT DROP;

INSERT INTO _pos VALUES
    ('PAINTER', 'Штукатурка', 'Штукатурка стін цементно-вапняною сумішшю (санвузли, укоси, фасади)', 'M2', 400.00,
     'Вирівнювання стін цементно-вапняною штукатуркою по маяках — для вологих приміщень, укосів і фасадів, де гіпс не застосовують.'),
    ('PAINTER', 'Шпалери', 'Поклейка склошпалер (структурних) під фарбування', 'M2', 190.00,
     'Склошпалери з фактурою («ялинка», «рогожка») на спеціальний клей; після висихання — фарбування окремою позицією.'),
    ('PAINTER', 'Фарбування', 'Фарбування шпалер під фарбування (флізелін, склошпалери)', 'M2', 170.00,
     'Фарбування флізелінових шпалер чи склошпалер під фарбування у 2 шари.'),
    ('PAINTER', 'Фарбування', 'Фарбування стін у вологих приміщеннях фарбою, стійкою до миття', 'M2', 190.00,
     'Кухня, санвузол: фарба, стійка до вологи й миття, у 2 шари по підготовленій поверхні.'),
    ('PAINTER', 'Фарбування', 'Фарбування радіатора опалення (за секцію)', 'PIECE', 80.00,
     'Зачистка й фарбування секції радіатора емаллю для металу. Ціна за одну секцію.'),
    ('PAINTER', 'Фарбування', 'Фарбування металевих поверхонь емаллю (решітки, поручні, металеві двері)', 'M2', 230.00,
     'Фарбування металу емаллю у 2 шари по ґрунту або емаллю «3 в 1» по іржі.'),
    ('PAINTER', 'Вагонка', 'Покриття дерев''яних поверхонь лазур''ю або лаком у 2 шари', 'M2', 180.00,
     'Захисне покриття вагонки чи іншого дерева лазур''ю або прозорим лаком у 2 шари.'),
    ('PAINTER', 'Підготовка', 'Антисептична обробка поверхні від цвілі', 'M2', 40.00,
     'Обробка антисептиком від цвілі й грибка — санвузли, кути зовнішніх стін — перед ґрунтуванням.');

INSERT INTO _ren VALUES
    ('PAINTER', 'Грунтовка поверхонь перед шпаклівкою фарбуванням',  'Грунтування перед шпаклівкою',                             'M2'),
    ('PAINTER', 'Грунтовка поверхонь перед штукатуркою армуванням',   'Грунтування основи перед штукатуркою, армуванням',         'M2'),
    ('PAINTER', 'Вирівнювання стін',                                  'Вирівнювання стін стартовою шпаклівкою по площині',        'M2'),
    ('PAINTER', 'Штукатурка стін ручна від 5см',                      'Штукатурка стін ручна понад 5 см (у кілька шарів, по сітці)', 'M2'),
    ('PAINTER', 'Нанесення декоративної штукатурки ферозит "мармурова крихта" - від', 'Нанесення декоративної штукатурки "мармурова крихта"', 'M2'),
    ('PAINTER', 'Штукатурні роботи (від)',                            'Штукатурні роботи',                                        'M2'),
    ('PAINTER', 'Паробар''єр гідробар''єр',                            'Монтаж пароізоляційної або гідроізоляційної мембрани',     'M2'),
    ('PAINTER', 'Монтаж кутника внутрішнього/мет.',                   'Монтаж внутрішнього металевого кутника',                   'LINEAR_METER'),
    ('PAINTER', 'Армування рами дверей прих. монтажу (сітка+американка+скловолокно)', 'Армування рами дверей прихованого монтажу (сітка, стрічка «американка», скловолокно)', 'LINEAR_METER'),
    ('PAINTER', 'Обклеювання рами дверей прих. монтажу',              'Обклеювання рами дверей прихованого монтажу',              'LINEAR_METER'),
    ('PAINTER', 'Фарбування дверей прих. монтажу (одна сторона)',     'Фарбування дверей прихованого монтажу (одна сторона)',     'PIECE'),
    ('PAINTER', 'Обшивка стелі шалівкою (кроком від 40-60 см)',       'Монтаж обрешітки стелі з дошки (крок 40–60 см)',           'M2'),
    ('PAINTER', 'Демонтажні роботи',                                  'Демонтажні роботи (за погодженням)',                       'M2');

INSERT INTO _cat VALUES
    ('PAINTER', 'Монтаж 3Д панелей',                    'M2',    'Оздоблення'),
    ('PAINTER', 'Установка люка з драбиною на горище',  'PIECE', 'Суміжні роботи');

INSERT INTO _desc VALUES
    ('PAINTER', 'Шпаклювання фінішне (2–4 рази)', 'M2',
     'Фінішна шпаклівка у 2–4 тонкі проходи з проміжним шліфуванням до гладкої площини під фарбування; кількість проходів залежить від класу якості (Q3/Q4) і бокового світла.'),
    ('PAINTER', 'Армування стін скловолокном (склохолст)', 'M2',
     'Поклейка склополотна (павутинки) на клей для армування площини від дрібних тріщин; після висихання — фінішна шпаклівка.'),
    ('PAINTER', 'Базове шпаклювання під скловолокно', 'M2',
     'Суцільний шар шпаклівки для вирівнювання площини перед поклейкою склополотна.'),
    ('PAINTER', 'Грунтування перед шпаклівкою', 'M2',
     'Ґрунтовка глибокого проникнення в 1 шар (на сильно вбирних основах — 2) для зміцнення основи та зчеплення шпаклівки.'),
    ('PAINTER', 'Грунтування основи перед штукатуркою, армуванням', 'M2',
     'Ґрунтовка глибокого проникнення по цеглі, газобетону чи старій штукатурці перед штукатуренням. Гладкий бетон — бетоноконтактом окремою позицією.'),
    ('PAINTER', 'Грунт-фарба (праймер під фарбу)', 'M2',
     'Пігментований ґрунт під фінішну фарбу: вирівнює вбирність і колір основи, зменшує витрату фарби.'),
    ('PAINTER', 'Грунтування кварцгрунтом', 'M2',
     'Ґрунт-фарба з кварцовим піском — шорстка адгезійна основа під декоративну штукатурку або гіпс по бетону.'),
    ('PAINTER', 'Штукатурка стін ручна до 2см', 'M2',
     'Вирівнювання стін гіпсовою штукатуркою по маяках при середній товщині шару до 2 см.'),
    ('PAINTER', 'Штукатурка стін ручна від 2см', 'M2',
     'Вирівнювання стін гіпсовою штукатуркою по маяках при середній товщині шару 2–5 см.'),
    ('PAINTER', 'Штукатурка стін ручна понад 5 см (у кілька шарів, по сітці)', 'M2',
     'Вирівнювання дуже кривих стін гіпсовою штукатуркою у кілька шарів по армувальній сітці.'),
    ('PAINTER', 'Машинна штукатурка стін', 'M2',
     'Нанесення гіпсової штукатурки штукатурною станцією по маяках з вирівнюванням і затиранням.'),
    ('PAINTER', 'Машинна штукатурка стель', 'M2',
     'Нанесення гіпсової штукатурки на стелю штукатурною станцією по маяках з вирівнюванням і затиранням.'),
    ('PAINTER', 'Фарбування стін/стель (білий)', 'M2',
     'Фарбування водно-дисперсійною фарбою у 2 шари валиком по підготовленій та проґрунтованій поверхні.'),
    ('PAINTER', 'Фарбування стін/стель (у кольорі)', 'M2',
     'Фарбування водно-дисперсійною фарбою в кольорі у 2 шари валиком по підготовленій та проґрунтованій поверхні.'),
    ('PAINTER', 'Фарбування укосів', 'LINEAR_METER',
     'Фарбування віконних чи дверних укосів у 2 шари; ціна — за метр периметра укосу.'),
    ('PAINTER', 'Поклейка шпалер шириною 50 см на стіну (з підбором)', 'M2',
     'Поклейка шпалер встик з підгонкою малюнка: «з підбором» — коли малюнок треба суміщати (більше обрізків і часу).'),
    ('PAINTER', 'Поклейка шпалер шириною 100 см на стіну (з підбором)', 'M2',
     'Поклейка шпалер встик з підгонкою малюнка: «з підбором» — коли малюнок треба суміщати (більше обрізків і часу).'),
    ('PAINTER', 'Виконання робіт на висоті понад 3м', 'PERCENT',
     'Надбавка до вартості робіт, які виконуються з риштування або на висоті понад 3 м.'),
    ('PAINTER', 'Демонтажні роботи (за погодженням)', 'M2',
     'Демонтаж, обсяг і склад якого узгоджуються з клієнтом на об''єкті.');

-- -------------------------------------------------------------------------------------------------
-- 0. Preconditions: each row is where we think it is, so nothing below lands on a guess.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE v_bad text;
BEGIN
    SELECT string_agg(r.old_name, '; ') INTO v_bad FROM _ren r
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct
                        WHERE ct.trade = r.trade AND ct.type = 'WORK' AND ct.unit = r.unit
                          AND lower(trim(ct.name)) = lower(trim(r.old_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V160: renamed position(s) not in the library: %', v_bad;
    END IF;

    -- A name another trade ships too would be split in two by a one-trade rename.
    SELECT string_agg(r.old_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.trade <> r.trade AND ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.old_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V160: renamed position(s) are shipped by another trade too: %', v_bad;
    END IF;

    SELECT string_agg(r.new_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.new_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V160: a new name is already in the library: %', v_bad;
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 1. Renames (see the header for why each statement is here).
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
SELECT gen_random_uuid(), n.owner_id, n.trade,
       lower(btrim(replace(replace(regexp_replace(r.new_name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))),
       n.unit, n.material_id, n.qty_per_unit, n.waste_percent, n.sort_order, n.basis,
       n.default_param, n.baseline_param
  FROM _ren r
  JOIN material_norm n
    ON n.unit = r.unit
   AND n.name_key = lower(btrim(replace(replace(regexp_replace(r.old_name, '\s+', ' ', 'g'), '( ', '('), ' )', ')')))
 WHERE NOT EXISTS (
        SELECT 1 FROM material_norm x
         WHERE x.owner_id IS NOT DISTINCT FROM n.owner_id
           AND x.trade IS NOT DISTINCT FROM n.trade
           AND x.unit = n.unit
           AND x.material_id IS NOT DISTINCT FROM n.material_id
           AND x.name_key = lower(btrim(replace(replace(regexp_replace(r.new_name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))));

-- The rename is RECORDED: the old key keeps its norms on purpose (an estimate written before today),
-- and MaterialCalculatorIntegrationTest reads this table to tell a kept key from a lost one.
INSERT INTO catalog_renamed_position (old_name_key, new_name_key, unit, renamed_in)
SELECT lower(btrim(replace(replace(regexp_replace(r.old_name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))),
       lower(btrim(replace(replace(regexp_replace(r.new_name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))),
       r.unit, 'V160'
  FROM _ren r
ON CONFLICT DO NOTHING;

-- -------------------------------------------------------------------------------------------------
-- 1b. Category moves: the library row, and a master's LIBRARY copy while it still sits in the
--     library's old category (a category he chose is his).
-- -------------------------------------------------------------------------------------------------
UPDATE catalog_items ci
   SET category = c.new_category
  FROM _cat c
 WHERE ci.source = 'LIBRARY' AND ci.trade = c.trade AND ci.type = 'WORK' AND ci.unit = c.unit
   AND lower(trim(ci.name)) = lower(trim(c.name))
   AND ci.category IS NOT DISTINCT FROM (SELECT ct.category FROM catalog_templates ct
                                          WHERE ct.trade = c.trade AND ct.type = 'WORK' AND ct.unit = c.unit
                                            AND lower(trim(ct.name)) = lower(trim(c.name)));

UPDATE catalog_templates ct
   SET category = c.new_category
  FROM _cat c
 WHERE ct.trade = c.trade AND ct.type = 'WORK' AND ct.unit = c.unit
   AND lower(trim(ct.name)) = lower(trim(c.name));

-- -------------------------------------------------------------------------------------------------
-- 2. Descriptions: the library row, and a master's LIBRARY copy only while it still says what the
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
-- 3. New positions, pushed into the catalogs of masters with the trade (V148's shape).
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    v_version int := (SELECT COALESCE(MAX(added_in_version), 0) + 1 FROM catalog_templates);
    v_added   int;
BEGIN
    INSERT INTO catalog_templates (id, trade, category, name, type, unit,
                                   suggested_price, added_in_version, description)
    SELECT gen_random_uuid(), p.trade, p.category, p.name, 'WORK', p.unit, p.price, v_version, p.description
      FROM _pos p
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct
                        WHERE ct.trade = p.trade AND lower(trim(ct.name)) = lower(trim(p.name))
                          AND ct.type = 'WORK' AND ct.unit = p.unit);

    CREATE TEMP TABLE _added ON COMMIT DROP AS
    SELECT DISTINCT ON (ut.user_id, lower(trim(ct.name)), ct.unit)
           gen_random_uuid() AS id, ut.user_id AS owner_id, ct.id AS template_id
      FROM user_trades ut
      JOIN catalog_templates ct ON ct.trade = ut.trade AND ct.added_in_version = v_version
     WHERE NOT EXISTS (SELECT 1 FROM catalog_items ci
                        WHERE ci.owner_id = ut.user_id
                          AND lower(trim(ci.name)) = lower(trim(ct.name))
                          AND ci.type = ct.type AND ci.unit = ct.unit)
     ORDER BY ut.user_id, lower(trim(ct.name)), ct.unit,
              array_position(ARRAY['ELECTRICAL','PLUMBING','TILING','BUILDER','PAINTER',
                                   'DRYWALL','FLOORING','DEMOLITION','METAL','GENERAL','OTHER'],
                             ct.trade::text);

    INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price,
                               category, trade, source, description)
    SELECT a.id, a.owner_id, ct.name, ct.type, ct.unit, ct.suggested_price,
           ct.category, ct.trade, 'LIBRARY', ct.description
      FROM _added a JOIN catalog_templates ct ON ct.id = a.template_id;
    GET DIAGNOSTICS v_added = ROW_COUNT;

    UPDATE catalog_update_notices n
       SET positions_added = n.positions_added + c.added
      FROM (SELECT owner_id, count(*) AS added FROM _added GROUP BY owner_id) c
     WHERE n.user_id = c.owner_id AND n.kind = 'COUNT' AND n.dismissed_at IS NULL;

    INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed)
    SELECT gen_random_uuid(), c.owner_id, 'COUNT', c.added, 0
      FROM (SELECT owner_id, count(*) AS added FROM _added GROUP BY owner_id) c
     WHERE NOT EXISTS (SELECT 1 FROM catalog_update_notices n
                        WHERE n.user_id = c.owner_id AND n.kind = 'COUNT' AND n.dismissed_at IS NULL);

    UPDATE users u
       SET last_synced_catalog_version = (SELECT MAX(added_in_version) FROM catalog_templates)
     WHERE EXISTS (SELECT 1 FROM user_trades ut
                    WHERE ut.user_id = u.id AND ut.trade IN (SELECT DISTINCT trade FROM _pos));

    RAISE NOTICE 'V160: catalog version %, % position(s) pushed into masters'' catalogs', v_version, v_added;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 4. RE-RANK, exactly as V118 PART 3/4 did (V120's rule: any migration that INSERTs a template).
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
-- 5. Self-checks, each about THIS migration's own work (review B-50).
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE v_bad text;
BEGIN
    -- No default bundle line is left on an old name, and every one resolves in the library.
    SELECT string_agg(DISTINCT i.name, '; ') INTO v_bad
      FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
      JOIN _ren r ON lower(trim(i.name)) = lower(trim(r.old_name)) AND i.unit = r.unit
     WHERE t.owner_id IS NULL AND t.is_default;
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V160: default bundle lines still on an old name: %', v_bad;
    END IF;

    -- Every norm the old key had now exists on the new key too (shipped rows).
    SELECT string_agg(DISTINCT r.new_name, '; ') INTO v_bad
      FROM _ren r
      JOIN material_norm n ON n.owner_id IS NULL AND n.unit = r.unit
       AND n.name_key = lower(btrim(replace(replace(regexp_replace(r.old_name, '\s+', ' ', 'g'), '( ', '('), ' )', ')')))
     WHERE NOT EXISTS (SELECT 1 FROM material_norm x
                        WHERE x.owner_id IS NULL AND x.unit = r.unit
                          AND x.material_id IS NOT DISTINCT FROM n.material_id
                          AND x.name_key = lower(btrim(replace(replace(regexp_replace(r.new_name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V160: renamed position(s) lost a norm: %', v_bad;
    END IF;

    -- No master's LIBRARY copy still carries an old name unless he also owns the new one.
    SELECT string_agg(DISTINCT ci.name, '; ') INTO v_bad
      FROM catalog_items ci JOIN _ren r
        ON lower(trim(ci.name)) = lower(trim(r.old_name)) AND ci.unit = r.unit AND ci.type = 'WORK'
     WHERE ci.source = 'LIBRARY'
       AND NOT EXISTS (SELECT 1 FROM catalog_items x
                        WHERE x.owner_id = ci.owner_id AND x.type = 'WORK' AND x.unit = r.unit
                          AND lower(trim(x.name)) = lower(trim(r.new_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V160: a master''s library copy was left on an old name: %', v_bad;
    END IF;

    SELECT string_agg(d.name, '; ') INTO v_bad FROM _desc d
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct
                        WHERE ct.trade = d.trade AND ct.unit = d.unit
                          AND lower(trim(ct.name)) = lower(trim(d.name))
                          AND ct.description = d.description);
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V160: description(s) did not land: %', v_bad;
    END IF;

END $$;

-- -------------------------------------------------------------------------------------------------
-- 6. Norms for the new positions (see the header for each figure's sibling).
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order, default_param)
SELECT gen_random_uuid(), 'PAINTER', v.name_key, v.unit, m.id, v.qty, v.basis, v.ord, v.def
  FROM (VALUES
    ('штукатурка стін цементно-вапняною сумішшю (санвузли, укоси, фасади)', 'M2', 'PLASTER_CEMENT_LIME', 1.4::numeric, 'THICKNESS', 1, 20::numeric),
    ('поклейка склошпалер (структурних) під фарбування', 'M2', 'FIBERGLASS',      1.1,  'QUANTITY', 1, NULL),
    ('поклейка склошпалер (структурних) під фарбування', 'M2', 'FIBERGLASS_GLUE', 0.3,  'QUANTITY', 2, NULL),
    ('фарбування шпалер під фарбування (флізелін, склошпалери)', 'M2', 'PAINT_INTERIOR', 0.22, 'QUANTITY', 1, NULL),
    ('фарбування стін у вологих приміщеннях фарбою, стійкою до миття', 'M2', 'PAINT_INTERIOR', 0.22, 'QUANTITY', 1, NULL),
    ('фарбування радіатора опалення (за секцію)', 'PIECE', 'ENAMEL_METAL', 0.05, 'QUANTITY', 1, NULL),
    ('фарбування металевих поверхонь емаллю (решітки, поручні, металеві двері)', 'M2', 'ENAMEL_METAL', 0.20, 'QUANTITY', 1, NULL),
    ('покриття дерев''яних поверхонь лазур''ю або лаком у 2 шари', 'M2', 'VARNISH_CLEAR', 0.20, 'QUANTITY', 1, NULL)
  ) AS v(name_key, unit, code, qty, basis, ord, def)
  JOIN material m ON m.code = v.code;

-- «Вирівнювання стін» never said what it levelled with, so it bought nothing; its new name says start
-- putty, and the start-putty position's own norm is what it buys.
INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id, qty_per_unit,
                           waste_percent, sort_order, basis, default_param, baseline_param)
SELECT gen_random_uuid(), NULL, n.trade, 'вирівнювання стін стартовою шпаклівкою по площині', n.unit,
       n.material_id, n.qty_per_unit, n.waste_percent, n.sort_order, n.basis, n.default_param, n.baseline_param
  FROM material_norm n
 WHERE n.owner_id IS NULL AND n.name_key = 'шпаклювання стін (старт, за потреби)' AND n.unit = 'M2'
   AND NOT EXISTS (SELECT 1 FROM material_norm x WHERE x.owner_id IS NULL
                     AND x.name_key = 'вирівнювання стін стартовою шпаклівкою по площині');

DO $$
BEGIN
    IF (SELECT count(DISTINCT name_key) FROM material_norm WHERE owner_id IS NULL AND trade = 'PAINTER'
          AND name_key IN ('штукатурка стін цементно-вапняною сумішшю (санвузли, укоси, фасади)',
                           'поклейка склошпалер (структурних) під фарбування',
                           'фарбування шпалер під фарбування (флізелін, склошпалери)',
                           'фарбування стін у вологих приміщеннях фарбою, стійкою до миття',
                           'фарбування радіатора опалення (за секцію)',
                           'фарбування металевих поверхонь емаллю (решітки, поручні, металеві двері)',
                           'покриття дерев''яних поверхонь лазур''ю або лаком у 2 шари',
                           'вирівнювання стін стартовою шпаклівкою по площині')) <> 8 THEN
        RAISE EXCEPTION 'V160: the new positions'' norms did not land whole';
    END IF;
END $$;
