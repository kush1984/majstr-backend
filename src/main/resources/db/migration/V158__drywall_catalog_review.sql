-- =================================================================================================
-- V158 — the trade-by-trade review, catalog pass: DRYWALL
--
-- Source: `C:\Work\prompts\trade-review\01-DRYWALL.md` §1, re-read against the V157 library. V148
-- already shipped five of the report's seven additions (vertical riser box, fixing for hung
-- equipment, corner bead, invisible hatch, door opening) and V157 the «Вирізка отворів» price. Owner
-- ruling (2026-10-13): build as recommended, and go on through the trades without asking unless it is
-- critical — existing masters' data must never break.
--
-- WHAT THIS DOES
--   1. Two positions: CW-stud wall lining (with a norm) and reveals glued with gypsum adhesive.
--   2. Five renames — a russism, a brand, two jobs in one name, and two names that do not say how:
--        Монтаж ніші під прихований карниз короб під комунікації → Монтаж ніші з гіпсокартону під
--          прихований карниз (the riser box has been its own position since V148)
--        Каркасна звукоізоляція (ГКЛ в два слоя) стін / стелі → … стін (стелі) з обшивкою ГКЛ у 2 шари
--        Монтаж гіпсокартону на клей → Монтаж гіпсокартону на стіни на клей (безкаркасно)
--        Монтаж укосів із гіпсокартону → Монтаж відкосів з гіпсокартону на профілі
--        Утеплення ГКЛ стіродуром → Утеплення стіни плитами XPS під обшивку ГКЛ
--   3. Client descriptions where the report asked for them.
--
-- NOT DONE, on purpose: the partitions keep their names (the «both faces» rule goes into the
-- description — a name that long is unreadable); «Монтаж гіпсокартону на стіни» keeps its name (three
-- bundles, clear enough); «Грунтування» is ONE row BUILDER and PAINTER share with one norm — a
-- DRYWALL-only rename would split it; the hidden-curtain track price (1400) has no source.
--
-- HOW A RENAME KEEPS EVERY MASTER WHOLE. A bundle line prices itself off the master's OWN catalog by
-- name, and a miss applies it at 0 ₴ (EstimateTemplateService). So a rename moves, in one statement
-- each, everything that is joined by that name:
--   * the library row (`catalog_templates`) and the lines of every DEFAULT bundle;
--   * every master's LIBRARY copy still carrying the old name — WHATEVER its price: the name is ours,
--     the price is his and is not touched. Unlike V116, which renamed only copies still at our price,
--     the default bundle line is renamed too, so a re-priced copy left on the old name would price
--     at 0 ₴. A copy he typed himself (source MANUAL or IMPORT) and a master already holding the new name are
--     left alone;
--   * his pending PRICE_DRIFT notice for that position (accept() finds the row by name);
--   * the lines of his FORKS of our bundles — only where his catalog now carries the new name;
--     templates he wrote himself are never touched;
--   * the norms: every row on the old key, shipped AND forked, is COPIED to the new key. The old
--     key keeps its rows, so an estimate line written before today still finds its materials.
-- Estimate lines are snapshots and are never touched.
--
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

-- A renamed position's OLD key keeps its norms (estimates already written carry the old name), so
-- «every shipped norm names a live position» needs to know which keys are kept on purpose.
CREATE TABLE catalog_renamed_position (
    old_name_key varchar(255) NOT NULL,
    new_name_key varchar(255) NOT NULL,
    unit         varchar(20)  NOT NULL,
    renamed_in   varchar(10)  NOT NULL,
    CONSTRAINT pk_catalog_renamed_position PRIMARY KEY (old_name_key, unit)
);

CREATE TEMP TABLE _pos (trade varchar(50), category varchar(255), name varchar(255), unit varchar(20),
                        price numeric(15,2), description varchar(500)) ON COMMIT DROP;
