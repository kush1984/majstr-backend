-- =================================================================================================
-- V159 — the trade-by-trade review, catalog pass: TILING
--
-- Source: `C:\Work\prompts\trade-review\02-TILING.md` §1, re-read against the V158 library. V148 already
-- shipped four of the report's additions (contact primer, sealant replacement, washing off the cement
-- haze, sloped screed). Owner ruling (2026-10-13): go on the recommendations, never break a master.
-- The shapes (rename, description, category move, addition) are V158's — see its header and
-- docs/iteration-trades-review-catalog.md.
--
-- WHAT THIS DOES
--   1. Four positions: preparing old tile for tile-on-tile (a «consumes nothing» verdict — its primer
--      is the contact-primer step), factory waterproofing corners (one corner per piece, a new
--      dictionary row), a slope-to-drain surcharge (%), and sealing the bath / tray joint to the wall
--      (tape + mastic).
--   2. Twenty-one renames: the «плітки» typo (×4) and «заусовка» → «запил», «конструкціїї»,
--      «полукруглими», «вкладання», «… обсягів» → «… (великі обсяги)» (×4, and the stair flights),
--      the one-component waterproofing that is a mastic, the stair waterproofing, «без шва» (there is
--      always a joint), the screed grinding that levels nothing, «Ґрунтівка» (a material) →
--      «Ґрунтування», «Демонтаж фарби», the latex grout on stairs, and «1000х1000» → «… мм».
--   3. Three category moves (the hardboard cover and the weather surcharge are organizational; the
--      1000×1000 tile is large-format like its neighbours).
--   4. Client descriptions: every square-tile laying position, the grouts, the waterproofings, the
--      priming, the «%» surcharges, the consumables %, the exclusive tile, the turnkey pool.
--
-- NOT DONE, on purpose:
--   * «Гідроізоляція плівкова» / «Гідроізоляція сухою сумішшю» are the SAME rows BUILDER ships (one
--     row in a two-trade master's catalog), so neither can be renamed or dropped for TILING alone;
--     their 0 ₴ stays inside TILING's deliberate «договірна» set (SeedCatalogInvariantsIntegrationTest).
--   * «Формування кута з епоксидної суміші» M2 → м.п.: a unit change cannot reach a master's copy.
--   * «Обсяг замовлення менше 10 / 25 м²»: whether it is a full rate or a surcharge is the owner's
--     to say; nothing is renamed on a guess.
--   * The 18 zero prices (deliberate, V82) and the tile button (V157 left it).
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
    ('TILING', 'Підготовчі роботи',
     'Підготовка старої плитки під укладання плитки на плитку (шліфування, знежирення)', 'M2', 160.00,
     'Зматування глазурі й знежирення старого облицювання, щоб клей тримав нову плитку. Ґрунт-бетоноконтакт — окремою позицією.'),
    ('TILING', 'Підготовчі роботи',
     'Поклейка гідроізоляційних кутів (внутрішніх і зовнішніх)', 'PIECE', 100.00,
     'Фабричний гідроізоляційний кут вклеюється в перший шар гідроізоляції у внутрішньому чи зовнішньому куті.'),
    ('TILING', 'Укладання плитки',
     'Укладання плитки на підлогу з ухилом до трапу (плюс % до м.кв.)', 'PERCENT', 30.00,
     'Надбавка до укладання плитки на підлогу душової без піддону: підрізка «конвертом» під ухил до трапу.'),
    ('TILING', 'Підготовчі роботи',
     'Гідроізоляція примикання ванни чи піддону до стіни (стрічка, мастика)', 'LINEAR_METER', 120.00,
     'Гідроізоляційна стрічка в мастиці по стику ванни чи піддону зі стіною — під плитку, до силікону.');

INSERT INTO _ren VALUES
    ('TILING', 'Різ плітки під 45° заусовка',                       'Запил плитки під 45°',                                          'LINEAR_METER'),
    ('TILING', 'Різ плітки під 90° (чорновий різ)',                 'Різ плитки під 90° (чорновий різ)',                             'LINEAR_METER'),
    ('TILING', 'Різ плітки під 90° зі шліфуванням (чистовий різ)',  'Різ плитки під 90° зі шліфуванням (чистовий різ)',              'LINEAR_METER'),
    ('TILING', 'Різ плітки радіальний, фігурний',                   'Різ плитки радіальний, фігурний',                               'LINEAR_METER'),
    ('TILING', 'Заусовка під 45° кута широкоформатної плитки',      'Запил під 45° кута широкоформатної плитки',                     'LINEAR_METER'),
    ('TILING', 'Виготовлення конструкціїї під інсталяцію з ГКЛ в 2 шари', 'Виготовлення конструкції під інсталяцію з ГКЛ в 2 шари', 'PIECE'),
    ('TILING', 'Влаштування люка ревізії на магнітах з полукруглими кутами', 'Влаштування люка ревізії на магнітах з напівкруглими кутами', 'PIECE'),
    ('TILING', 'Вкладання копінгового каменю',                      'Укладання копінгового (бортового) каменю басейну',              'LINEAR_METER'),
    ('TILING', 'Облицювання стандартною плиткою обсягів',           'Облицювання стандартною плиткою (великі обсяги)',               'M2'),
    ('TILING', 'Облицювання широкоформатною плиткою обсягів',       'Облицювання широкоформатною плиткою (великі обсяги)',           'M2'),
    ('TILING', 'Облицювання мозаїкою обсягів',                      'Облицювання мозаїкою (великі обсяги)',                          'M2'),
    ('TILING', 'Облицювання каменем обсягів',                       'Облицювання каменем (великі обсяги)',                           'M2'),
    ('TILING', 'Облицювання сходових маршів',                       'Облицювання сходових маршів (великі обсяги)',                   'LINEAR_METER'),
    ('TILING', 'Нанесення однокомпонентної гідроізоляції',          'Нанесення гідроізоляційної мастики у 2 шари',                   'M2'),
    ('TILING', 'Влаштування гідроізоляції',                         'Гідроізоляція сходів, ґанку обмазувальна',                      'M2'),
    ('TILING', 'Укладання плитки без шва',                          'Укладання ректифікованої плитки з мінімальним швом (1–1,5 мм)', 'M2'),
    ('TILING', 'Шліфування стяжки, вирівнювання до 5 мм',           'Шліфування стяжки (зняття перепадів до 5 мм)',                  'M2'),
    ('TILING', 'Ґрунтівка поверхні',                                'Ґрунтування поверхні',                                          'M2'),
    ('TILING', 'Демонтаж фарби',                                    'Видалення фарби перед облицюванням (насічка, зняття)',          'M2'),
    ('TILING', 'Затирання швів цементною сумішшю з латексом',       'Затирання швів на сходах цементною сумішшю з латексом',         'M2'),
    ('TILING', 'Укладання плитки 1000х1000',                        'Укладання плитки 1000х1000 мм',                                 'M2');

INSERT INTO _cat VALUES
    ('TILING', 'Захисне укриття плитки листами ДВП',      'M2',      'Організаційні послуги'),
    ('TILING', 'Погодні умови (плюс % до кошторису)',      'PERCENT', 'Організаційні послуги'),
    ('TILING', 'Укладання плитки 1000х1000 мм',            'M2',      'Укладання широкоформатної плитки');

-- Every square-tile laying position by format, read off the library AFTER the renames would apply —
-- the 1000×1000 row is added by name below because it is still unrenamed at this point.
INSERT INTO _desc
SELECT 'TILING', ct.name, ct.unit,
       'Укладання на клей з вирівнюванням системою СВП, підрізка. Затирка швів — окремою позицією.'
  FROM catalog_templates ct
 WHERE ct.trade = 'TILING' AND ct.type = 'WORK' AND ct.unit = 'M2'
   AND ct.name ~ '^Укладання плитки [0-9]+х[0-9]+ мм$';

INSERT INTO _desc
SELECT 'TILING', ct.name, ct.unit,
       'Укладання на клей з вирівнюванням системою СВП, підрізка. Затирка швів — окремою позицією.'
  FROM catalog_templates ct
 WHERE ct.trade = 'TILING' AND ct.type = 'WORK' AND ct.unit = 'M2'
   AND ct.name ~ '^Укладання плитки [0-9]+х[0-9]+$' AND ct.name <> 'Укладання плитки 1000х1000';

INSERT INTO _desc VALUES
    ('TILING', 'Укладання плитки 1000х1000 мм', 'M2',
     'Укладання на клей з вирівнюванням системою СВП, підрізка. Затирка швів — окремою позицією.'),
    ('TILING', 'Заповнення швів цементною сумішшю', 'M2',
     'Цементна затирка швів — для більшості приміщень. Не для басейнів і поверхонь під постійною дією кислот.'),
    ('TILING', 'Заповнення швів цементною сумішшю з латексом', 'M2',
     'Еластична цементна затирка з латексною добавкою — для теплої підлоги, фасадів і поверхонь з перепадами температур.'),
    ('TILING', 'Заповнення швів епоксидною сумішшю', 'M2',
     'Епоксидна затирка: водонепроникна, не вбирає бруд і не боїться хімії. Для душових, кухонних фартухів, мозаїки, басейнів. Дорожча за цементну.'),
    ('TILING', 'Нанесення гідроізоляційної мастики у 2 шари', 'M2',
     'Полімерна гідроізоляційна мастика у 2 шари загальною товщиною 1–1,5 мм; кути й примикання армуються стрічкою окремою позицією.'),
    ('TILING', 'Нанесення двокомпонентної гідроізоляції', 'M2',
     'Еластична цементно-полімерна гідроізоляція у 2 шари (≈2 мм) — для душових, балконів, терас; кути армуються стрічкою окремою позицією.'),
    ('TILING', 'Ґрунтування поверхні', 'M2',
     'Ґрунт глибокого проникнення перед вирівнюванням або перед гідроізоляцією — кожен прохід окремою позицією.'),
    ('TILING', 'Укладання плитки по діагоналі (плюс % до м.кв.)', 'PERCENT',
     'Надбавка у відсотках до ціни укладання плитки за м² — за розкладку по діагоналі і більше підрізки.'),
    ('TILING', 'Укладання плитки ялинкою (плюс % до м.кв.)', 'PERCENT',
     'Надбавка у відсотках до ціни укладання плитки за м² — за розкладку ялинкою.'),
    ('TILING', 'Укладання плитки на відкос (плюс % до м.кв.)', 'PERCENT',
     'Надбавка у відсотках до ціни укладання плитки за м² — за облицювання відкосів.'),
    ('TILING', 'Укладання плитки поверх теплої підлоги (плюс % до м.кв.)', 'PERCENT',
     'Надбавка у відсотках до укладання над теплою підлогою: еластичний клей класу C2, підігрів вмикається не раніше ніж через 28 діб.'),
    ('TILING', 'Облицювання меблів великим форматом (плюс % до м.кв.)', 'PERCENT',
     'Надбавка у відсотках до ціни укладання великого формату за м² — за облицювання меблів.'),
    ('TILING', 'Облицювання стелі великим форматом (плюс % до м.кв.)', 'PERCENT',
     'Надбавка у відсотках до ціни укладання великого формату за м² — за облицювання стелі.'),
    ('TILING', 'Укладання великоформатної плитки товщиною 15-20 мм (плюс % до м.кв.)', 'PERCENT',
     'Надбавка у відсотках до ціни укладання за м² — за плиту товщиною 15–20 мм.'),
    ('TILING', 'Погодні умови (плюс % до кошторису)', 'PERCENT',
     'Надбавка у відсотках до всього кошторису за роботу на вулиці в холод чи спеку.'),
    ('TILING', 'Витратні матеріали', 'PERCENT',
     'Хрестики, система вирівнювання, диски, коронки та інший дрібний розхідник. Клей, затирку й гідроізоляцію рахує калькулятор матеріалів окремо.'),
    ('TILING', 'Укладання ексклюзивної дорогої плитки', 'M2',
     'Укладання дорогої або крихкої плитки з підвищеною обережністю: ручна підрізка, контроль кожного елемента.'),
    ('TILING', 'Комплексне будівництво басейну', 'PIECE',
     'Басейн під ключ одним договором: чаша, гідроізоляція, облицювання. Не поєднувати в кошторисі з окремими позиціями басейну.');

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
        RAISE EXCEPTION 'V159: renamed position(s) not in the library: %', v_bad;
    END IF;

    -- A name another trade ships too would be split in two by a one-trade rename.
    SELECT string_agg(r.old_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.trade <> r.trade AND ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.old_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V159: renamed position(s) are shipped by another trade too: %', v_bad;
    END IF;

    SELECT string_agg(r.new_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.new_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V159: a new name is already in the library: %', v_bad;
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
       r.unit, 'V159'
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

    RAISE NOTICE 'V159: catalog version %, % position(s) pushed into masters'' catalogs', v_version, v_added;
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
        RAISE EXCEPTION 'V159: default bundle lines still on an old name: %', v_bad;
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
        RAISE EXCEPTION 'V159: renamed position(s) lost a norm: %', v_bad;
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
        RAISE EXCEPTION 'V159: a master''s library copy was left on an old name: %', v_bad;
    END IF;

    SELECT string_agg(d.name, '; ') INTO v_bad FROM _desc d
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct
                        WHERE ct.trade = d.trade AND ct.unit = d.unit
                          AND lower(trim(ct.name)) = lower(trim(d.name))
                          AND ct.description = d.description);
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V159: description(s) did not land: %', v_bad;
    END IF;

END $$;

-- -------------------------------------------------------------------------------------------------
-- 6. Norms for the new positions.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    (gen_random_uuid(), 'WATERPROOF_CORNER', 'Кут гідроізоляційний (внутрішній чи зовнішній)', NULL, 'PIECE', NULL, NULL, NULL);

-- The bath / tray joint: the corner tape's 1,05 m/m, bedded in mastic over a ≈0,2 m strip at the
-- one-component mastic's 1,5 kg/m² → 0,3 kg/m (calculation from V137's own figure).
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'TILING', v.name_key, v.unit, m.id, v.qty, 'QUANTITY', v.ord
  FROM (VALUES
    ('поклейка гідроізоляційних кутів (внутрішніх і зовнішніх)', 'PIECE', 'WATERPROOF_CORNER', 1::numeric, 1),
    ('гідроізоляція примикання ванни чи піддону до стіни (стрічка, мастика)', 'LINEAR_METER', 'TAPE_WATERPROOF', 1.05, 1),
    ('гідроізоляція примикання ванни чи піддону до стіни (стрічка, мастика)', 'LINEAR_METER', 'WATERPROOF_MASTIC', 0.3, 2)
  ) AS v(name_key, unit, code, qty, ord)
  JOIN material m ON m.code = v.code;

-- Tile-on-tile preparation: sanding and degreasing; the primer is the contact-primer step.
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
VALUES (gen_random_uuid(), 'TILING',
        'підготовка старої плитки під укладання плитки на плитку (шліфування, знежирення)', 'M2',
        NULL, NULL, 'QUANTITY', 0);

DO $$
BEGIN
    IF (SELECT count(*) FROM material_norm WHERE owner_id IS NULL AND trade = 'TILING'
          AND name_key IN ('поклейка гідроізоляційних кутів (внутрішніх і зовнішніх)',
                           'гідроізоляція примикання ванни чи піддону до стіни (стрічка, мастика)',
                           'підготовка старої плитки під укладання плитки на плитку (шліфування, знежирення)')) <> 4 THEN
        RAISE EXCEPTION 'V159: the new positions'' norms did not land whole';
    END IF;
END $$;
