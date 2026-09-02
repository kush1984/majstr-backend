-- =================================================================================================
-- V118 — the catalog has an ORDER, and DRYWALL's first phase gets the name the master gave it.
--
-- Master feedback on two screenshots of his own catalog, all of it on the ГІПСОКАРТОН chip:
--   «Підготовка, Підготовка та захист, Оздоблення — це впринципі одна і та сама категорія
--    -> Підготовка та захист»
--   «що тут робить категорія Шпалери?» / «Звукоізоляція і Кладка — треба прибрати тут взагалі»
--   «позиції нам треба посортувати — перше беремо Підготовку, а потім вже роботи»
--
-- Only two of those are data problems. The folders he could not explain (Шпалери, Оздоблення,
-- Шпаклювання та шліфування, Звукоізоляція) are rows V116 PART 7 deliberately copied VERBATIM from
-- PAINTER/DEMOLITION into DRYWALL phases: his own copy is stored under PAINTER, so the DRYWALL chip
-- shows it under PAINTER's category. That is fixed in the READ path (CatalogItemResponse now carries
-- the category each sharing trade files a position under) — not here, because re-filing the row
-- would only move the same problem onto the PAINTER chip.
--
-- What IS a data problem, and what this migration does:
--   PART 1  rename DRYWALL's «Підготовка» phase to «Підготовка та захист» (his wording).
--   PART 2  re-home LIBRARY rows orphaned by an earlier rebuild (his «Кладка» pair: V116 retired
--           those names from DRYWALL, BUILDER still ships them, he has BUILDER).
--   PART 3  catalog_templates.sort_order — the library finally states the order the work is done in.
--   PART 4  renumber every master's catalog_items.sort_order from it.
--   PART 5  self-check.
--
-- Why PART 3/4 exist at all: V87 backfilled sort_order ONCE, alphabetically, and nothing has
-- maintained it since. Every row a master received afterwards — from a migration or from
-- «Додати нові з каталогу» — landed on the DEFAULT 0 (CatalogTemplateService.missingItems never set
-- it; fixed in the same iteration). 145 of this master's 935 rows sit at 0, so PostgreSQL returned
-- them in whatever order it liked and the categories they belong to floated to the top of the page.
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- PART 1 — «Підготовка» → «Підготовка та захист», DRYWALL only.
--
-- The name now collides with PAINTER's category of the same name. That is deliberate and harmless:
-- catalog_items groups by category NAME, so a master running both trades sees one prep folder
-- instead of two — which is exactly what he asked for.
--
-- V116 PART 11 pinned the old name in a self-check. It has already run and is checksummed; this
-- migration supersedes it, and PART 5 below re-pins the new set.
-- -------------------------------------------------------------------------------------------------
UPDATE catalog_templates SET category = 'Підготовка та захист'
WHERE trade = 'DRYWALL' AND category = 'Підготовка';

UPDATE catalog_items SET category = 'Підготовка та захист'
WHERE trade = 'DRYWALL' AND source = 'LIBRARY' AND category = 'Підготовка';

-- -------------------------------------------------------------------------------------------------
-- PART 2 — orphaned LIBRARY rows go back to a trade that still ships them.
--
-- A rebuild that RETIRES a name (V116 dropped seven «Кладка» rows from DRYWALL) leaves every master
-- who already owned the copy holding a row whose stored trade no longer recognizes it. It then sits
-- under that trade's chip forever, in a category the trade no longer has — «Кладка» on the drywall
-- screen, which is what the master was looking at.
--
-- Deliberately narrow: only a LIBRARY row, only when its stored trade no longer ships the name, and
-- only when EXACTLY ONE trade the master actually has still does. Two candidates means we would be
-- guessing, and a guess here silently moves a priced position out of the master's sight.
-- -------------------------------------------------------------------------------------------------
WITH candidate AS (
    SELECT ci.id AS item_id, ct.trade AS new_trade, ct.category AS new_category
    FROM catalog_items ci
    JOIN user_trades ut ON ut.user_id = ci.owner_id
    JOIN catalog_templates ct
      ON ct.trade = ut.trade
     AND lower(trim(ct.name)) = lower(trim(ci.name))
     AND ct.type = ci.type
     AND ct.unit = ci.unit
    WHERE ci.source = 'LIBRARY'
      AND ut.trade <> ci.trade
      AND NOT EXISTS (
          SELECT 1 FROM catalog_templates own
          WHERE own.trade = ci.trade
            AND lower(trim(own.name)) = lower(trim(ci.name))
            AND own.type = ci.type
            AND own.unit = ci.unit)
),
resolved AS (
    SELECT item_id, min(new_trade) AS new_trade, min(new_category) AS new_category
    FROM candidate
    GROUP BY item_id
    HAVING count(DISTINCT new_trade) = 1
)
UPDATE catalog_items ci
SET trade = r.new_trade, category = r.new_category
FROM resolved r
WHERE ci.id = r.item_id;