CREATE TEMP TABLE _ren (trade varchar(50), old_name varchar(255), new_name varchar(255),
                        unit varchar(20)) ON COMMIT DROP;
CREATE TEMP TABLE _desc (trade varchar(50), name varchar(255), unit varchar(20),
                         description varchar(500)) ON COMMIT DROP;

INSERT INTO _pos VALUES
    ('DRYWALL', 'Каркас і обшивка',
     'Монтаж облицювання стіни на стійковому профілі CW (без кріплення до стіни)', 'M2', 500.00,
     'Облицювання кривої або слабкої стіни, вентканалу: самостійний каркас із профілів CW/UW, закріплений лише до підлоги й стелі, обшивка ГКЛ в 1 шар.'),
    ('DRYWALL', 'Каркас і обшивка',
     'Монтаж відкосів з гіпсокартону на клей', 'LINEAR_METER', 230.00,
     'Відкоси вікна чи дверей з ГКЛ на гіпсовому клеї, без каркаса. Ціна за м.п. відкосу.');

INSERT INTO _ren VALUES
    ('DRYWALL', 'Монтаж ніші під прихований карниз короб під комунікації',
                'Монтаж ніші з гіпсокартону під прихований карниз', 'LINEAR_METER'),
    ('DRYWALL', 'Каркасна звукоізоляція (ГКЛ в два слоя) стін',
                'Каркасна звукоізоляція стін з обшивкою ГКЛ у 2 шари', 'M2'),
    ('DRYWALL', 'Каркасна звукоізоляція (ГКЛ в два слоя) стелі',
                'Каркасна звукоізоляція стелі з обшивкою ГКЛ у 2 шари', 'M2'),
    ('DRYWALL', 'Монтаж гіпсокартону на клей',
                'Монтаж гіпсокартону на стіни на клей (безкаркасно)', 'M2'),
    ('DRYWALL', 'Монтаж укосів із гіпсокартону',
                'Монтаж відкосів з гіпсокартону на профілі', 'LINEAR_METER'),
    ('DRYWALL', 'Утеплення ГКЛ стіродуром',
                'Утеплення стіни плитами XPS під обшивку ГКЛ', 'M2');

