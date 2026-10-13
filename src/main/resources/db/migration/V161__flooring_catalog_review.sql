-- =================================================================================================
-- V161 — the trade-by-trade review, catalog pass: FLOORING
--
-- Source: `C:\Work\prompts\trade-review\04-FLOORING.md` §1, re-read against the V160 library. V147 and
-- V148 shipped ten of the report's eighteen additions. Owner ruling (2026-10-13): go on the
-- recommendations, never break a master. Shapes: V158's header and
-- docs/iteration-trades-review-catalog.md.
--
-- FLOORING stays FULLY answered (V147, V148 §3b): every position added here ships its norm.
--
-- WHAT THIS DOES
--   1. Seven positions: engineered board laid floating (the glued board's board 1,05 + the laminate
--      underlay), strip parquet on adhesive (the art parquet's blocks 1,1 + adhesive 1,0), laminate in
--      herringbone (laminate 1,12 — the layout surplus belongs in the coefficient, batch 2's ruling —
--      + underlay), plywood or OSB on a screed (OSB 1,05, V151's dictionary row), parquet oil (new
--      `PARQUET_OIL`, 0,04 l/m² — the report's 30-50 ml), a transition profile between coverings (the
--      threshold profile 1,0 m/m), and the dry gypsum-fibre floor copied VERBATIM from DRYWALL (V116's
--      rule: one row for a master with both trades) — its DRYWALL-filed norm is re-filed to
--      `trade = NULL`, forks with it (B-35 / B-107), so a floor layer's estimate reaches it.
--   2. Eleven renames: «укладка» → «укладання» (×4, with the click SPC vs glued LVT said), the board
--      floor «по лагах», the art parquet as a job, the 421 ₴ floor priming and the screed priming
--      («ґ»), the «заливаня» typo, the self-levelling pour by its layer, and the «лак тонера» that
--      tints or varnishes a floor (and leaves the skirting category).
--   3. «Ґрунтування основи підлоги» 421 → 35 ₴/м² (a V31 copy-paste outlier: «Грунтовка під стяжку»
--      is 40 and PAINTER's priming 33), V157's shape — a PRICE_DRIFT notice, never his price.
--   4. Category moves: the three hidden-skirting rows join «Плінтус»; the terrace board, its painting
--      and the geotextile get one «Тераса» category.
--   5. Client descriptions for the report's key positions.
--
-- NOT DONE, on purpose: the duplicates (with the other trades', for the owner's word); «Машинна
-- стяжка самовирівнююча» (two incompatible products behind one name — which one is the owner's to
-- say, V147); the threshold's unit (a unit change cannot reach a master's copy); rows BUILDER ships
-- under the same name (self-levelling compound, film, damper tape, screed insulation); a separate
-- carpet position (FLOOR_ROLL already answers both); waterproofing under screed (TILING / BUILDER).
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
    ('FLOORING', 'Підлога', 'Укладання паркетної / інженерної дошки плаваючим способом', 'M2', 420.00,
     'Укладання паркетної чи інженерної дошки на замок по підкладці, без клею; компенсаційний зазор по периметру. Плінтус і пороги — окремо.'),
    ('FLOORING', 'Підлога', 'Укладання штучного паркету на клей (палуба, ялинка)', 'M2', 750.00,
     'Укладання штучного паркету на паркетний клей палубою чи ялинкою. Циклювання й покриття — окремими позиціями.'),
    ('FLOORING', 'Підлога', 'Укладання ламінату ялинкою', 'M2', 500.00,
     'Укладання ламінату «ялинкою» плаваючим способом з підкладкою; запас матеріалу більший, ніж при прямому укладанні.'),
    ('FLOORING', 'Підлога', 'Укладання фанери або OSB на стяжку чи лаги', 'M2', 250.00,
     'Основа з фанери чи OSB під паркет або на стару дерев''яну підлогу, кріплення до стяжки чи лаг.'),
    ('FLOORING', 'Підлога', 'Покриття паркету олією (олією з воском), 1 шар', 'M2', 70.00,
     'Один шар олії чи олії з воском по відциклюваному паркету — альтернатива лаку.'),
    ('FLOORING', 'Плінтус', 'Монтаж перехідного (стикового) профілю між покриттями', 'LINEAR_METER', 200.00,
     'Профіль на стику двох покриттів, наприклад плитки й ламінату в кухні-студії.'),
    ('FLOORING', 'Стяжка', 'Монтаж сухої збірної підлоги з гіпсоволокна', 'M2', 430.00,
     'Вирівнювальна засипка та збірний настил з гіпсоволокнистих листів у два шари. Матеріал рахується окремо.');

INSERT INTO _ren VALUES
    ('FLOORING', 'Укладка ламінату проста',              'Укладання ламінату прямим способом',                    'M2'),
    ('FLOORING', 'Укладка ламінату по діагоналі',        'Укладання ламінату по діагоналі',                       'M2'),
    ('FLOORING', 'Укладка кварцвінілу на підкладку',     'Укладання замкового кварцвінілу (SPC) на підкладку',    'M2'),
    ('FLOORING', 'Укладка кварцвінілу на клей',          'Укладання клейового кварцвінілу (LVT) на клей',         'M2'),
    ('FLOORING', 'Настил підлоги із дошки',              'Настилання підлоги з дошки по лагах',                   'M2'),
    ('FLOORING', 'Художній паркет',                      'Укладання художнього паркету',                          'M2'),
    ('FLOORING', 'Грунтовка підлоги підготовчі роботи',  'Ґрунтування основи підлоги',                            'M2'),
    ('FLOORING', 'Грунтовка під стяжку',                 'Ґрунтування основи під стяжку',                         'M2'),
    ('FLOORING', 'Заливаня штроби в стяжці',             'Заливання штроби в стяжці',                             'LINEAR_METER'),
    ('FLOORING', 'Наливна підлога самовирівнююча',       'Наливна підлога — вирівнювальний шар 10–50 мм',         'M2'),
    ('FLOORING', 'Нанесення лаку тонера на підлогу',     'Тонування або лакування підлоги, 1 шар',                'M2');

INSERT INTO _cat VALUES
    ('FLOORING', 'Тонування або лакування підлоги, 1 шар',               'M2',           'Підлога'),
    ('FLOORING', 'Монтаж накладки плінтуса прихованого монтажу',         'LINEAR_METER', 'Плінтус'),
    ('FLOORING', 'Монтаж профілю плінтуса прихованого монтажу',          'LINEAR_METER', 'Плінтус'),
    ('FLOORING', 'Монтаж та виготовлення ніші під плінтус прихованого монтажу', 'LINEAR_METER', 'Плінтус'),
    ('FLOORING', 'Монтаж терасної дошки',                                'M2',           'Тераса'),
    ('FLOORING', 'Фарбування терасної дошки',                            'M2',           'Тераса'),
    ('FLOORING', 'Укладання геотекстилю',                                'M2',           'Тераса');

INSERT INTO _desc VALUES
    ('FLOORING', 'Укладання ламінату прямим способом', 'M2',
     'Укладання ламінату плаваючим способом на підготовлену основу з підкладкою; компенсаційний зазор 8–10 мм по периметру; підрізання по стінах. Плінтус і пороги — окремо.'),
    ('FLOORING', 'Укладання ламінату по діагоналі', 'M2',
     'Укладання ламінату по діагоналі плаваючим способом з підкладкою; запас матеріалу більший (10–15 %). Плінтус і пороги — окремо.'),
    ('FLOORING', 'Цементно-піщана стяжка понад 20м2', 'M2',
     'Маяки, демпферна стрічка, стяжка заданої товщини, загладжування; догляд (зволоження) 3–7 діб. Покриття — після висихання (≈28 діб під паркет чи ламінат).'),
    ('FLOORING', 'Цементно-піщана стяжка до 20м2', 'M2',
     'Маяки, демпферна стрічка, стяжка заданої товщини, загладжування; догляд (зволоження) 3–7 діб. Покриття — після висихання (≈28 діб під паркет чи ламінат).'),
    ('FLOORING', 'Наливна підлога — вирівнювальний шар 10–50 мм', 'M2',
     'Ґрунтування, демпферна стрічка, заливання вирівнювального шару заданої товщини, прокатування голчастим валиком.'),
    ('FLOORING', 'Циклювання паркету з лакуванням', 'M2',
     'Шліфування в 3 проходи (грубе, середнє, фінішне), шпаклювання щілин, знепилення і лакування.'),
    ('FLOORING', 'Монтаж плінтуса пластик', 'LINEAR_METER',
     'Різання, кути, з''єднувачі, заглушки, кріплення; ціна за м.п. плінтуса.'),
    ('FLOORING', 'Монтаж плінтуса дерев''яного', 'LINEAR_METER',
     'Різання, кути, з''єднувачі, кріплення; ціна за м.п. плінтуса.'),
    ('FLOORING', 'Монтаж плінтуса шпонованого МДФ', 'LINEAR_METER',
     'Різання, кути, з''єднувачі, заглушки, кріплення; ціна за м.п. плінтуса.'),
    ('FLOORING', 'Монтаж міжкімнатного порога', 'LINEAR_METER',
     'Поріг (стиковий профіль) у дверному отворі, кріплення на дюбелі чи клей.'),
    ('FLOORING', 'Нанесення епоксидного покриття', 'M2',
     'Наливний епоксидний шар 1–3 мм по ґрунтованій основі.'),
    ('FLOORING', 'Укладання клейового кварцвінілу (LVT) на клей', 'M2',
     'Тонкий клейовий вініл на дисперсійний клей; основа має бути вирівняна самовирівнювальною сумішшю і проґрунтована.'),
    ('FLOORING', 'Укладання замкового кварцвінілу (SPC) на підкладку', 'M2',
     'Замковий кварцвініл плаваючим способом на підкладку; компенсаційний зазор по периметру. Плінтус і пороги — окремо.');

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
        RAISE EXCEPTION 'V161: renamed position(s) not in the library: %', v_bad;
    END IF;

    -- A name another trade ships too would be split in two by a one-trade rename.
    SELECT string_agg(r.old_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.trade <> r.trade AND ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.old_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V161: renamed position(s) are shipped by another trade too: %', v_bad;
    END IF;

    SELECT string_agg(r.new_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.new_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V161: a new name is already in the library: %', v_bad;
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
       r.unit, 'V161'
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

    RAISE NOTICE 'V161: catalog version %, % position(s) pushed into masters'' catalogs', v_version, v_added;
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
        RAISE EXCEPTION 'V161: default bundle lines still on an old name: %', v_bad;
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
        RAISE EXCEPTION 'V161: renamed position(s) lost a norm: %', v_bad;
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
        RAISE EXCEPTION 'V161: a master''s library copy was left on an old name: %', v_bad;
    END IF;

    SELECT string_agg(d.name, '; ') INTO v_bad FROM _desc d
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct
                        WHERE ct.trade = d.trade AND ct.unit = d.unit
                          AND lower(trim(ct.name)) = lower(trim(d.name))
                          AND ct.description = d.description);
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V161: description(s) did not land: %', v_bad;
    END IF;

END $$;

-- -------------------------------------------------------------------------------------------------
-- 6. Norms for the new positions (FLOORING stays fully answered).
-- -------------------------------------------------------------------------------------------------
INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    (gen_random_uuid(), 'PARQUET_OIL', 'Олія для паркету (з воском)', NULL, 'LITRE', NULL, NULL, NULL);

INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'FLOORING', v.name_key, v.unit, m.id, v.qty, 'QUANTITY', v.ord
  FROM (VALUES
    ('укладання паркетної / інженерної дошки плаваючим способом', 'M2', 'ENGINEERED_BOARD',  1.05::numeric, 1),
    ('укладання паркетної / інженерної дошки плаваючим способом', 'M2', 'UNDERLAY_LAMINATE', 1.0,  2),
    ('укладання штучного паркету на клей (палуба, ялинка)',       'M2', 'PARQUET_BLOCK',     1.1,  1),
    ('укладання штучного паркету на клей (палуба, ялинка)',       'M2', 'PARQUET_ADHESIVE',  1.0,  2),
    ('укладання ламінату ялинкою',                                'M2', 'LAMINATE',          1.12, 1),
    ('укладання ламінату ялинкою',                                'M2', 'UNDERLAY_LAMINATE', 1.0,  2),
    ('укладання фанери або osb на стяжку чи лаги',                'M2', 'OSB',               1.05, 1),
    ('покриття паркету олією (олією з воском), 1 шар',            'M2', 'PARQUET_OIL',       0.04, 1),
    ('монтаж перехідного (стикового) профілю між покриттями',     'LINEAR_METER', 'THRESHOLD_PROFILE', 1.0, 1)
  ) AS v(name_key, unit, code, qty, ord)
  JOIN material m ON m.code = v.code;

-- The dry floor is now shipped by DRYWALL and FLOORING under one name: its norm answers for both.
UPDATE material_norm n
   SET trade = NULL
 WHERE n.trade = 'DRYWALL'
   AND n.name_key = 'монтаж сухої збірної підлоги з гіпсоволокна' AND n.unit = 'M2'
   AND NOT EXISTS (SELECT 1 FROM material_norm o
                    WHERE o.owner_id IS NOT DISTINCT FROM n.owner_id AND o.trade IS NULL
                      AND o.name_key = n.name_key AND o.unit = n.unit
                      AND o.material_id IS NOT DISTINCT FROM n.material_id);

-- -------------------------------------------------------------------------------------------------
-- 7. «Ґрунтування основи підлоги» 421 → 35: the library row, and a notice to a master still on 421.
-- -------------------------------------------------------------------------------------------------
UPDATE catalog_templates
   SET suggested_price = 35.00
 WHERE trade = 'FLOORING' AND name = 'Ґрунтування основи підлоги' AND unit = 'M2' AND suggested_price = 421.00;

INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed,
                                    position_name, old_price, new_price)
SELECT gen_random_uuid(), ci.owner_id, 'PRICE_DRIFT', 0, 0, 'Ґрунтування основи підлоги', 421.00, 35.00
  FROM catalog_items ci
 WHERE ci.source = 'LIBRARY' AND ci.type = 'WORK' AND ci.unit = 'M2'
   AND lower(trim(ci.name)) = 'ґрунтування основи підлоги' AND ci.default_price = 421.00
   AND NOT EXISTS (SELECT 1 FROM catalog_update_notices n
                    WHERE n.user_id = ci.owner_id AND n.kind = 'PRICE_DRIFT' AND n.dismissed_at IS NULL
                      AND lower(trim(n.position_name)) = 'ґрунтування основи підлоги');

DO $$
BEGIN
    IF (SELECT suggested_price FROM catalog_templates
         WHERE trade = 'FLOORING' AND name = 'Ґрунтування основи підлоги') <> 35.00 THEN
        RAISE EXCEPTION 'V161: the floor priming price did not move';
    END IF;
    IF EXISTS (SELECT 1 FROM catalog_templates t
                WHERE t.trade = 'FLOORING' AND t.type = 'WORK'
                  AND NOT EXISTS (SELECT 1 FROM material_norm n
                                   WHERE n.owner_id IS NULL AND n.unit = t.unit
                                     AND (n.trade = 'FLOORING' OR n.trade IS NULL)
                                     AND n.name_key = lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))))
                  AND t.name NOT IN ('Машинна стяжка самовирівнююча',
                                     'Монтаж та виготовлення ніші під плінтус прихованого монтажу')) THEN
        RAISE EXCEPTION 'V161: a FLOORING position this migration touched answers nothing';
    END IF;
    IF EXISTS (SELECT 1 FROM material_norm WHERE owner_id IS NULL AND trade = 'DRYWALL'
                AND name_key = 'монтаж сухої збірної підлоги з гіпсоволокна') THEN
        RAISE EXCEPTION 'V161: the dry floor norm is still filed under DRYWALL';
    END IF;
END $$;