-- -------------------------------------------------------------------------------------------------
-- PART 3 — the library states its own order.
--
-- catalog_templates had no order at all, so «спочатку підготовка, потім роботи» was not expressible
-- anywhere: the only ordering that ever existed was V87's one-off alphabetical backfill of each
-- master's copy. The rank is GLOBAL (trade, then category rank, then name) rather than per-trade, so
-- a master running six trades gets his categories clustered by trade instead of interleaved.
--
-- Category rank is deliberately coarse — three named groups and one default:
--   demolition first, preparation next, the work itself in the middle, overheads and «Інше» last.
-- DRYWALL is the one trade with an explicit phase sequence, because V116 rebuilt it as one
-- («просто набір позицій, без будь-якої послідовності» is the failure mode it was fixing) and
-- alphabetical order would print Звукоізоляція before Каркас.
-- -------------------------------------------------------------------------------------------------
ALTER TABLE catalog_templates ADD COLUMN sort_order INT NOT NULL DEFAULT 0;

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

-- -------------------------------------------------------------------------------------------------
-- PART 4 — every master's own catalog is renumbered from it.
--
-- A row the library ships takes its template's rank. A row the master typed himself has no template,
-- so it takes the rank of the FIRST library row in the same (trade, category) — landing it inside
-- the folder it belongs to rather than at the end of the list. A row in a category we ship nothing
-- for goes after everything, alphabetically.
--
-- This overwrites any arrangement a master dragged into place. Nothing can have: the catalog board
-- has never had drag grips (CatalogBoard: «a catalog is a reference list a master searches and
-- prices, not one he arranges»), so PUT /api/catalog/items/order has no caller in the app.
-- -------------------------------------------------------------------------------------------------
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
-- PART 5 — self-check.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    v_phases text;
    v_zero   int;
    v_dupes  int;
BEGIN
    SELECT string_agg(DISTINCT category, ', ' ORDER BY category)
    INTO v_phases FROM catalog_templates WHERE trade = 'DRYWALL';
    IF v_phases <> 'Звукоізоляція та утеплення, Каркас і обшивка, Надбавки, '
                || 'Оздоблення під фарбування, Підготовка та захист' THEN
        RAISE EXCEPTION 'V118: unexpected DRYWALL categories after the rename: %', v_phases;
    END IF;

    -- Every template must now carry a distinct, non-zero rank; the whole point is that 0 stops
    -- meaning "never ordered".
    SELECT count(*) INTO v_zero FROM catalog_templates WHERE sort_order = 0;
    IF v_zero > 0 THEN
        RAISE EXCEPTION 'V118: % templates were left unranked', v_zero;
    END IF;
    SELECT count(*) INTO v_dupes FROM (
        SELECT sort_order FROM catalog_templates GROUP BY sort_order HAVING count(*) > 1) d;
    IF v_dupes > 0 THEN
        RAISE EXCEPTION 'V118: % template ranks are shared by more than one row', v_dupes;
    END IF;

    -- And no master may be left with two positions claiming the same slot.
    SELECT count(*) INTO v_dupes FROM (
        SELECT owner_id, sort_order FROM catalog_items
        GROUP BY owner_id, sort_order HAVING count(*) > 1) d;
    IF v_dupes > 0 THEN
        RAISE EXCEPTION 'V118: % (owner, sort_order) pairs are shared by more than one item', v_dupes;
    END IF;
END $$;
