-- =================================================================================================
-- V163 — the trade-by-trade review, catalog pass: ELECTRICAL (additions, renames, prices,
--        descriptions)
--
-- Source: `C:\Work\prompts\trade-review\06-ELECTRICAL.md` §1, re-read against the V162 library. V148
-- shipped 27 of the report's 32 additions. Owner ruling (2026-10-13): go on the recommendations, never
-- break a master. Shapes: V158's header and docs/iteration-trades-review-catalog.md; prices: V157's.
--
-- WHAT THIS DOES
--   1. Five positions: a hollow-wall box in plasterboard (new `BOX_HOLLOW`, one per piece), a cable
--      tray (new `CABLE_TRAY`, 1 m/m), panel assembly per module, a dedicated air-conditioner line, and
--      heating cable in a screed (the last three unnormed — the cable length and the kit follow the
--      job, as V153 left mats and lines).
--   2. Seventeen renames: the gypsum-board «штроблення» that cuts no chase, the built-in trunking, the
--      chase filling as its result, the cable in conduit by cross-section (the boundary V153's
--      copper / power norms already draw), the neutral bars as an action, the LED «трансформатор» as a
--      power supply, the motion sensor, and «трьох-/чотирьох-/двух-полюсний» → «три-/чотири-/дво-».
--   3. Category moves: chases into «Штроблення», temporary lighting into «Підготовка», the TV hatch
--      into «Слаботочка», the cooktop / plug / boiler into «Підключення техніки», the stabiliser into
--      «Щит», the sensor into «Розумний дім».
--   4. Prices that are placeholders (V157's shape — the library row and a PRICE_DRIFT notice, never
--      his price): surface panels for 48-180 modules at 120 ₴ while 12-36 is 800 (→ 1200 / 1600 /
--      2500), 3P / 4P / change-over 4P / RCBO breakers at 270 ₴ below the 2P's 440-600 (→ 500 / 600 /
--      700 / 500), and TV cable at 210 ₴/м beside twisted pair at ~55 (→ 60).
--   5. Client descriptions for the report's key positions.
--
-- NOT DONE, on purpose: the ~30 duplicates (with the other trades', for the owner's word); the UPS
-- POINT → PIECE unit (a unit change cannot reach a master's copy); the 50×250 chase and the bare
-- «Прокладка кабелю» / trunking prices (what they are is the owner's to say); the chase sizes' order
-- (concrete «40х20» vs brick «20х40» — which is width cannot be read off the rows).
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
    ('ELECTRICAL', 'Підрозетники', 'Монтаж підрозетника в гіпсокартон (порожниста стіна)', 'PIECE', 75.00,
     'Підрозетник для порожнистих стін на розпірних лапках у готовий отвір у ГКЛ, без гіпсу.'),
    ('ELECTRICAL', 'Кабель', 'Монтаж кабельного лотка', 'LINEAR_METER', 200.00,
     'Металевий або пластиковий лоток для кабелів — технічні приміщення, вводи, будинки.'),
    ('ELECTRICAL', 'Щит', 'Збірка та комутація електрощита, за 1 модуль', 'PIECE', 200.00,
     'Встановлення й комутація модульного обладнання щита з розрахунку за модуль. Вартість пристроїв — окремо.'),
    ('ELECTRICAL', 'Підключення техніки', 'Прокладання окремої лінії під кондиціонер з підключенням', 'PIECE', 1100.00,
     'Окрема лінія від щита до кондиціонера з автоматом і підключенням.'),
    ('ELECTRICAL', 'Тепла підлога', 'Монтаж нагрівального кабелю в стяжку', 'M2', 520.00,
     'Нагрівальний кабель на монтажній стрічці під стяжку, вимірювання опору до і після укладання. Стяжка — окремо.');

INSERT INTO _ren VALUES
    ('ELECTRICAL', 'Штроблення під електрокабель в гіпсокартоні', 'Прорізання отворів і каналів у гіпсокартоні під кабель', 'LINEAR_METER'),
    ('ELECTRICAL', 'Штроблення під кабель канал',                 'Штроблення під прихований (вбудований) кабель-канал',     'LINEAR_METER'),
    ('ELECTRICAL', 'Заробка штроб',                               'Закладення штроб гіпсовою сумішшю',                       'LINEAR_METER'),
    ('ELECTRICAL', 'Прокладка кабелю в гофрі',                    'Прокладка кабелю перерізом до 2,5 мм² в гофрованій трубі', 'LINEAR_METER'),
    ('ELECTRICAL', 'Прокладка силового кабелю в гофрі трубі',     'Прокладка силового кабелю перерізом від 4 мм² в гофрованій трубі', 'LINEAR_METER'),
    ('ELECTRICAL', 'Нульові шини',                                'Установка нульової (PE/N) шини в щит',                     'PIECE'),
    ('ELECTRICAL', 'Установка та підключення трансформатора',     'Установка блока живлення світлодіодної стрічки',          'PIECE'),
    ('ELECTRICAL', 'Установка датчиків руху датчиків світла',     'Установка датчика руху або освітленості з підключенням',  'PIECE'),
    ('ELECTRICAL', 'Установка автомата трьохполюсного',           'Установка автомата триполюсного',                         'PIECE'),
    ('ELECTRICAL', 'Установка автомата чотирьохполюсного',        'Установка автомата чотириполюсного',                      'PIECE'),
    ('ELECTRICAL', 'Установка автомата перекидного чотирьохполюсного', 'Установка автомата перекидного чотириполюсного',     'PIECE'),
    ('ELECTRICAL', 'Установка реле перекидного чотирьохполюсного', 'Установка реле перекидного чотириполюсного',             'PIECE'),
    ('ELECTRICAL', 'Установка ПЗВ чотирьохполюсний',              'Установка ПЗВ чотириполюсний',                             'PIECE'),
    ('ELECTRICAL', 'Установка пристрою захисного відключення (ПЗВ) - чотирьохполюсний', 'Установка пристрою захисного відключення (ПЗВ) - чотириполюсний', 'PIECE'),
    ('ELECTRICAL', 'Установка пристрою захисного відключення (ПЗВ) - двухполюсний',     'Установка пристрою захисного відключення (ПЗВ) - двополюсний',     'PIECE');

INSERT INTO _cat VALUES
    ('ELECTRICAL', 'Прорізання отворів і каналів у гіпсокартоні під кабель', 'LINEAR_METER', 'Штроблення'),
    ('ELECTRICAL', 'Штроблення під прихований (вбудований) кабель-канал',     'LINEAR_METER', 'Штроблення'),
    ('ELECTRICAL', 'Штроблення під електрокабель 20х20 в газоблоці',          'LINEAR_METER', 'Штроблення'),
    ('ELECTRICAL', 'Установка тимчасового освітлення та розеток (на час ремонту)', 'PIECE',   'Підготовка'),
    ('ELECTRICAL', 'Монтаж люка-ревізії під ТВ, інтернет',                    'PIECE',        'Слаботочка'),
    ('ELECTRICAL', 'Установка та підключення електроплити, варочної поверхні', 'PIECE',       'Підключення техніки'),
    ('ELECTRICAL', 'Монтаж вилки кабельної',                                  'PIECE',        'Підключення техніки'),
    ('ELECTRICAL', 'Монтаж електричного котла',                               'PIECE',        'Підключення техніки'),
    ('ELECTRICAL', 'Установка та підключення стабілізатора напруги',          'PIECE',        'Щит'),
    ('ELECTRICAL', 'Установка датчика руху або освітленості з підключенням',  'PIECE',        'Розумний дім');

INSERT INTO _desc VALUES
    ('ELECTRICAL', 'Перевірка цілісності електричного кола (продзвонка), квартири', 'M2',
     'Перевірка кожної лінії тестером від щита до точки, за м² площі квартири; результат — лінії й точки без обривів і плутанини фаз.'),
    ('ELECTRICAL', 'Закладення штроб гіпсовою сумішшю', 'LINEAR_METER',
     'Закриття штроби гіпсовою сумішшю врівень зі стіною під подальше шпаклювання.'),
    ('ELECTRICAL', 'Монтаж підрозетника', 'PIECE',
     'Посадка підрозетника на гіпс у готовий отвір врівень зі стіною; без свердління отвору.'),
    ('ELECTRICAL', 'Отвір для підрозетника в бетоні', 'PIECE',
     'Свердління отвору коронкою 68 мм у бетоні; без монтажу коробки.'),
    ('ELECTRICAL', 'Отвір для підрозетника в цеглі', 'PIECE',
     'Свердління отвору коронкою 68 мм у цеглі; без монтажу коробки.'),
    ('ELECTRICAL', 'Отвір для підрозетника в газоблоці', 'PIECE',
     'Свердління отвору коронкою 68 мм у газоблоці; без монтажу коробки.'),
    ('ELECTRICAL', 'Збірка розподільчої коробки розпаювання проводів', 'PIECE',
     'З''єднання проводів у коробці клемами чи опресуванням, ізоляція, маркування.'),
    ('ELECTRICAL', 'Установка та підключення розеток та вимикачів', 'PIECE',
     'Встановлення механізму в готовий підрозетник, підключення, рамка; ціна за 1 механізм (точку): блок із 3 розеток — 3 точки.'),
    ('ELECTRICAL', 'Монтаж електрощита в нішу', 'PIECE',
     'Кріплення корпусу щита в нішу без встановлення модулів; модулі — окремими позиціями.'),
    ('ELECTRICAL', 'Монтаж електрощита накладного 12-36 модулів', 'PIECE',
     'Кріплення корпусу щита без встановлення модулів; модулі — окремими позиціями.'),
    ('ELECTRICAL', 'Установка автомата однополюсного', 'PIECE',
     'Встановлення на DIN-рейку і підключення одного модульного пристрою, без вартості пристрою.'),
    ('ELECTRICAL', 'Установка автомата двополюсного', 'PIECE',
     'Встановлення на DIN-рейку і підключення одного модульного пристрою, без вартості пристрою.'),
    ('ELECTRICAL', 'Укладка підлоги електричної до 10м2', 'M2',
     'Укладання нагрівального мата на підготовлену основу, вимірювання опору до і після укладання; без стяжки й плитки.'),
    ('ELECTRICAL', 'Укладка підлоги електричної від 10м2', 'M2',
     'Укладання нагрівального мата на підготовлену основу, вимірювання опору до і після укладання; без стяжки й плитки.');

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
        RAISE EXCEPTION 'V163: renamed position(s) not in the library: %', v_bad;
    END IF;

    -- A name another trade ships too would be split in two by a one-trade rename.
    SELECT string_agg(r.old_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.trade <> r.trade AND ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.old_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V163: renamed position(s) are shipped by another trade too: %', v_bad;
    END IF;

    SELECT string_agg(r.new_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.new_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V163: a new name is already in the library: %', v_bad;
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
       r.unit, 'V163'
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

    RAISE NOTICE 'V163: catalog version %, % position(s) pushed into masters'' catalogs', v_version, v_added;
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
        RAISE EXCEPTION 'V163: default bundle lines still on an old name: %', v_bad;
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
        RAISE EXCEPTION 'V163: renamed position(s) lost a norm: %', v_bad;
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
        RAISE EXCEPTION 'V163: a master''s library copy was left on an old name: %', v_bad;
    END IF;

    SELECT string_agg(d.name, '; ') INTO v_bad FROM _desc d
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct
                        WHERE ct.trade = d.trade AND ct.unit = d.unit
                          AND lower(trim(ct.name)) = lower(trim(d.name))
                          AND ct.description = d.description);
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V163: description(s) did not land: %', v_bad;
    END IF;

END $$;

-- -------------------------------------------------------------------------------------------------
-- 6. Norms for the new positions.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    (gen_random_uuid(), 'BOX_HOLLOW', 'Підрозетник для порожнистих стін', NULL, 'PIECE',        NULL, NULL, NULL),
    (gen_random_uuid(), 'CABLE_TRAY', 'Лоток кабельний',                  NULL, 'LINEAR_METER', NULL, NULL, NULL);

INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'ELECTRICAL', v.name_key, v.unit, m.id, v.qty, 'QUANTITY', 1
  FROM (VALUES
    ('монтаж підрозетника в гіпсокартон (порожниста стіна)', 'PIECE',        'BOX_HOLLOW', 1::numeric),
    ('монтаж кабельного лотка',                              'LINEAR_METER', 'CABLE_TRAY', 1)
  ) AS v(name_key, unit, code, qty)
  JOIN material m ON m.code = v.code;

-- -------------------------------------------------------------------------------------------------
-- 7. Placeholder prices (V157's shape): the library row, and a notice to a master still on ours.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE v_missing text;
BEGIN
    CREATE TEMP TABLE _reprice (name varchar(255), unit varchar(20), old_price numeric(15,2),
                                new_price numeric(15,2)) ON COMMIT DROP;
    INSERT INTO _reprice VALUES
        ('Монтаж електрощита накладного 48-60 модулів',     'PIECE',        120.00, 1200.00),
        ('Монтаж електрощита накладного 72-90 модулів',     'PIECE',        120.00, 1600.00),
        ('Монтаж електрощита накладного 120-180 модулів',   'PIECE',        120.00, 2500.00),
        ('Установка автомата триполюсного',                 'PIECE',        270.00,  500.00),
        ('Установка автомата чотириполюсного',              'PIECE',        270.00,  600.00),
        ('Установка автомата перекидного чотириполюсного',  'PIECE',        270.00,  700.00),
        ('Установка автомата диференційного',               'PIECE',        270.00,  500.00),
        ('Монтаж ТВ-кабелю',                                'LINEAR_METER', 210.00,   60.00);

    SELECT string_agg(r.name, '; ') INTO v_missing FROM _reprice r
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct
                        WHERE ct.trade = 'ELECTRICAL' AND ct.type = 'WORK' AND ct.unit = r.unit
                          AND lower(trim(ct.name)) = lower(trim(r.name)) AND ct.suggested_price = r.old_price);
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'V163: repriced position(s) not found at the expected old price: %', v_missing;
    END IF;

    UPDATE catalog_templates ct SET suggested_price = r.new_price
      FROM _reprice r
     WHERE ct.trade = 'ELECTRICAL' AND ct.type = 'WORK' AND ct.unit = r.unit
       AND lower(trim(ct.name)) = lower(trim(r.name));

    INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed,
                                        position_name, old_price, new_price)
    SELECT gen_random_uuid(), ci.owner_id, 'PRICE_DRIFT', 0, 0, r.name, r.old_price, r.new_price
      FROM _reprice r
      JOIN catalog_items ci
        ON lower(trim(ci.name)) = lower(trim(r.name)) AND ci.type = 'WORK' AND ci.unit = r.unit
     WHERE ci.source = 'LIBRARY' AND ci.default_price = r.old_price
       AND NOT EXISTS (SELECT 1 FROM catalog_update_notices n
                        WHERE n.user_id = ci.owner_id AND n.kind = 'PRICE_DRIFT' AND n.dismissed_at IS NULL
                          AND lower(trim(n.position_name)) = lower(trim(r.name)));

    IF (SELECT count(*) FROM material_norm WHERE owner_id IS NULL
          AND name_key IN ('монтаж підрозетника в гіпсокартон (порожниста стіна)', 'монтаж кабельного лотка')) <> 2 THEN
        RAISE EXCEPTION 'V163: the new positions'' norms did not land';
    END IF;
END $$;
