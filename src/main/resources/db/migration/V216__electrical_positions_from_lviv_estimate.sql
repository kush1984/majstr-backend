-- =================================================================================================
-- V216 — ELECTRICAL: 19 new work positions found in a Lviv contractor's estimate (2026-11-19)
--
-- Source: a labour-only subcontract estimate for a residential complex near Lviv (Брюховичі), 90
-- lines plus a 31-line rate sheet, prices +10 % over the electrician's. Compared with our ELECTRICAL
-- library, 19 jobs had no position of their own. Its prices are wholesale (126 flats, 9 km of
-- conduit) and run 1,3–2× under our private-renovation figures, so they set no price here: each new
-- position is priced at apartment level from public price lists (Lviv: amperage.com.ua,
-- electroimpulse.lviv.ua, podkluch.com.ua; elsewhere: electricpro.od.ua, elektrika.lutsk.ua,
-- klaster.ua, zoloti-ruky.com, relicon.kiev.ua, rabotniki.ua), with the estimate as a floor. The
-- source column on each row names them. Owner approved the list and the prices on 2026-11-19.
--
-- V176's shape for additions: ONE new catalog version and NOTHING pushed — a master takes them with
-- «Додати нові з довідника» (`last_synced_catalog_version` is NOT advanced), no notice. Categories
-- are ELECTRICAL's existing ones. No brand in a name. No material norms (open question «V176's new
-- positions have no material norms» — these join it). Descriptions only where a name alone could be
-- mistaken for an existing position. V118's ranking is re-run (V120's rule).
--
-- Nothing refuses on the live library's state: a position already in the trade is skipped with a
-- WARNING. Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

CREATE TEMP TABLE _pos (category varchar(100), name varchar(255), unit varchar(20),
                        price numeric(15,2), description varchar(500)) ON COMMIT DROP;

INSERT INTO _pos VALUES
    -- Кабель: heavier power cable — the library stopped at «від 4 мм²».
    ('Кабель', 'Прокладка силового кабелю перерізом 16–35 мм²', 'LINEAR_METER', 130.00, NULL),
        -- estimate 72–113; amperage.com.ua від 100 (to 16 mm²)
    ('Кабель', 'Прокладка силового кабелю перерізом від 50 мм²', 'LINEAR_METER', 180.00, NULL),
        -- estimate 135–162
    ('Кабель', 'Герметизація кабельної проходки вогнестійкою піною', 'PIECE', 300.00,
     'Робота; піна — окремим матеріалом.'),
        -- estimate 315; foam itself 260–610 a can (prom.ua)
    -- Кабель: the tray beyond the plain «Монтаж кабельного лотка» (200).
    ('Кабель', 'Монтаж кабельного лотка шириною від 400 мм', 'LINEAR_METER', 320.00,
     'Лоток до 300 мм — позиція «Монтаж кабельного лотка».'),
        -- elektrika.lutsk.ua: 400–600 mm costs 1,6–1,9× a narrow tray; estimate 1,5×
    ('Кабель', 'Монтаж кришки кабельного лотка', 'LINEAR_METER', 100.00, NULL),
        -- elektrika.lutsk.ua 70; estimate 230
    ('Кабель', 'Монтаж повороту або трійника кабельного лотка', 'PIECE', 250.00, NULL),
        -- estimate 165; price lists put a bend at 1–1,5 m of straight tray
    ('Кабель', 'Монтаж перегородки в кабельному лотку', 'LINEAR_METER', 100.00, NULL),
        -- estimate 162
    -- Щит: terminating a core, by cross-section.
    ('Щит', 'Підключення жили кабелю до затискача, до 10 мм²', 'PIECE', 100.00,
     'За одну жилу: зачистка, оконцювання, затягування в клемі. Опресовка наконечника — окремо.'),
        -- estimate 90
    ('Щит', 'Підключення жили кабелю до затискача, 16–35 мм²', 'PIECE', 160.00,
     'За одну жилу: зачистка, оконцювання, затягування в клемі. Опресовка наконечника — окремо.'),
        -- estimate 135; zoloti-ruky.com 85 for the crimp alone
    ('Щит', 'Підключення жили кабелю до затискача, 50–95 мм²', 'PIECE', 300.00,
     'За одну жилу: зачистка, оконцювання, затягування в клемі. Опресовка наконечника — окремо.'),
        -- estimate 225–270
    ('Щит', 'Установка розетки на дин-рейку', 'PIECE', 250.00, NULL),
        -- elektrika.lutsk.ua 220; estimate 162
    -- Заземлення
    ('Заземлення', 'Прокладання сталевої штаби заземлення', 'LINEAR_METER', 200.00, NULL),
        -- estimate 189; no price list prices it apart from the loop
    ('Заземлення', 'Приєднання обладнання до контуру заземлення', 'PIECE', 300.00, NULL),
        -- estimate 162; electricpro.od.ua 400
    ('Заземлення', 'Монтаж коробки зрівнювання потенціалів (КЗП)', 'PIECE', 400.00, NULL),
        -- klaster.ua 447; estimate 162
    -- Освітлення
    ('Освітлення', 'Установка та підключення лінійного світильника (підвісного або накладного)', 'PIECE', 450.00, NULL),
        -- estimate 360; our pendant chandelier 600
    ('Освітлення', 'Установка та підключення LED-панелі (накладної або підвісної)', 'PIECE', 400.00, NULL),
        -- estimate 450; rabotniki.ua Armstrong avg ≈290 (recessed, lighter work)
    ('Освітлення', 'Установка аварійного (евакуаційного) світильника', 'PIECE', 350.00, NULL),
        -- estimate 250; rabotniki.ua light fittings 200–500
    -- Вимірювання
    ('Вимірювання', 'Виконавча документація: схеми і протоколи вимірювань', 'SET', 4000.00,
     'Комплект для здачі обʼєкта. Якщо вимірювання вже стоять у кошторисі окремими рядками — не додавайте.'),
        -- estimate 3 600
    -- Організаційні послуги
    ('Організаційні послуги', 'Виїзд (перебазування) бригади на обʼєкт', 'PIECE', 800.00, NULL);
        -- estimate 900; electricpro.od.ua від 500; podkluch.com.ua free

