-- =================================================================================================
-- V162 — the trade-by-trade review, catalog pass: BUILDER (additions, renames, descriptions)
--
-- Source: `C:\Work\prompts\trade-review\05-BUILDER.md` §1, re-read against the V161 library. V148
-- shipped most of the report's additions (blinding, slab, base insulation, apron, drainage,
-- geotextile, soil removal, first AAC course, facade plaster, corner mesh, battens, counter-battens,
-- rafters, the shared screed rows, priming). Owner ruling (2026-10-13): go on the recommendations,
-- never break a master. Shapes: V158's header and docs/iteration-trades-review-catalog.md.
--
-- WHAT THIS DOES
--   1. Five positions: foundation concrete from a mixer (V151's ready-mix 1,015 m³/m³), a ring beam
--      cast in AAC U-blocks (unnormed: the block count follows the block length), a 380 mm ceramic
--      block wall per m² (unnormed: the format decides the count), semi-dry machine screed (the
--      cement-sand screed's THICKNESS norm), and «Цементно-піщана стяжка до 20м2» copied VERBATIM from
--      FLOORING (one row for a master with both trades) — its FLOORING-filed norm is re-filed to
--      `trade = NULL`, forks with it (B-35 / B-107).
--   2. Seventeen renames: the brick walls by the Ukrainian brick (250 mm is one brick, «240» was the
--      European format), the half-brick and AAC partitions, the 2NF ceramic block, the floor beams
--      (not a roof), the antiseptic «за м³ деревини», the membrane, the OSB roof deck, the eaves
--      soffits «(ширина до 0,6 м)», the PU foam by m³, the site levelling «- від», the monolithic slab,
--      and the four concrete stair rows told apart by how the concrete arrives.
--   3. Category moves: the floor beams → «Монтажні», PU foam and the stone-chip plaster → «Фасад».
--   4. Client descriptions: the facade and roof «комплекс» rows, the foundation, the masonry
--      thresholds, the antiseptic.
--
-- NOT DONE, on purpose: the 28 duplicates (with the other trades', for the owner's word — the two
-- doorway rows included: V120 recorded them as two wordings of ONE job, so they are not renamed apart);
-- «Кладка стіни з керамоблока 38х60х20» (no such ceramic block exists — its format is the owner's to
-- say), «Приготування розчину з щебнем», «Гідроізоляція плівкою» and the masonry reinforcement units
-- (meanings to settle first); the post caps' unit (a unit change cannot reach a master's copy); rows
-- PAINTER ships under the same name; a rebar-tying position (waits for the «арматура кг/м³» basis);
-- an excavator position (no price in the report); BUILDER's unsourced 1350 / 630 prices.
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
    ('BUILDER', 'Фундамент', 'Бетонування фундаменту з міксера (приймання, вібрування)', 'M3', 750.00,
     'Приймання товарного бетону з міксера, укладання й вібрування. Арматура й опалубка — окремими позиціями.'),
    ('BUILDER', 'Кладка', 'Влаштування армопояса по газоблоку в U-блоках', 'LINEAR_METER', 1100.00,
     'Армопояс під перекриття чи мауерлат у U-блоках: укладання блоків, арматурний каркас, бетонування.'),
    ('BUILDER', 'Кладка', 'Кладка стін з керамоблоку 380 мм на теплий розчин або клей-піну', 'M2', 750.00,
     'Кладка зовнішніх стін з крупноформатного керамічного блоку 380 мм. Ціна за м² стіни.'),
    ('BUILDER', 'Стяжка', 'Напівсуха стяжка механізована (з фіброю)', 'M2', 350.00,
     'Напівсуха цементно-піщана стяжка з фіброволокном: подача пневмонагнітачем, затирання машиною.'),
    ('BUILDER', 'Стяжка', 'Цементно-піщана стяжка до 20м2', 'M2', 500.00, NULL);

INSERT INTO _ren VALUES
    ('BUILDER', 'Кладка цегли 240 мм',                    'Кладка стін з рядової цегли в 1 цеглу (250 мм)',           'M3'),
    ('BUILDER', 'Кладка цегли 380 мм',                    'Кладка стін з рядової цегли в 1,5 цегли (380 мм)',         'M3'),
    ('BUILDER', 'Кладка перегородки з цегли до 50м2',      'Кладка перегородки з цегли в пів цегли (120 мм) до 50 м²', 'M2'),
    ('BUILDER', 'Кладка перегородки з цегли від 50м2',     'Кладка перегородки з цегли в пів цегли (120 мм) від 50 м²', 'M2'),
    ('BUILDER', 'Кладка перегородки з піноблоку газоблоку до 50м2',  'Кладка перегородки з газоблоку (піноблоку) до 50 м²',  'M2'),
    ('BUILDER', 'Кладка перегородки з піноблоку газоблоку від 50м2', 'Кладка перегородки з газоблоку (піноблоку) від 50 м²', 'M2'),
    ('BUILDER', 'Кладка стіни з керамоблока 2НФ 25х12',   'Кладка стін з керамоблоку 2НФ (250×120×138)',               'M3'),
    ('BUILDER', 'Балки перекриття',                       'Монтаж дерев''яних балок перекриття',                       'PIECE'),
    ('BUILDER', 'Обробка антисептиком',                   'Обробка пиломатеріалів антисептиком (за м³ деревини)',      'M3'),
    ('BUILDER', 'Гідробар''єр паробар''єр',                'Монтаж гідро- або пароізоляційної мембрани покрівлі',       'M2'),
    ('BUILDER', 'Монтаж ОСБ плити',                       'Монтаж суцільного настилу з ОСБ на покрівлю',               'M2'),
    ('BUILDER', 'Підшива даху дерев''яною вагонкою',       'Підшивка звісів даху дерев''яною вагонкою (ширина до 0,6 м)', 'LINEAR_METER'),
    ('BUILDER', 'Підшива даху сайдінгом, профнастилом',    'Підшивка звісів даху сайдингом або профнастилом (ширина до 0,6 м)', 'LINEAR_METER'),
    ('BUILDER', 'Зовнішнє утеплення пінополіуретаном',     'Напилення пінополіуретану (утеплення) за м³ піни',          'M3'),
    ('BUILDER', 'Планування ділянки і нівелювання - від',  'Планування ділянки і нівелювання (мінімальне замовлення)',  'PIECE'),
    ('BUILDER', 'Монолітне перекриття приймання бетону армування', 'Влаштування монолітного перекриття (армування, бетонування)', 'M3'),
    ('BUILDER', 'Бетонні сходи пряма сходинка',            'Бетонні сходи пряма сходинка (бетон з міксера)',            'PIECE'),
    ('BUILDER', 'Бетонні сходи поворотна сходинка',        'Бетонні сходи поворотна сходинка (бетон з міксера)',        'PIECE'),
    ('BUILDER', 'Бетонні сходи пряма сходинка заливка вручну',     'Бетонні сходи пряма сходинка (бетон вручну)',     'PIECE'),
    ('BUILDER', 'Бетонні сходи поворотна сходинка заливка вручну', 'Бетонні сходи поворотна сходинка (бетон вручну)', 'PIECE');

INSERT INTO _cat VALUES
    ('BUILDER', 'Монтаж дерев''яних балок перекриття',               'PIECE', 'Монтажні'),
    ('BUILDER', 'Напилення пінополіуретану (утеплення) за м³ піни',  'M3',    'Фасад'),
    ('BUILDER', 'Механізоване нанесення камінцевої штукатурки',      'M2',    'Фасад');

INSERT INTO _desc VALUES
    ('BUILDER', 'Утеплення фасада комплекс пінопласт сітка декор штукатурка', 'M2',
     'Приклеювання плит пінопласту, дюбелювання, армування сіткою по клейовій суміші, ґрунтування, декоративна штукатурка. Без риштування і відкосів.'),
    ('BUILDER', 'Утеплення фасада комплекс вата сітка декор штукатурка', 'M2',
     'Приклеювання плит мінеральної вати, дюбелювання, армування сіткою по клейовій суміші, ґрунтування, декоративна штукатурка. Без риштування і відкосів.'),
    ('BUILDER', 'Монтаж двоскатного даху (кроква, обрешітка, контррейка, черепиця)', 'M2',
     'Кроквяна система, мембрана, контррейка, обрешітка і покрівельне покриття. Утеплення, водостік і підшивка звісів — окремо.'),
    ('BUILDER', 'Монтаж односкатного даху (кроква, обрешітка, контррейка, черепиця)', 'M2',
     'Кроквяна система, мембрана, контррейка, обрешітка і покрівельне покриття. Утеплення, водостік і підшивка звісів — окремо.'),
    ('BUILDER', 'Монтаж чотирьохскатного даху (кроква, обрешітка, контррейка, черепиця)', 'M2',
     'Кроквяна система, мембрана, контррейка, обрешітка і покрівельне покриття. Утеплення, водостік і підшивка звісів — окремо.'),
    ('BUILDER', 'Облаштування фундаменту (приймання бетону арматура)', 'M3',
     'В''язання арматурного каркаса і бетонування. Без опалубки, земляних робіт і вартості бетону та арматури.'),
    ('BUILDER', 'Кладка перегородки з цегли в пів цегли (120 мм) до 50 м²', 'M2',
     'Ціна для обсягу кладки на об''єкті до 50 м²; від 50 м² — окрема позиція з нижчою ціною.'),
    ('BUILDER', 'Кладка перегородки з цегли в пів цегли (120 мм) від 50 м²', 'M2',
     'Ціна для обсягу кладки на об''єкті від 50 м².'),
    ('BUILDER', 'Кладка перегородки з газоблоку (піноблоку) до 50 м²', 'M2',
     'Ціна для обсягу кладки на об''єкті до 50 м²; від 50 м² — окрема позиція з нижчою ціною.'),
    ('BUILDER', 'Кладка перегородки з газоблоку (піноблоку) від 50 м²', 'M2',
     'Ціна для обсягу кладки на об''єкті від 50 м².'),
    ('BUILDER', 'Обробка пиломатеріалів антисептиком (за м³ деревини)', 'M3',
     'Обробка антисептиком у 2 шари; ціна за 1 м³ пиломатеріалу.');

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
        RAISE EXCEPTION 'V162: renamed position(s) not in the library: %', v_bad;
    END IF;

    -- A name another trade ships too would be split in two by a one-trade rename.
    SELECT string_agg(r.old_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.trade <> r.trade AND ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.old_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V162: renamed position(s) are shipped by another trade too: %', v_bad;
    END IF;

    SELECT string_agg(r.new_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.new_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V162: a new name is already in the library: %', v_bad;
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
       r.unit, 'V162'
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

    RAISE NOTICE 'V162: catalog version %, % position(s) pushed into masters'' catalogs', v_version, v_added;
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
        RAISE EXCEPTION 'V162: default bundle lines still on an old name: %', v_bad;
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
        RAISE EXCEPTION 'V162: renamed position(s) lost a norm: %', v_bad;
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
        RAISE EXCEPTION 'V162: a master''s library copy was left on an old name: %', v_bad;
    END IF;

    SELECT string_agg(d.name, '; ') INTO v_bad FROM _desc d
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct
                        WHERE ct.trade = d.trade AND ct.unit = d.unit
                          AND lower(trim(ct.name)) = lower(trim(d.name))
                          AND ct.description = d.description);
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V162: description(s) did not land: %', v_bad;
    END IF;

END $$;

-- -------------------------------------------------------------------------------------------------
-- 6. Norms for the new positions.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'BUILDER', 'бетонування фундаменту з міксера (приймання, вібрування)', 'M3',
       m.id, 1.015, 'QUANTITY', 1
  FROM material m WHERE m.code = 'CONCRETE_READY';

-- Semi-dry screed is the cement-sand screed's mix laid by machine: its THICKNESS norm, as shipped.
INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id, qty_per_unit,
                           waste_percent, sort_order, basis, default_param, baseline_param)
SELECT gen_random_uuid(), NULL, 'BUILDER', 'напівсуха стяжка механізована (з фіброю)', n.unit,
       n.material_id, n.qty_per_unit, n.waste_percent, n.sort_order, n.basis, n.default_param, n.baseline_param
  FROM material_norm n
 WHERE n.owner_id IS NULL AND n.name_key = 'цементно-піщана стяжка понад 20м2' AND n.unit = 'M2';

-- «Цементно-піщана стяжка до 20м2» is now shipped by FLOORING and BUILDER: its norm answers for both.
UPDATE material_norm n
   SET trade = NULL
 WHERE n.trade = 'FLOORING'
   AND n.name_key = 'цементно-піщана стяжка до 20м2' AND n.unit = 'M2'
   AND NOT EXISTS (SELECT 1 FROM material_norm o
                    WHERE o.owner_id IS NOT DISTINCT FROM n.owner_id AND o.trade IS NULL
                      AND o.name_key = n.name_key AND o.unit = n.unit
                      AND o.material_id IS NOT DISTINCT FROM n.material_id);

-- The verbatim copy carries FLOORING's description too (one row, one meaning).
UPDATE catalog_templates b
   SET description = f.description
  FROM catalog_templates f
 WHERE b.trade = 'BUILDER' AND b.name = 'Цементно-піщана стяжка до 20м2'
   AND f.trade = 'FLOORING' AND f.name = 'Цементно-піщана стяжка до 20м2'
   AND b.description IS DISTINCT FROM f.description;

DO $$
BEGIN
    IF (SELECT count(*) FROM material_norm WHERE owner_id IS NULL
          AND name_key IN ('бетонування фундаменту з міксера (приймання, вібрування)',
                           'напівсуха стяжка механізована (з фіброю)')) < 2 THEN
        RAISE EXCEPTION 'V162: the new positions'' norms did not land';
    END IF;
    IF EXISTS (SELECT 1 FROM material_norm WHERE owner_id IS NULL AND trade = 'FLOORING'
                AND name_key = 'цементно-піщана стяжка до 20м2') THEN
        RAISE EXCEPTION 'V162: the small-screed norm is still filed under FLOORING';
    END IF;
END $$;
