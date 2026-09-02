-- =================================================================================================
-- V120 — the three prices the master settled, and four positions the drywall catalog was missing.
--
--   «та ти ціни давай зміни, беремо 850, 1350 і 100 і доречі ти десь там бігав по сайтах і понаходив
--    якісь позиції по гіпсокартоні, якщо там є що для нас взяти то давай беремо заодно»
--
-- PART 1 — the three numbers. All three were logged as "waiting on his word, not on code"
-- (open-questions.md → «DRYWALL: cross-trade price contradictions», items 1 and 4; and
-- iteration-catalog-order-and-explanations.md §7). Each is a cross-trade contradiction: one job
-- priced twice, in two trades, under two wordings.
--
--   850 / 650  PAINTER «Каркасна звукоізоляція ГКЛ два слоя стелі / стін» — 80 / 60 ₴, while
--              DRYWALL prices the identical work at 850 / 650 ₴. A 10x gap: the PAINTER rows look
--              like they inherited the price of the *безкаркасна* variant sitting next to them
--              (which really is 80 / 60). Note CatalogNameKey already considers the two wordings
--              the SAME work — «в» is a connector and word order is sorted away — so this pair was
--              also splitting its own price_insight_candidate signal in half.
--   1350       BUILDER «Облаштування дверного пройому звуження розширення» — 800 ₴, while
--              «Облаштування дверної пройми» in the same trade and unit says 1350 ₴.
--   100        PAINTER «Армування стиків ГКЛ» — 75 ₴/м.п., while DRYWALL's «Заповнення та
--              армування стиків ГКЛ» is 100 ₴/м.п. His own catalog already reads 100 on the
--              PAINTER row: he re-priced it by hand, which is the answer to which number is real.
--
-- A migration NEVER overwrites a master's own price (V116 does not, and CatalogTemplateService is
-- built around not doing it): the shared library row is updated here, and every master still
-- carrying our OLD number on a LIBRARY row gets a PRICE_DRIFT notice. His price changes only if he
-- taps «Прийняти», and only while it still equals the old number.
--
-- Deliberately NOT done: retiring either duplicate wording. With the prices equal the merge is
-- finally cheap, but it deletes rows from masters' catalogs and repoints default bundles — a
-- V116-shaped migration, not an UPDATE. Logged in open-questions.md instead.
--
-- PART 2 — four positions the catalog did not carry, found by reading the Kyiv drywall price lists
-- (rabotniki.ua's per-position averages over 40-120 offers each, budver, kabanchik) against our 55.
-- Most of what those lists sell we already ship, and better: укоси, тіньовий профіль, треки
-- прихованого карниза, ніша з підсвічуванням, радіусні перегородки, арка, фрезерування are all
-- there. Four things were genuinely absent, and one of them is the master's own trade:
--
--   Монтаж сухої збірної підлоги з гіпсоволокна   430 ₴/м²    the whole library had no dry-floor
--                                                             position at all (no ГВЛ, no суха
--                                                             стяжка, in any trade).
--   Ремонт ділянки конструкції з гіпсокартону     600 ₴/м²    no repair position; TILING («Дрібний
--                                                             ремонт облицювання») and PAINTER
--                                                             («Місцевий ремонт штукатурки») both
--                                                             ship one, drywall did not.
--   Установка люка-ревізії простого               400 ₴/шт    copied VERBATIM from PLUMBING (V116's
--                                                             rule: re-wording a stage another
--                                                             trade ships hands a two-trade master
--                                                             two rows). Description left NULL for
--                                                             the same reason — one row, one
--                                                             meaning.
--   Монтаж ущільнювальної стрічки на профіль       50 ₴/м.п.  sold as its own line everywhere; we
--                                                             had the mineral wool, the membrane
--                                                             and the sealant, but not the tape
--                                                             that decouples the frame.
--
-- Prices are an ORIENTIR, derived the same way V116/V117 derived theirs, and to be settled by
-- price_insight_candidate (V94) once masters actually estimate with them:
--   • підлога — our own м² prices track rabotniki's averages almost exactly (стіна 430 vs 453,
--     стеля 560 vs 526, перегородка 800 vs 647), and budver prices a dry floor level with a wall
--     (286 = 286); scaled to ours that is 430.
--   • ремонт — demolition of the damaged patch (60) + re-sheathing (430) + the joints around it.
--     The «від 286 ₴» the lead-generation sites print is a headline, not a repair price.
--   • стрічка — rabotniki 30-70 ₴/м.п., average 43 over 68 offers.
--
-- Deliberately NOT added, and why (each is a wording that would compete with a row we already have,
-- which is the exact defect V116 existed to undo):
--   • «Обшивка труб / стояків гіпсокартоном» — «Монтаж ніші під прихований карниз короб під
--     комунікації» is that job, and it is the row a master already prices.
--   • «Монтаж дворівневої / багаторівневої стелі» — that is a flat ceiling plus a короб by the
--     linear metre, both of which we sell; one m² position on top of them double-bills.
--   • «Монтаж додаткового шару ГКЛ» — genuinely absent, but his own 800 → 900 for a partition in
--     1 vs 2 layers implies 50 ₴/м² per extra face, which is too low to ship as a number he might
--     bill a client with. Left for him to say. See open-questions.md.
--
-- Only the tape joins a default bundle («Звукоізоляція та утеплення», opening its framed half —
-- the profile is taped before the frame is built). The other three are stand-alone jobs and a
-- bundle is a sequence, not a shelf.
--
-- Catalog version 15 (V116/V117 shipped 14). Like V117 this tops up an UNDISMISSED COUNT notice
-- rather than queueing a second one: V116-V120 all reach production in the same deploy, so the
-- master must see ONE «каталог оновлено», not three.
--
-- PART 3 — re-rank. V118 made catalog_templates.sort_order a dense, distinct, trade-clustered
-- ranking and its self-check refuses rank 0. Inserting a template therefore REQUIRES re-running
-- that ranking (and the renumbering of every master's catalog_items derived from it) — the new rows
-- would otherwise sit at the column DEFAULT 0, which is precisely the defect V118 cleaned up.
-- =================================================================================================

DO $$
DECLARE
    v_version   int := 15;
    v_added     int := 0;
    v_notices   int := 0;
    v_missing   text;
    v_bundle_id uuid;
BEGIN
    -- =============================================================================================
    -- PART 1. The three prices.
    -- =============================================================================================
    CREATE TEMP TABLE _reprice (
        trade      varchar(50),
        name       varchar(255),
        type       varchar(20),
        unit       varchar(20),
        old_price  numeric(15,2),
        new_price  numeric(15,2)
    ) ON COMMIT DROP;

    INSERT INTO _reprice VALUES
        ('PAINTER', 'Каркасна звукоізоляція ГКЛ два слоя стелі',         'WORK', 'M2',            80.00,  850.00),
        ('PAINTER', 'Каркасна звукоізоляція ГКЛ два слоя стін',          'WORK', 'M2',            60.00,  650.00),
        ('BUILDER', 'Облаштування дверного пройому звуження розширення', 'WORK', 'PIECE',        800.00, 1350.00),
        ('PAINTER', 'Армування стиків ГКЛ',                              'WORK', 'LINEAR_METER',  75.00,  100.00);

    -- Every row must still be there at the price we think we are correcting. If one has moved, the
    -- master's word was given about a number that no longer exists and the notices below would lie.
    SELECT string_agg(r.trade || ' / ' || r.name, '; ') INTO v_missing
    FROM _reprice r
    WHERE NOT EXISTS (
        SELECT 1 FROM catalog_templates ct
        WHERE ct.trade = r.trade AND lower(trim(ct.name)) = lower(trim(r.name))
          AND ct.type = r.type AND ct.unit = r.unit AND ct.suggested_price = r.old_price);
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'V120: repriced position(s) not found at the expected old price: %', v_missing;
    END IF;

    UPDATE catalog_templates ct
    SET suggested_price = r.new_price
    FROM _reprice r
    WHERE ct.trade = r.trade AND lower(trim(ct.name)) = lower(trim(r.name))
      AND ct.type = r.type AND ct.unit = r.unit;

    -- One notice per master still holding OUR old number on a LIBRARY row. A master who re-priced
    -- the position himself gets nothing: his number is his, and CatalogTemplateService.accept
    -- would refuse to touch it anyway.
    INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed,
                                        position_name, old_price, new_price)
    SELECT gen_random_uuid(), ci.owner_id, 'PRICE_DRIFT', 0, 0, r.name, r.old_price, r.new_price
    FROM _reprice r
    JOIN catalog_items ci
      ON lower(trim(ci.name)) = lower(trim(r.name))
     AND ci.type = r.type AND ci.unit = r.unit
    WHERE ci.source = 'LIBRARY' AND ci.default_price = r.old_price;
    GET DIAGNOSTICS v_notices = ROW_COUNT;

    -- =============================================================================================
    -- PART 2. Four positions.
    -- =============================================================================================
    INSERT INTO catalog_templates (id, trade, category, name, type, unit,
                                   suggested_price, added_in_version, description)
    VALUES
        (gen_random_uuid(), 'DRYWALL', 'Каркас і обшивка',
         'Монтаж сухої збірної підлоги з гіпсоволокна', 'WORK', 'M2', 430.00, v_version,
         'Вирівнювальна засипка та збірний настил з гіпсоволокнистих листів у два шари. ' ||
         'Матеріал рахується окремо.'),

        (gen_random_uuid(), 'DRYWALL', 'Каркас і обшивка',
         'Ремонт ділянки конструкції з гіпсокартону', 'WORK', 'M2', 600.00, v_version,
         'Демонтаж пошкодженої ділянки, підгонка і монтаж нового аркуша, заповнення стиків. ' ||
         'Шпаклювання і фарбування рахуються окремо.'),

        (gen_random_uuid(), 'DRYWALL', 'Каркас і обшивка',
         'Установка люка-ревізії простого', 'WORK', 'PIECE', 400.00, v_version, NULL),

        (gen_random_uuid(), 'DRYWALL', 'Звукоізоляція та утеплення',
         'Монтаж ущільнювальної стрічки на профіль', 'WORK', 'LINEAR_METER', 50.00, v_version,
         'Стрічка між профілем і основою розриває передачу звуку з перекриття в каркас. ' ||
         'Рахується по довжині профілю, що прилягає до стін, стелі й підлоги.');

    -- The tape is the only one of the four that belongs to a sequence we ship: it goes on the
    -- profile BEFORE the frame is built, so it opens the framed half of «Звукоізоляція та
    -- утеплення» (a default bundle is a SEQUENCE — sort_order is the content). The other three are
    -- stand-alone jobs: a dry floor is its own contract, a repair is ad-hoc, and a hatch is priced
    -- per piece when the object happens to need one.
    SELECT id INTO v_bundle_id
    FROM estimate_templates
    WHERE is_default AND trade = 'DRYWALL' AND name = 'Звукоізоляція та утеплення';

    IF v_bundle_id IS NULL THEN
        RAISE EXCEPTION 'V120: the soundproofing bundle V116 created is gone';
    END IF;

    UPDATE estimate_template_items
    SET sort_order = sort_order + 1
    WHERE template_id = v_bundle_id AND sort_order >= 3;

    INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
    VALUES (gen_random_uuid(), v_bundle_id,
            'Монтаж ущільнювальної стрічки на профіль', 'WORK', 'LINEAR_METER', 3);

    -- A bundle line carries no price: it resolves at apply time against the master's own catalog
    -- on lower(trim(name)). A name that does not match applies the line at 0 ₴, silently.
    IF NOT EXISTS (SELECT 1 FROM estimate_template_items i
                   JOIN catalog_templates ct ON ct.trade = 'DRYWALL' AND ct.name = i.name
                                            AND ct.type = i.type AND ct.unit = i.unit
                   WHERE i.template_id = v_bundle_id
                     AND i.name = 'Монтаж ущільнювальної стрічки на профіль') THEN
        RAISE EXCEPTION 'V120: the bundle line does not resolve to a catalog position (0 UAH on apply)';
    END IF;

    IF (SELECT count(DISTINCT sort_order) FROM estimate_template_items WHERE template_id = v_bundle_id)
       <> (SELECT count(*) FROM estimate_template_items WHERE template_id = v_bundle_id) THEN
        RAISE EXCEPTION 'V120: two lines of the soundproofing bundle share a sort_order';
    END IF;

    -- The masters who already have the trade. Same dedup key as
    -- ux_catalog_items_owner_name_type_unit: an existing row of any source or price wins, so a
    -- master who typed one of these himself keeps his own wording and his own number.
    CREATE TEMP TABLE _added ON COMMIT DROP AS
    SELECT gen_random_uuid() AS id, t.user_id AS owner_id, ct.id AS template_id
    FROM (SELECT DISTINCT user_id FROM user_trades WHERE trade = 'DRYWALL') t
    CROSS JOIN catalog_templates ct
    WHERE ct.trade = 'DRYWALL' AND ct.added_in_version = v_version
      AND NOT EXISTS (
          SELECT 1 FROM catalog_items ci
          WHERE ci.owner_id = t.user_id
            AND lower(trim(ci.name)) = lower(trim(ct.name))
            AND ci.type = ct.type AND ci.unit = ct.unit);

    INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price,
                               category, trade, source, description)
    SELECT a.id, a.owner_id, ct.name, ct.type, ct.unit, ct.suggested_price,
           ct.category, 'DRYWALL', 'LIBRARY', ct.description
    FROM _added a JOIN catalog_templates ct ON ct.id = a.template_id;
    GET DIAGNOSTICS v_added = ROW_COUNT;

    -- One deploy, one notice — V116/V117/V120 all land together and their COUNT notices would
    -- otherwise read as three separate catalog updates. Only an undismissed notice is topped up;
    -- a master who has already dismissed his (or registered in between) gets a fresh one.
    UPDATE catalog_update_notices n
    SET positions_added = n.positions_added + c.added
    FROM (SELECT owner_id, count(*) AS added FROM _added GROUP BY owner_id) c
    WHERE n.user_id = c.owner_id AND n.kind = 'COUNT' AND n.dismissed_at IS NULL;

    INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed)
    SELECT c.id, c.owner_id, 'COUNT', c.added, 0
    FROM (SELECT gen_random_uuid() AS id, owner_id, count(*) AS added
          FROM _added GROUP BY owner_id) c
    WHERE NOT EXISTS (SELECT 1 FROM catalog_update_notices n
                      WHERE n.user_id = c.owner_id AND n.kind = 'COUNT' AND n.dismissed_at IS NULL);

    UPDATE users u
    SET last_synced_catalog_version = (SELECT MAX(added_in_version) FROM catalog_templates)
    WHERE EXISTS (SELECT 1 FROM user_trades ut WHERE ut.user_id = u.id AND ut.trade = 'DRYWALL');

    RAISE NOTICE 'V120: % price notice(s), % catalog row(s) added, DRYWALL now at % positions',
        v_notices, v_added,
        (SELECT count(*) FROM catalog_templates WHERE trade = 'DRYWALL');