-- Names AFTER the renames above.
INSERT INTO _desc VALUES
    ('DRYWALL', 'Монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар', 'M2',
     'Площа рахується по обшивці з обох боків: перегородка 3×2,7 м = 16,2 м². Каркас CW/UW, ущільнювальна стрічка, ГКЛ в 1 шар з кожного боку. Мінвата і шпаклювання — окремо.'),
    ('DRYWALL', 'Монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари', 'M2',
     'Площа рахується по обшивці з обох боків: перегородка 3×2,7 м = 16,2 м². Каркас CW/UW, ущільнювальна стрічка, ГКЛ у 2 шари з кожного боку з розбіжкою швів. Мінвата і шпаклювання — окремо.'),
    ('DRYWALL', 'Монтаж радіусних конструкцій (перегородки) із гіпсокартону в 1 шар', 'M2',
     'Вигнута перегородка. Площа рахується по обшивці з обох боків. Каркас із надрізаного профілю, ГКЛ в 1 шар з кожного боку.'),
    ('DRYWALL', 'Монтаж радіусних конструкцій (перегородки) із гіпсокартону в 2 шари', 'M2',
     'Вигнута перегородка. Площа рахується по обшивці з обох боків. Каркас із надрізаного профілю, ГКЛ у 2 шари з кожного боку.'),
    ('DRYWALL', 'Монтаж короба (прямого) із гіпсокартону по периметру стелі', 'LINEAR_METER',
     'Ціна за м.п. довжини короба. Для розрахунку матеріалу вкажіть розгортку — ширина + висота короба, м.'),
    ('DRYWALL', 'Монтаж короба (радіусного) із гіпсокартону по периметру стелі', 'LINEAR_METER',
     'Ціна за м.п. довжини короба. Для розрахунку матеріалу вкажіть розгортку — ширина + висота короба, м.'),
    ('DRYWALL', 'Монтаж ніші з гіпсокартону під прихований карниз', 'LINEAR_METER',
     'П-подібна ніша в стелі під карниз чи штору. Ціна за м.п. Для розрахунку матеріалу вкажіть розгортку ніші, м.'),
    ('DRYWALL', 'Монтаж вертикального короба з гіпсокартону на стояки', 'LINEAR_METER',
     'Короб, що закриває стояки води чи каналізації. Ціна за м.п. висоти. Для розрахунку матеріалу вкажіть розгортку — ширина + глибина короба, м.'),
    ('DRYWALL', 'Монтаж гіпсокартону на стелю зі скосами', 'M2',
     'Мансардні скоси і зламані площини стелі: більше підрізки й кріплення, ніж на рівній стелі.'),
    ('DRYWALL', 'Монтаж на висоті (більше 3м)', 'PERCENT',
     'Надбавка до робіт, що виконуються з риштування чи вишки на висоті понад 3 м.'),
    ('DRYWALL', 'Шпаклювання та шліфування гіпсокартону (без склополотна)', 'M2',
     'Суцільне шпаклювання ГКЛ з шліфуванням, без склополотна — під шпалери та фактурні фарби.');

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
        RAISE EXCEPTION 'V158: renamed position(s) not in the library: %', v_bad;
    END IF;

    -- A name another trade ships too would be split in two by a one-trade rename.
    SELECT string_agg(r.old_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.trade <> r.trade AND ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.old_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V158: renamed position(s) are shipped by another trade too: %', v_bad;
    END IF;

    SELECT string_agg(r.new_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.new_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V158: a new name is already in the library: %', v_bad;
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
       r.unit, 'V158'
  FROM _ren r
ON CONFLICT DO NOTHING;

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

    RAISE NOTICE 'V158: catalog version %, % position(s) pushed into masters'' catalogs', v_version, v_added;
END $$;

-- The CW lining: «Монтаж гіпсокартону на стіни»'s board and screws on a free-standing frame
-- (Knauf W625/W626, knauf.csv: CW 2,0, UW 0,7, TN25 14 per m²). UW is fixed floor and ceiling
-- every ~0,5 m → 1,4 dowels per m² (calculation). No hangers, no CD connectors.
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'DRYWALL',
       'монтаж облицювання стіни на стійковому профілі cw (без кріплення до стіни)', 'M2',
       m.id, v.qty, 'QUANTITY', v.ord
  FROM (VALUES ('GKL_SHEET', 1::numeric, 1), ('PROFILE_CW', 2.0, 2), ('PROFILE_UW', 0.7, 3),
               ('SCREW_TN25', 14, 4), ('DOWEL_NAIL', 1.4, 5)) AS v(code, qty, ord)
  JOIN material m ON m.code = v.code;

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
        RAISE EXCEPTION 'V158: default bundle lines still on an old name: %', v_bad;
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
        RAISE EXCEPTION 'V158: renamed position(s) lost a norm: %', v_bad;
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
        RAISE EXCEPTION 'V158: a master''s library copy was left on an old name: %', v_bad;
    END IF;

    SELECT string_agg(d.name, '; ') INTO v_bad FROM _desc d
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct
                        WHERE ct.trade = d.trade AND ct.unit = d.unit
                          AND lower(trim(ct.name)) = lower(trim(d.name))
                          AND ct.description = d.description);
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V158: description(s) did not land: %', v_bad;
    END IF;

    IF (SELECT count(*) FROM material_norm
         WHERE owner_id IS NULL
           AND name_key = 'монтаж облицювання стіни на стійковому профілі cw (без кріплення до стіни)') <> 5 THEN
        RAISE EXCEPTION 'V158: the CW lining norm did not land whole';
    END IF;
END $$;
