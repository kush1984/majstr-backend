-- =================================================================================================
-- V164 — the trade-by-trade review, catalog pass: PLUMBING (additions, renames, descriptions)
--
-- Source: `C:\Work\prompts\trade-review\07-PLUMBING.md` §1, re-read against the V163 library. V148
-- shipped twenty of the report's additions (water riser, collector, filters, softener, check valve,
-- revision, spill test, sink, vanity, the heating set, the demolition rows) and V157 the four price
-- anomalies. Owner ruling (2026-10-13): go on the recommendations, never break a master. Shapes:
-- V158's header and docs/iteration-trades-review-catalog.md.
--
-- WHAT THIS DOES
--   1. Six positions: a circulation pump, an extra leak sensor, a sewer air-admittance valve (new
--      `AIR_VALVE_SEWER`, one per piece), a frame for a wall-hung basin or bidet (new
--      `INSTALL_FRAME_BASIN`, one per piece — V152 normed the WC frame the same way), a bidet on its
--      own, and the linear shower drain copied VERBATIM from TILING (one row for a two-trade master;
--      TILING's 0 ₴ is its deliberate «договірна» set, here it carries the drain's 1600 orientir, V148's
--      rule for a zero). The pump, the sensor, the bidet and the drain are appliances — unnormed.
--   2. Nineteen renames: the drinking-water filter under the sink, the self-cleaning filter, «осмос»,
--      the ball valve, the sewer outlets, the boiler hook-up «за точку», the WC that was «унітаз біде»
--      (the bidet is its own position now), the concealed mixer parts and the Smart Box brand, the
--      cast-iron and the panel radiators, the electric water heater, «рушникоосушки», «Ф» → «Ø», the
--      water-hammer arrester, and the two pressure tests as «Опресування».
--   3. Client descriptions for the report's key positions.
--
-- NOT DONE, on purpose: the ~22 duplicates (with the other trades', for the owner's word — the
-- Smart Box fitting row, the second osmosis row and the short riser / boiler rows among them); every
-- POINT → PIECE unit (a unit change cannot reach a master's copy); the boiler-room, borehole,
-- ventilation and bathroom-accessory category regrouping (display only, left for the duplicates
-- pass that touches the same rows); a separate under-floor insulation and damper position (V152's
-- under-floor norm already buys both — a double count); blockage clearing and small repairs (no
-- price in the report).
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
    ('PLUMBING', 'Опалення', 'Монтаж циркуляційного насоса (ГВП, опалення)', 'PIECE', 1200.00,
     'Встановлення циркуляційного насоса з кранами і підключенням — рециркуляція ГВП або контур опалення.'),
    ('PLUMBING', 'Водопостачання', 'Монтаж датчика протікання (додатковий)', 'PIECE', 200.00,
     'Додатковий датчик до вже змонтованої системи захисту від протікання.'),
    ('PLUMBING', 'Каналізація', 'Монтаж повітряного (вакуумного) клапана каналізації', 'PIECE', 300.00,
     'Повітряний клапан на стояку чи довгій гілці каналізації — проти зриву гідрозатворів.'),
    ('PLUMBING', 'Сантехприлади', 'Монтаж інсталяції для підвісного умивальника або біде', 'PIECE', 2600.00,
     'Рама інсталяції, кріплення до стіни й підлоги, підведення води й каналізації. Обшивка ГКЛ і плитка — окремо.'),
    ('PLUMBING', 'Сантехприлади', 'Установка біде', 'PIECE', 1400.00,
     'Встановлення й підключення біде.'),
    ('PLUMBING', 'Сантехприлади', 'Монтаж лінійного душового каналу', 'PIECE', 1600.00, NULL);

INSERT INTO _ren VALUES
    ('PLUMBING', 'Монтаж фільтра очищення води легка система',        'Установка фільтра питної води під мийку',                     'POINT'),
    ('PLUMBING', 'Монтаж фільтра осмос',                              'Монтаж фільтра зворотного осмосу',                            'PIECE'),
    ('PLUMBING', 'Установка кранів',                                  'Монтаж кульового крана',                                      'PIECE'),
    ('PLUMBING', 'Розводка труб точка',                               'Виводи каналізації по точках',                                'POINT'),
    ('PLUMBING', 'Обв''язка котла (один котел - 4 точки) - від',      'Обв''язка котла (за точку підключення)',                       'POINT'),
    ('PLUMBING', 'Установка простого унітазу біде',                   'Установка унітазу підлогового (компакт)',                     'PIECE'),
    ('PLUMBING', 'Установка змішувача прихованого типу для душа (біде)', 'Монтаж прихованої частини змішувача (душ, біде)',          'PIECE'),
    ('PLUMBING', 'Установка змішувача прихованого типу умивальник',   'Монтаж прихованої частини змішувача умивальника',             'PIECE'),
    ('PLUMBING', 'Монтаж душової системи прихованого типу Smart Box', 'Монтаж прихованої частини душової системи (вбудований корпус)', 'PIECE'),
    ('PLUMBING', 'Установка радіатора опалення вертикального, чавунного', 'Установка чавунного радіатора опалення',                  'PIECE'),
    ('PLUMBING', 'Установка радіатора опалення простого',             'Установка панельного (секційного) радіатора опалення',        'PIECE'),
    ('PLUMBING', 'Установка та підключення бойлера',                  'Установка та підключення електричного водонагрівача (бойлера)', 'PIECE'),
    ('PLUMBING', 'Установка та підключення рушникоосушки',            'Установка та підключення рушникосушки',                       'PIECE'),
    ('PLUMBING', 'Монтаж зворотного клапана каналізації Ф50',         'Монтаж зворотного клапана каналізації Ø50',                   'PIECE'),
    ('PLUMBING', 'Монтаж зворотного клапана каналізації Ф110',        'Монтаж зворотного клапана каналізації Ø110',                  'PIECE'),
    ('PLUMBING', 'Монтаж гідрокомпенсатора',                          'Монтаж гасника гідроудару (гідрокомпенсатора)',               'PIECE'),
    ('PLUMBING', 'Перевірка системи водопроводу тиском',              'Опресування (гідравлічне випробування) водопроводу',          'PIECE'),
    ('PLUMBING', 'Перевірка системи опалення тиском',                 'Опресування (гідравлічне випробування) опалення',             'PIECE');

INSERT INTO _desc VALUES
    ('PLUMBING', 'Виводи по точках з кінцевим елементом', 'POINT',
     'Підведення труби від магістралі чи колектора до точки споживання з настінним кутником. Точка = один вихід труби: змішувач умивальника — 2 точки (ХВ і ГВ), унітаз — 1.'),
    ('PLUMBING', 'Виводи каналізації по точках', 'POINT',
     'Відвід від стояка чи лежака до приладу з фасонними частинами; одна точка = один прилад.'),
    ('PLUMBING', 'Опресування (гідравлічне випробування) водопроводу', 'PIECE',
     'Гідравлічне випробування змонтованих труб тиском до закриття штроб чи стяжки, з фіксацією результату.'),
    ('PLUMBING', 'Опресування (гідравлічне випробування) опалення', 'PIECE',
     'Гідравлічне випробування системи опалення тиском до закриття штроб чи стяжки, з фіксацією результату.'),
    ('PLUMBING', 'Монтаж (заміна) стояка каналізації (пластиковий на пластиковий) з проходом через одну панель', 'PIECE',
     'Демонтаж старої ділянки в межах квартири, трійник, компенсатор, кріплення, прохід через перекриття з герметизацією. Відключення стояка погоджується з ОСББ.'),
    ('PLUMBING', 'Монтаж (заміна) стояка каналізації (пластиковий на пластиковий) з проходом через дві панелі', 'PIECE',
     'Демонтаж старої ділянки в межах квартири, трійник, компенсатор, кріплення, проходи через два перекриття з герметизацією. Відключення стояка погоджується з ОСББ.'),
    ('PLUMBING', 'Монтаж (заміна) стояка каналізації (чавунний на пластиковий) з проходом через одну панель', 'PIECE',
     'Демонтаж чавунної ділянки, перехідник чавун-пластик, трійник, компенсатор, кріплення, прохід через перекриття. Відключення стояка погоджується з ОСББ.'),
    ('PLUMBING', 'Монтаж (заміна) стояка каналізації (чавунний на пластиковий) з проходом через дві панелі', 'PIECE',
     'Демонтаж чавунної ділянки, перехідники чавун-пластик, трійник, компенсатор, кріплення, проходи через два перекриття. Відключення стояка погоджується з ОСББ.'),
    ('PLUMBING', 'Монтаж трубної системи тепла підлога з утеплювачем', 'M2',
     'Утеплювач, плівка, демпферна стрічка, труба з кліпсами за заданим кроком, підключення до колектора. Стяжка — окремо.'),
    ('PLUMBING', 'Монтаж інсталяції для унітазу', 'PIECE',
     'Рама інсталяції, кріплення до стіни й підлоги, підключення води й каналізації. Обшивка ГКЛ і плитка — окремо.'),
    ('PLUMBING', 'Обв''язка котла (за точку підключення)', 'POINT',
     'Підключення патрубка котла (подача, зворотка, ХВ, ГВ) з краном і фільтром; одна точка = один патрубок. Газ — лише ліцензована служба.'),
    ('PLUMBING', 'Монтаж котельної (котел, бойлер, насоси, крани, фільтра) - від', 'PIECE',
     '«Від» — мінімальна ціна; остаточна — після огляду приміщення і складу обладнання.');

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
        RAISE EXCEPTION 'V164: renamed position(s) not in the library: %', v_bad;
    END IF;

    -- A name another trade ships too would be split in two by a one-trade rename.
    SELECT string_agg(r.old_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.trade <> r.trade AND ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.old_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V164: renamed position(s) are shipped by another trade too: %', v_bad;
    END IF;

    SELECT string_agg(r.new_name, '; ') INTO v_bad FROM _ren r
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.type = 'WORK' AND ct.unit = r.unit
                      AND lower(trim(ct.name)) = lower(trim(r.new_name)));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V164: a new name is already in the library: %', v_bad;
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
       r.unit, 'V164'
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

    RAISE NOTICE 'V164: catalog version %, % position(s) pushed into masters'' catalogs', v_version, v_added;
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
        RAISE EXCEPTION 'V164: default bundle lines still on an old name: %', v_bad;
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
        RAISE EXCEPTION 'V164: renamed position(s) lost a norm: %', v_bad;
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
        RAISE EXCEPTION 'V164: a master''s library copy was left on an old name: %', v_bad;
    END IF;

    SELECT string_agg(d.name, '; ') INTO v_bad FROM _desc d
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct
                        WHERE ct.trade = d.trade AND ct.unit = d.unit
                          AND lower(trim(ct.name)) = lower(trim(d.name))
                          AND ct.description = d.description);
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V164: description(s) did not land: %', v_bad;
    END IF;

END $$;

-- -------------------------------------------------------------------------------------------------
-- 6. Norms for the new positions.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    (gen_random_uuid(), 'AIR_VALVE_SEWER',     'Клапан повітряний каналізаційний',                      NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'INSTALL_FRAME_BASIN', 'Інсталяція для підвісного умивальника чи біде (рама)', NULL, 'PIECE', NULL, NULL, NULL);

INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'PLUMBING', v.name_key, 'PIECE', m.id, 1, 'QUANTITY', 1
  FROM (VALUES
    ('монтаж повітряного (вакуумного) клапана каналізації', 'AIR_VALVE_SEWER'),
    ('монтаж інсталяції для підвісного умивальника або біде', 'INSTALL_FRAME_BASIN')
  ) AS v(name_key, code)
  JOIN material m ON m.code = v.code;

DO $$
BEGIN
    IF (SELECT count(*) FROM material_norm WHERE owner_id IS NULL AND trade = 'PLUMBING'
          AND name_key IN ('монтаж повітряного (вакуумного) клапана каналізації',
                           'монтаж інсталяції для підвісного умивальника або біде')) <> 2 THEN
        RAISE EXCEPTION 'V164: the new positions'' norms did not land';
    END IF;
END $$;
