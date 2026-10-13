-- =================================================================================================
-- V165 — the trade-by-trade review, catalog pass: DEMOLITION (additions, renames, descriptions)
--
-- Source: `C:\Work\prompts\trade-review\08-DEMOLITION.md` §1, re-read against the V164 library. V148
-- shipped fourteen of the report's additions and V154 the dust curtain's norm. Owner ruling
-- (2026-10-13): go on the recommendations, never break a master. Shapes: V158's header and
-- docs/iteration-trades-review-catalog.md.
--
-- WHAT THIS DOES
--   1. Ten positions, priced off our own siblings where the report gives no range (an ORIENTIR, to be
--      settled by price_insight_candidate): haulage by the van trip (2000 — the haulers' call-out
--      fee, V157's sources), built-in wardrobes (400), a WC frame (600 — the WC 350 plus the frame),
--      plaster off a ceiling (90 — the wall rate 60 × 1,5 overhead), a suspended ceiling (60 — the
--      plasterboard ceiling), plasterboard lining (60), brick masonry by m³ (400 — the brick wall's
--      100 ₴/м² over 0,25 m), concrete by breaker by m³ (1300 — the screed's 65 ₴/м² over 0,05 m),
--      floor insulation / hardboard / plywood (25 — beside linoleum 20), and the film cover copied
--      VERBATIM from TILING (one row for a two-trade master; TILING's 0 ₴ is its «договірна» set, here
--      it carries PAINTER's 50 for the same job — V148's rule for a zero).
--      Norms: V154's rule — taking things apart consumes nothing (the bags are «Фасування»); the WC
--      frame leaves pipe ends to cap and stays unnormed like the other fixtures; the film cover buys
--      film 1,10 m²/m² + masking tape 0,5 m/m² (the report's §3.3, the cardboard cover's tape), filed
--      `trade = NULL` because TILING ships the same row.
--   2. Eight renames: «ламінату паркету», «розеток вимикачів», «дерев'яної підлоги лаг», both
--      partitions and the brick wall said by thickness, and «Отвір в бетоні до 10 см» that is a
--      100 mm wall, not a 10 cm hole.
--   3. One category move: wall tile out of «Демонтаж підлоги».
--   4. Client descriptions for the report's key positions.
--
-- NOT DONE, on purpose: diamond cutting and reinforcing an opening (no price, and the frame is METAL's
-- work); bags carried per floor (no «bags × floors» unit); rows another trade ships under the same
-- name (linoleum, washbasin, old pipes); the balcony-block pair and the debris wordings (with the
-- duplicates, for the owner's word); «Підйом будматеріалів» (its unit is the question).
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
    ('DEMOLITION', 'Сміття', 'Вивезення сміття автомобілем (рейс)', 'PIECE', 2000.00,
     'Один рейс вантажного автомобіля з утилізацією сміття на полігоні. Завантаження — окремо.'),
    ('DEMOLITION', 'Демонтаж столярки', 'Демонтаж вбудованих шаф і антресолей', 'PIECE', 400.00,
     'Розбирання вбудованої шафи чи антресолі. Винесення — окремо.'),
    ('DEMOLITION', 'Демонтаж сантехніки', 'Демонтаж інсталяції (підвісного унітазу)', 'PIECE', 600.00,
     'Зняття підвісного унітазу і рами інсталяції з від''єднанням води й каналізації.'),
    ('DEMOLITION', 'Демонтаж стелі', 'Збивання штукатурки зі стелі', 'M2', 90.00,
     'Збивання старої штукатурки зі стелі до плити перекриття. Фасування і винесення сміття — окремо.'),
    ('DEMOLITION', 'Демонтаж стелі', 'Демонтаж підвісної стелі (касетної, рейкової)', 'M2', 60.00,
     'Зняття касет чи рейок і підвісної системи.'),
    ('DEMOLITION', 'Демонтаж стін', 'Демонтаж обшивки стін з гіпсокартону', 'M2', 60.00,
     'Зняття облицювання стін з ГКЛ разом із каркасом чи клеєм.'),
    ('DEMOLITION', 'Демонтаж стін', 'Демонтаж цегляної кладки', 'M3', 400.00,
     'Розбирання цегляної кладки — товстих стін, тумб, печей, підстав під ванну. Ціна за м³ кладки.'),
    ('DEMOLITION', 'Отвори', 'Демонтаж бетону відбійним молотком', 'M3', 1300.00,
     'Розбивання бетону відбійним молотком — пороги, монолітні тумби, товста стяжка. Ціна за м³ бетону.'),
    ('DEMOLITION', 'Демонтаж підлоги', 'Демонтаж утеплювача, ДВП чи фанери з підлоги', 'M2', 25.00,
     'Зняття шарів під старим покриттям підлоги.'),
    ('DEMOLITION', 'Підготовка та захист', 'Укриття плівкою підлоги, дверей, сантехніки', 'M2', 50.00, NULL);

INSERT INTO _ren VALUES
    ('DEMOLITION', 'Демонтаж ламінату паркету',                    'Демонтаж ламінату або паркету',                            'M2'),
    ('DEMOLITION', 'Демонтаж розеток вимикачів',                   'Демонтаж розеток і вимикачів',                             'PIECE'),
    ('DEMOLITION', 'Демонтаж дерев''яної підлоги лаг',              'Демонтаж дерев''яної підлоги з лагами',                     'M2'),
    ('DEMOLITION', 'Демонтаж перегородки з піноблоку газоблоку',   'Демонтаж перегородки з піноблоку або газоблоку',           'M2'),
    ('DEMOLITION', 'Демонтаж перегородки з цегли півцеглини',      'Демонтаж перегородки з цегли в півцеглини (120 мм)',       'M2'),
    ('DEMOLITION', 'Демонтаж цегляної стіни',                      'Демонтаж цегляної стіни в цеглину (250 мм)',               'M2'),
    ('DEMOLITION', 'Отвір в бетоні до 10 см',                      'Вирізання прорізу в бетонній стіні товщиною до 100 мм',    'M2');

INSERT INTO _cat VALUES
    ('DEMOLITION', 'Демонтаж плитки зі стін', 'M2', 'Демонтаж стін');

INSERT INTO _desc VALUES
    ('DEMOLITION', 'Збивання штукатурки зі стін', 'M2',
     'Збивання старої штукатурки до цегли чи бетону. Фасування і винесення сміття — окремо. Шар понад 3 см — за домовленістю.'),
    ('DEMOLITION', 'Демонтаж стяжки бетонної', 'M2',
     'Розбирання стяжки товщиною до 5 см відбійним молотком. Товстіша стяжка — доплата. Фасування і винесення — окремо.'),
    ('DEMOLITION', 'Демонтаж керамічної плитки з підлоги', 'M2',
     'Зняття плитки разом із клеєм до основи. Збереження плитки не гарантується.'),
    ('DEMOLITION', 'Демонтаж плитки зі стін', 'M2',
     'Зняття плитки разом із клеєм до основи. Збереження плитки не гарантується.'),
    ('DEMOLITION', 'Демонтаж ванни', 'PIECE',
     'Демонтаж ванни з від''єднанням сифона. Чавунну ванну за потреби розбивають на частини; винесення — окремо.'),
    ('DEMOLITION', 'Демонтаж цегляної стіни в цеглину (250 мм)', 'M2',
     'Розбирання не несучої стіни. Несучі стіни — лише за проєктом і дозволом; посилення прорізу — окрема робота.'),
    ('DEMOLITION', 'Демонтаж перегородки з цегли в півцеглини (120 мм)', 'M2',
     'Розбирання не несучої перегородки. Фасування і винесення сміття — окремо.'),
    ('DEMOLITION', 'Вирізка прорізу в цегляній стіні', 'PIECE',
     'Розмір прорізу узгоджується до початку. Несучі стіни — лише за проєктом; посилення прорізу — окрема робота.'),
    ('DEMOLITION', 'Розширення дверного прорізу', 'PIECE',
     'Розмір прорізу узгоджується до початку. Несучі стіни — лише за проєктом; посилення прорізу — окрема робота.'),
    ('DEMOLITION', 'Демонтаж електрощита', 'PIECE',
     'Роботи виконуються при знеструмленій лінії; тимчасове живлення — окремо.'),
    ('DEMOLITION', 'Фасування сміття в мішки', 'PIECE',
     'Збирання сміття в будівельні мішки (до 40 кг). Мішки — матеріал, у кошторисі окремо.'),
    ('DEMOLITION', 'Винесення будівельного сміття', 'M3',
     'Винесення мішків до контейнера чи авто. Поверх без ліфта — доплата.'),
    ('DEMOLITION', 'Вантаження сміття в авто', 'M3',
     'Завантаження мішків або навалом у кузов чи контейнер. Вивезення — окремою позицією.'),
    ('DEMOLITION', 'Демонтаж натяжної стелі', 'M2',
     'Зняття полотна і профілю. Повторне використання полотна не гарантується.');

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
        RAISE EXCEPTION 'V165: renamed position(s) not in the library: %', v_bad;
    END IF;

    -- A name another trade ships too would be split in two by a one-trade rename.
    SELECT string_agg(r.old_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.trade <> r.trade AND ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.old_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V165: renamed position(s) are shipped by another trade too: %', v_bad;
    END IF;

    SELECT string_agg(r.new_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.new_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V165: a new name is already in the library: %', v_bad;
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
       r.unit, 'V165'
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

    RAISE NOTICE 'V165: catalog version %, % position(s) pushed into masters'' catalogs', v_version, v_added;
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
        RAISE EXCEPTION 'V165: default bundle lines still on an old name: %', v_bad;
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
        RAISE EXCEPTION 'V165: renamed position(s) lost a norm: %', v_bad;
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
        RAISE EXCEPTION 'V165: a master''s library copy was left on an old name: %', v_bad;
    END IF;

    SELECT string_agg(d.name, '; ') INTO v_bad FROM _desc d
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct
                        WHERE ct.trade = d.trade AND ct.unit = d.unit
                          AND lower(trim(ct.name)) = lower(trim(d.name))
                          AND ct.description = d.description);
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V165: description(s) did not land: %', v_bad;
    END IF;

END $$;

-- -------------------------------------------------------------------------------------------------
-- 6. Norms for the new positions.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'DEMOLITION', v.name_key, v.unit, NULL, NULL, 'QUANTITY', 0
  FROM (VALUES
    ('вивезення сміття автомобілем (рейс)',          'PIECE'),
    ('демонтаж вбудованих шаф і антресолей',        'PIECE'),
    ('збивання штукатурки зі стелі',                'M2'),
    ('демонтаж підвісної стелі (касетної, рейкової)', 'M2'),
    ('демонтаж обшивки стін з гіпсокартону',        'M2'),
    ('демонтаж цегляної кладки',                    'M3'),
    ('демонтаж бетону відбійним молотком',          'M3'),
    ('демонтаж утеплювача, двп чи фанери з підлоги', 'M2')
  ) AS v(name_key, unit);

-- The film cover: TILING ships it too, so the norm is trade-less.
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), NULL, 'укриття плівкою підлоги, дверей, сантехніки', 'M2', m.id, v.qty, 'QUANTITY', v.ord
  FROM (VALUES ('PE_FILM', 1.10::numeric, 1), ('TAPE_MASKING', 0.5, 2)) AS v(code, qty, ord)
  JOIN material m ON m.code = v.code
 WHERE NOT EXISTS (SELECT 1 FROM material_norm x WHERE x.owner_id IS NULL
                     AND x.name_key = 'укриття плівкою підлоги, дверей, сантехніки');

DO $$
BEGIN
    IF (SELECT count(DISTINCT name_key) FROM material_norm WHERE owner_id IS NULL
          AND name_key IN ('вивезення сміття автомобілем (рейс)', 'демонтаж вбудованих шаф і антресолей',
                           'збивання штукатурки зі стелі', 'демонтаж підвісної стелі (касетної, рейкової)',
                           'демонтаж обшивки стін з гіпсокартону', 'демонтаж цегляної кладки',
                           'демонтаж бетону відбійним молотком', 'демонтаж утеплювача, двп чи фанери з підлоги',
                           'укриття плівкою підлоги, дверей, сантехніки')) <> 9 THEN
        RAISE EXCEPTION 'V165: the new positions'' norms did not land';
    END IF;
END $$;
