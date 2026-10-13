-- =================================================================================================
-- V157 — the trade-by-trade review's price anomalies: fourteen library prices
--
-- Source: the «ціни-аномалії» of `C:\Work\prompts\trade-review\` 02-09, re-read against the V156
-- library. Owner ruling (2026-10-13): «беремо твої рекомендації» on both tables.
--
-- NINE have an anchor in our own catalog — a twin wording, a sibling in the same trade, or the same
-- position in another trade:
--   PAINTER   Захист вхідних дверей картоном               0 → 200   DEMOLITION ships it at 200
--                                                                   (open-questions, DRYWALL item 3)
--   PLUMBING  Прокладання труб на хомутах (каналізація)   25 → 220   «Прокладка труб каналізації»;
--                                                                   V71 fixed the water twin 25 → 180
--   PLUMBING  Заробка штроб (опалення)                    53 → 100   «Заробка штроб (сантехніка)»
--   PLUMBING  Прокладання труб на хомутах (опалення)      53 → 180   the water twin
--   PLUMBING  Шумоізоляція стояка каналізації           3000 → 300   a whole riser's price on a metre;
--                                                                   a unit change cannot reach a
--                                                                   master's copy, a price can
--   DRYWALL   Вирізка отворів в гіпсокартоні             900 → 200   ELECTRICAL's box hole in GKL; 900
--                                                                   was «Обшивка інсталяції»'s number
--   FLOORING  Чистка підлоги порохотягом підготовка      421 → 60    «Підготовка поверхні (очищення…)»
--   PAINTER   Грунтовка поверхні кварцгрунтом             25 → 90    «Грунтування кварцгрунтом»
--   DEMOLITION Вирізка прорізу в цегляній стіні          263 → 1000  263 is V31's placeholder;
--                                                                   «Розширення отвору під вхідні
--                                                                   двері» is 1000
--
-- FIVE had no anchor and were looked up (2026-10-13, Kyiv / Ukraine, work only):
--   DEMOLITION Вантаження сміття в авто (м³)             950 → 200   loaders add 100-250 ₴/м³ over
--              haulage (Мурашка, MoveX +100; Podsobnik ≈230-250); 700-950 is haulage WITH loading
--              (Gruzar 700, K-B-S 950), which double-counted «Вивезення сміття контейнером» in V148's
--              bundle. Now level with «Винесення будівельного сміття».
--   PAINTER   Монтаж 3 Д панелей з підготовкою під фарбування (м²)  4500 → 800 and
--   PAINTER   Монтаж 3Д панелей (м²)                      80 → 500   mounting 500-1200 ₴/м²
--              (BeautyWalls 2026, 3dpanel.kiev.ua «від 500»), seams and sanding +200 ₴/м.п.
--   BUILDER   Бетонування армопоясу з міксера (м.п.)     300 → 1100  work only: rabotniki average 1068,
--              budver 1155 over 258 masters, range 730-1350
--   BUILDER   Бетонування армопоясу вручну (м.п.)       1800 → 1200  the same plus mixing on site
--              (≈1000 ₴/м³ × ≈0,075 м³ a metre)
--   «Виготовлення кнопки з плитки» 13 316 stays: one Kyiv price list asks «від 21 600».
--
-- Left alone on purpose: the metal stair frame (900), «Підйом будматеріалів» (263) and the boiler
-- hook-up per POINT — what the price is FOR is the question, a rename's job; the 500 ₴ «лічильник
-- підвищеної складності» leaves with its duplicate; BUILDER's 1350 × 19 and 630 × 5 mix plausible and
-- implausible figures with no source; TILING's and PAINTER's deliberate zeros («договірна»).
--
-- A migration NEVER overwrites a master's own price (V120's shape): the library row moves, and a
-- master still carrying our OLD number on a LIBRARY row gets a PRICE_DRIFT notice. His price changes
-- only if he taps «Прийняти», and only while it still equals the old number. A master already holding
-- a pending notice for the position gets no second one (V120's invariant).
--
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

DO $$
DECLARE
    v_missing text;
    v_notices int;
    v_bad     int;
BEGIN
    CREATE TEMP TABLE _reprice (
        trade      varchar(50),
        name       varchar(255),
        unit       varchar(20),
        old_price  numeric(15,2),
        new_price  numeric(15,2)
    ) ON COMMIT DROP;

    INSERT INTO _reprice VALUES
        ('PAINTER',    'Захист вхідних дверей картоном',                  'PIECE',           0.00,  200.00),
        ('PLUMBING',   'Прокладання труб на хомутах (каналізація)',       'LINEAR_METER',   25.00,  220.00),
        ('PLUMBING',   'Заробка штроб (опалення)',                        'LINEAR_METER',   53.00,  100.00),
        ('PLUMBING',   'Прокладання труб на хомутах (опалення)',          'LINEAR_METER',   53.00,  180.00),
        ('PLUMBING',   'Шумоізоляція стояка каналізації',                 'LINEAR_METER', 3000.00,  300.00),
        ('DRYWALL',    'Вирізка отворів в гіпсокартоні',                  'PIECE',         900.00,  200.00),
        ('FLOORING',   'Чистка підлоги порохотягом підготовка',           'M2',            421.00,   60.00),
        ('PAINTER',    'Грунтовка поверхні кварцгрунтом',                 'M2',             25.00,   90.00),
        ('DEMOLITION', 'Вирізка прорізу в цегляній стіні',                'PIECE',         263.00, 1000.00),
        ('DEMOLITION', 'Вантаження сміття в авто',                        'M3',            950.00,  200.00),
        ('PAINTER',    'Монтаж 3 Д панелей з підготовкою під фарбування', 'M2',           4500.00,  800.00),
        ('PAINTER',    'Монтаж 3Д панелей',                               'M2',             80.00,  500.00),
        ('BUILDER',    'Бетонування армопоясу з міксера',                 'LINEAR_METER',  300.00, 1100.00),
        ('BUILDER',    'Бетонування армопоясу вручну',                    'LINEAR_METER', 1800.00, 1200.00);

    -- Every row must still be there at the price we think we are correcting; otherwise the ruling
    -- was given about a number that no longer exists and the notices below would lie.
    SELECT string_agg(r.trade || ' / ' || r.name, '; ') INTO v_missing
      FROM _reprice r
     WHERE NOT EXISTS (
            SELECT 1 FROM catalog_templates ct
             WHERE ct.trade = r.trade AND lower(trim(ct.name)) = lower(trim(r.name))
               AND ct.type = 'WORK' AND ct.unit = r.unit AND ct.suggested_price = r.old_price);
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'V157: repriced position(s) not found at the expected old price: %', v_missing;
    END IF;

    UPDATE catalog_templates ct
       SET suggested_price = r.new_price
      FROM _reprice r
     WHERE ct.trade = r.trade AND lower(trim(ct.name)) = lower(trim(r.name))
       AND ct.type = 'WORK' AND ct.unit = r.unit;

    -- catalog_items holds one row per (owner, name, type, unit), so a position two trades ship is
    -- one row whatever trade claimed it; the old price is what tells ours from his.
    INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed,
                                        position_name, old_price, new_price)
    SELECT gen_random_uuid(), ci.owner_id, 'PRICE_DRIFT', 0, 0, r.name, r.old_price, r.new_price
      FROM _reprice r
      JOIN catalog_items ci
        ON lower(trim(ci.name)) = lower(trim(r.name))
       AND ci.type = 'WORK' AND ci.unit = r.unit
     WHERE ci.source = 'LIBRARY' AND ci.default_price = r.old_price
       AND NOT EXISTS (SELECT 1 FROM catalog_update_notices n
                        WHERE n.user_id = ci.owner_id AND n.kind = 'PRICE_DRIFT'
                          AND n.dismissed_at IS NULL
                          AND lower(trim(n.position_name)) = lower(trim(r.name)));
    GET DIAGNOSTICS v_notices = ROW_COUNT;

    SELECT count(*) INTO v_bad FROM (
        SELECT 1 FROM catalog_update_notices
         WHERE kind = 'PRICE_DRIFT' AND dismissed_at IS NULL
           AND position_name IN (SELECT name FROM _reprice)
         GROUP BY user_id, position_name HAVING count(*) > 1) d;
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V157: % master(s) hold two pending notices for one position', v_bad;
    END IF;

    RAISE NOTICE 'V157: 14 library prices moved, % price notice(s) queued', v_notices;
END $$;