END $$;

-- -------------------------------------------------------------------------------------------------
-- PART 3 — re-rank, exactly as V118 PART 3/4 did.
--
-- This is not optional bookkeeping: V118 turned sort_order into the library's statement of the
-- order the work is done in, and pinned "no template at rank 0, no two templates sharing one" as a
-- self-check. Four new rows on the column DEFAULT would break both, and would land in the master's
-- catalog wherever PostgreSQL felt like putting them. Any future migration that INSERTs a template
-- has to end this way too.
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
-- PART 4 — self-checks. The migration refuses to land half-done.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    v_bad    int;
    v_phases text;
BEGIN
    IF EXISTS (SELECT 1 FROM catalog_templates
               WHERE trade = 'PAINTER' AND unit = 'M2' AND suggested_price IN (80.00, 60.00)
                 AND name LIKE 'Каркасна звукоізоляція%') THEN
        RAISE EXCEPTION 'V120: the framed soundproofing rows still carry the unframed price';
    END IF;

    SELECT count(*) INTO v_bad FROM catalog_templates
    WHERE (trade = 'BUILDER' AND name = 'Облаштування дверного пройому звуження розширення'
           AND suggested_price <> 1350.00)
       OR (trade = 'PAINTER' AND name = 'Армування стиків ГКЛ' AND suggested_price <> 100.00);
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V120: % repriced row(s) did not take the new number', v_bad;
    END IF;

    -- A master must never be offered the same change twice: acceptUpdateNotice matches on
    -- CatalogNameKey + the old price, so two pending notices for one position would both fire and
    -- the second would silently do nothing after the first moved the price.
    SELECT count(*) INTO v_bad FROM (
        SELECT user_id, position_name FROM catalog_update_notices
        WHERE kind = 'PRICE_DRIFT' AND dismissed_at IS NULL
        GROUP BY user_id, position_name HAVING count(*) > 1) d;
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V120: % master(s) hold two pending notices for one position', v_bad;
    END IF;

    -- The four new positions have to be reachable: inside DRYWALL's phase sequence, priced, and
    -- copied to every master who has the trade.
    SELECT string_agg(DISTINCT category, ', ' ORDER BY category)
    INTO v_phases FROM catalog_templates WHERE trade = 'DRYWALL';
    IF v_phases <> 'Звукоізоляція та утеплення, Каркас і обшивка, Надбавки, '
                || 'Оздоблення під фарбування, Підготовка та захист' THEN
        RAISE EXCEPTION 'V120: a new position landed outside the phase sequence: %', v_phases;
    END IF;

    IF EXISTS (SELECT 1 FROM catalog_templates
               WHERE trade = 'DRYWALL' AND added_in_version = 15 AND suggested_price = 0) THEN
        RAISE EXCEPTION 'V120: a new position ships at 0 ₴ — it would put a zero line in an estimate';
    END IF;

    SELECT count(*) INTO v_bad
    FROM (SELECT DISTINCT user_id FROM user_trades WHERE trade = 'DRYWALL') t
    CROSS JOIN catalog_templates ct
    WHERE ct.trade = 'DRYWALL' AND ct.added_in_version = 15
      AND NOT EXISTS (SELECT 1 FROM catalog_items ci
                      WHERE ci.owner_id = t.user_id
                        AND lower(trim(ci.name)) = lower(trim(ct.name))
                        AND ci.type = ct.type AND ci.unit = ct.unit);
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V120: % (master, new position) pair(s) were not copied', v_bad;
    END IF;

    -- V118's ranking invariants, re-pinned after the insert.
    SELECT count(*) INTO v_bad FROM catalog_templates WHERE sort_order = 0;
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V120: % templates were left unranked', v_bad;
    END IF;
    SELECT count(*) INTO v_bad FROM (
        SELECT sort_order FROM catalog_templates GROUP BY sort_order HAVING count(*) > 1) d;
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V120: % template ranks are shared by more than one row', v_bad;
    END IF;
    SELECT count(*) INTO v_bad FROM (
        SELECT owner_id, sort_order FROM catalog_items
        GROUP BY owner_id, sort_order HAVING count(*) > 1) d;
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V120: % (owner, sort_order) pairs are shared by more than one item', v_bad;
    END IF;
END $$;
