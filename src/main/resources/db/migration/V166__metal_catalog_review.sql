-- =================================================================================================
-- V166 — the trade-by-trade review, catalog pass: METAL (additions, renames, descriptions)
--
-- Source: `C:\Work\prompts\trade-review\09-METAL.md` §1, re-read against the V165 library. V148
-- shipped eight of the report's twenty additions; V155 normed the trade and V156 added its last
-- bundle. Owner ruling (2026-10-13): go on the recommendations, never break a master. Shapes: V158's
-- header and docs/iteration-trades-review-catalog.md.
--
-- WHAT THIS DOES
--   1. Five positions, each priced off the sibling the report named (an ORIENTIR): MMA welding by the
--      hour (400 — below the semi-automatic 500), a stringer staircase per step (1350 — BUILDER's
--      welded step), steel picket cladding on a fence (250 — the profiled sheet), chain-link fencing
--      (300 — half the 3D mesh), and rust treatment with a converter (70 — below cleaning 120). All
--      unnormed: V155's rulings (welding by the hour has no published consumption; the picket and
--      mesh quantities follow the fence's height and gap; no source for the converter's spread).
--   2. Five renames: the stair frame is priced PER STEP (900 ₴ for a whole frame is impossible —
--      BUILDER's step is 1350, the ramp 6000), bending «за гин», cutting «за м.п. різу», «Монтаж воріт
--      на місці» → «Монтаж воріт», and the welded fence rails that are welded TO the posts (the shop
--      section is «Виготовлення секції паркану з профтруби»).
--   3. Client descriptions: every «Виготовлення…» (work only — the steel is the client's estimate of
--      materials), the gates, primer and paint by m² of STEEL, the two services, erecting by the kg,
--      welding by the hour, the automation.
--
-- NOT DONE, on purpose: the seam-per-metre, arch rolling, lintel, balcony extension, steel door and
-- fire-proofing positions (no price in the report, and a number from the air is worse than a gap);
-- the cross-trade pairs with BUILDER (fence sheet, posts, stair frame — V116-shaped merges, for the
-- owner's word with the duplicates); «каркас ангара» by the tonne beside the kg rows (a unit change
-- cannot reach a master's copy).
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
    ('METAL', 'Зварювальні та монтажні роботи', 'Зварювальні роботи (електродом)', 'HOUR', 400.00,
     'Ручне дугове зварювання електродом, за годину роботи зварника; електроди — окремо.'),
    ('METAL', 'Сходи та огородження', 'Виготовлення сходів на косоурах (за сходинку)', 'PIECE', 1350.00,
     'Металеві сходи на косоурах, ціна за одну сходинку. Метал, проступи й фарбування — окремо.'),
    ('METAL', 'Паркани та огорожі', 'Монтаж металевого штакетника на паркан', 'M2', 250.00,
     'Кріплення металевого штакетника до лаг паркану саморізами. Ціна за м² паркану.'),
    ('METAL', 'Паркани та огорожі', 'Монтаж сітки-рабиці', 'LINEAR_METER', 300.00,
     'Натягування сітки-рабиці на стовпи з кріпленням. Ціна за м.п. паркану.'),
    ('METAL', 'Обробка та покриття', 'Обробка іржі перетворювачем', 'M2', 70.00,
     'Обробка іржавого металу перетворювачем іржі перед ґрунтуванням — дешевша альтернатива піскоструму.');

INSERT INTO _ren VALUES
    ('METAL', 'Виготовлення металевого каркасу сходів', 'Виготовлення металевого каркасу сходів (за сходинку)', 'PIECE'),
    ('METAL', 'Гнуття профтруби та листа',              'Гнуття профтруби або листа (за гин)',                   'PIECE'),
    ('METAL', 'Різка металу',                           'Різка металу (за м.п. різу)',                           'LINEAR_METER'),
    ('METAL', 'Монтаж воріт на місці',                  'Монтаж воріт',                                          'PIECE'),
    ('METAL', 'Зварювання каркасу та лаг паркану',      'Приварювання лаг до стовпів паркану',                   'LINEAR_METER');

INSERT INTO _desc
SELECT 'METAL', ct.name, ct.unit,
       'Ціна за роботу; метал, фурнітура і покриття — окремо, за кошторисом матеріалів.'
  FROM catalog_templates ct
 WHERE ct.trade = 'METAL' AND ct.type = 'WORK' AND ct.name LIKE 'Виготовлення %'
   AND ct.name NOT IN ('Виготовлення відкатних воріт (каркас, зашивка)', 'Виготовлення розпашних воріт (каркас, зашивка)',
                       'Виготовлення металевого каркасу сходів');

INSERT INTO _desc VALUES
    ('METAL', 'Виготовлення металевого каркасу сходів (за сходинку)', 'PIECE',
     'Ціна за одну сходинку каркаса; метал і фарбування — окремо.'),
    ('METAL', 'Виготовлення відкатних воріт (каркас, зашивка)', 'M2',
     'За м² полотна воріт. Каркас із профтруби, зашивка профнастилом або штакетником. Фундамент, фурнітура, автоматика і фарбування — окремі позиції.'),
    ('METAL', 'Виготовлення розпашних воріт (каркас, зашивка)', 'M2',
     'За м² полотна воріт. Каркас із профтруби, зашивка профнастилом або штакетником. Стовпи, автоматика і фарбування — окремі позиції.'),
    ('METAL', 'Фарбування металу', 'M2',
     'За м² поверхні металу (не площі воріт чи паркану), у 2 шари. Площа з маси: ≈127/t м² на тонну для труб, ≈255/t — для кутика, швелера, листа (t — товщина стінки, мм).'),
    ('METAL', 'Ґрунтування металу', 'M2',
     'За м² поверхні металу (не площі воріт чи паркану), один шар. Площа з маси: ≈127/t м² на тонну для труб, ≈255/t — для кутика, швелера, листа (t — товщина стінки, мм).'),
    ('METAL', 'Гаряче цинкування (послуга)', 'KG',
     'За кг готового виробу; транспорт до цинкувального заводу — окремо. Після цинкування звичайний алкідний ґрунт не застосовують.'),
    ('METAL', 'Порошкове фарбування (послуга)', 'M2',
     'За м² поверхні; виконується в камері до монтажу; зварювати після фарбування не можна.'),
    ('METAL', 'Монтаж металоконструкцій', 'KG',
     'За кг змонтованої конструкції; анкери і бетонування опор — окремо.'),
    ('METAL', 'Зварювальні роботи (аргон)', 'HOUR',
     'За годину роботи зварника; присадка і газ — окремо.'),
    ('METAL', 'Зварювальні роботи (напівавтомат)', 'HOUR',
     'За годину роботи зварника; дріт і газ — окремо.'),
    ('METAL', 'Монтаж автоматики для воріт', 'SET',
     'Монтаж і налаштування приводу воріт; без вартості комплекту автоматики.');

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
        RAISE EXCEPTION 'V166: renamed position(s) not in the library: %', v_bad;
    END IF;

    -- A name another trade ships too would be split in two by a one-trade rename.
    SELECT string_agg(r.old_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.trade <> r.trade AND ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.old_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V166: renamed position(s) are shipped by another trade too: %', v_bad;
    END IF;

    SELECT string_agg(r.new_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.new_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V166: a new name is already in the library: %', v_bad;
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
       r.unit, 'V166'
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

    RAISE NOTICE 'V166: catalog version %, % position(s) pushed into masters'' catalogs', v_version, v_added;
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
        RAISE EXCEPTION 'V166: default bundle lines still on an old name: %', v_bad;
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
        RAISE EXCEPTION 'V166: renamed position(s) lost a norm: %', v_bad;
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
        RAISE EXCEPTION 'V166: a master''s library copy was left on an old name: %', v_bad;
    END IF;

    SELECT string_agg(d.name, '; ') INTO v_bad FROM _desc d
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct
                        WHERE ct.trade = d.trade AND ct.unit = d.unit
                          AND lower(trim(ct.name)) = lower(trim(d.name))
                          AND ct.description = d.description);
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V166: description(s) did not land: %', v_bad;
    END IF;

END $$;