-- -------------------------------------------------------------------------------------------------
-- 1. Skip, with a WARNING, anything the live library already sells in ELECTRICAL.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    v_bad text;
BEGIN
    SELECT string_agg(p.name, '; ') INTO v_bad FROM _pos p
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.trade = 'ELECTRICAL' AND ct.type = 'WORK' AND ct.unit = p.unit
                      AND lower(btrim(ct.name)) = lower(btrim(p.name)));
    IF v_bad IS NOT NULL THEN
        RAISE WARNING 'V216: new position(s) already in ELECTRICAL, skipped: %', v_bad;
        DELETE FROM _pos p
         WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                        WHERE ct.trade = 'ELECTRICAL' AND ct.type = 'WORK' AND ct.unit = p.unit
                          AND lower(btrim(ct.name)) = lower(btrim(p.name)));
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 2. New positions under ONE new version; nothing pushed, `last_synced_catalog_version` untouched.
-- -------------------------------------------------------------------------------------------------
INSERT INTO catalog_templates (id, trade, category, name, type, unit, suggested_price, description,
                               added_in_version)
SELECT gen_random_uuid(), 'ELECTRICAL', p.category, p.name, 'WORK', p.unit, p.price, p.description,
       v.version
  FROM _pos p
 CROSS JOIN (SELECT COALESCE(MAX(added_in_version), 0) + 1 AS version FROM catalog_templates) v;

-- -------------------------------------------------------------------------------------------------
-- 3. RE-RANK, exactly as V118 PART 3/4 did (V120's rule: any migration that INSERTs a template).
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
                               WHEN 'Підготовка та захист'        THEN 1
                               WHEN 'Каркас і обшивка'            THEN 2
                               WHEN 'Звукоізоляція та утеплення'  THEN 3
                               WHEN 'Оздоблення під фарбування'   THEN 4
                               WHEN 'Надбавки'                    THEN 8
                               ELSE 5
                           END
                       WHEN category = 'Демонтаж' OR category LIKE 'Демонтаж %' THEN 1
                       WHEN category IN ('Підготовка', 'Підготовка та захист',
                                         'Підготовчі роботи', 'Земляні') THEN 2
                       WHEN category IN ('Надбавки', 'Організаційні послуги', 'Сміття') THEN 8
                       WHEN category = 'Інше' THEN 9
                       WHEN category IS NULL THEN 10
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
-- 4. Self-check, about THIS migration's own work (review B-50).
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    v_n int;
    v_version int := (SELECT MAX(added_in_version) FROM catalog_templates);
BEGIN
    SELECT count(*) INTO v_n FROM catalog_templates ct JOIN _pos p
        ON ct.trade = 'ELECTRICAL' AND ct.name = p.name AND ct.unit = p.unit
       AND ct.suggested_price = p.price AND ct.added_in_version = v_version;
    IF v_n <> (SELECT count(*) FROM _pos) THEN
        RAISE EXCEPTION 'V216: % of % new position(s) landed under version %',
            v_n, (SELECT count(*) FROM _pos), v_version;
    END IF;
    RAISE NOTICE 'V216: % new ELECTRICAL position(s) under catalog version %', v_n, v_version;
END $$;
