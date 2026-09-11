-- =================================================================================================
-- V132 - a position two trades ship is STORED under the trade whose work the master actually does.
--
-- V116 PART 7 copied ten painting and demolition positions into the DRYWALL library, because a
-- drywall master really does prime, putty and sand what he has just sheathed:
--
--     Демонтаж гіпсокартонної стелі, Демонтаж перегородки з гіпсокартону, Захист підлоги картоном,
--     Грунтування, Базове шпаклювання під скловолокно, Шліфування під скловолокно/склохолст,
--     Обезпилення поверхні, Поклейка склополотна, Шпаклювання фінішне (2-4 рази),
--     Шліфування стін/стель (фінішне)
--
-- `catalog_items` holds ONE row per (owner, name, type, unit) - the trade is not in that unique
-- index - so a master who has both trades stores each of those positions exactly once, under
-- whichever trade claimed it first. V118's seeding array puts PAINTER before DRYWALL, so for a
-- DRYWALL+PAINTER master it is ALWAYS the painting copy that lands. That is not incidental: it is
-- deterministic, and it is how every such master's catalog reads today.
--
-- Until now that cost nothing, because `sharedTrades` shows the one row under BOTH chips and this
-- migration does not change that: the display is a READ-path fact assembled from the library (V118),
-- not from this column. What it cost was the MATERIAL CALCULATOR. An estimate line snapshots the
-- catalog row's trade (V125), the calculator now answers only with norms of the POSITION's own trade
-- (same round), and so a real drywall master's «Шпаклювання фінішне» - filed under PAINTER through
-- no decision of his - would find no DRYWALL norm and quietly buy him nothing. The master named this
-- risk himself while we were agreeing the calculator change.
--
-- So: the SAME row, re-filed. No row is inserted, none is deleted, nothing is duplicated.
--
-- WHY IT CANNOT COLLIDE. `ux_catalog_items_owner_name_type_unit` is (owner, lower(trim(name)), type,
-- unit) - the trade is absent from it, so changing the trade can never violate it. The row a
-- collision would need (the same master holding a second copy of the same name/type/unit) cannot
-- exist in the first place.
--
-- WHO IS TOUCHED. Only a master who HAS the DRYWALL trade (`user_trades`). For a painter who does
-- no drywall, «Шпаклювання фінішне» is a painting position and nothing here is a correction - that
-- is exactly the master's ruling that a painting position must not pull drywall materials in.
--
-- WHY `source` IS NOT FILTERED ON. It would be the obvious guard - «only touch rows WE copied» - and
-- it is the wrong one twice over. V79 backfilled that column by a HEURISTIC (a library copy is
-- written as one large batch), so an early master's shipped row can read MANUAL and would be skipped
-- for no reason. And the filter below is narrower than `source` anyway: the row's CURRENT trade must
-- be one of the other trades that ship this very name, type and unit in the library. A position the
-- master invented himself cannot match that by accident, and for the one that does match, the
-- visible catalog does not change at all.
--
-- THE ESTIMATE LINES ARE A SEPARATE FACT (section 4). `estimate_items.trade` is a SNAPSHOT (V125),
-- so re-filing the catalog does not reach the lines already authored - and a DRAFT estimate is
-- exactly where the master opens «Матеріали». Those are re-derived, mirroring
-- `EstimateService.resolveTrade` (owner's catalog, lower(trim(name)) + type + unit). A SIGNED
-- estimate is NOT touched: the client signed that sheet and it is immutable. The consequence is
-- worth stating plainly - a signed estimate keeps whatever trade it was authored with, so the
-- calculator can still answer nothing for one of its painting-filed lines. That is the immutability
-- rule doing its job, not a gap.
--
-- V118's RANKING IS RE-RUN (section 3) even though no `catalog_templates` row is inserted: the rows
-- above change CATEGORY as well as trade, and a row's rank is looked up by (trade, category). Leave
-- it out and a re-filed position keeps the rank of a category it is no longer in, which is the
-- «категорії не в порядку виконання робіт» complaint all over again.
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 0. What the catalog looks like before we touch it. Section 5 compares against this: the whole
--    claim of this migration is that it moves rows and creates none.
-- -------------------------------------------------------------------------------------------------
CREATE TEMP TABLE v132_before AS
SELECT count(*) AS items FROM catalog_items;

-- -------------------------------------------------------------------------------------------------
-- 1. The set, derived FROM THE LIBRARY rather than listed here: a DRYWALL position whose name, type
--    and unit another trade also ships. Ten of them today (V116 PART 7). Hardcoding the ten
--    Ukrainian strings would look clearer and would silently stop matching the day one of them is
--    renamed by a later rebuild - the same trap V123 exists to close.
-- -------------------------------------------------------------------------------------------------
CREATE TEMP TABLE v132_shared AS
SELECT DISTINCT
       lower(trim(d.name)) AS name_key,
       d.type,
       d.unit,
       d.category          AS drywall_category,
       o.trade             AS other_trade
  FROM catalog_templates d
  JOIN catalog_templates o
    ON lower(trim(o.name)) = lower(trim(d.name))
   AND o.type = d.type
   AND o.unit = d.unit
   AND o.trade <> 'DRYWALL'
 WHERE d.trade = 'DRYWALL';

-- The library must answer with ONE category per shared position, or the UPDATE below would pick
-- arbitrarily between two. Checked before anything is written.
DO $$
DECLARE
    ambiguous text;
BEGIN
    SELECT string_agg(DISTINCT name_key, ', ') INTO ambiguous
      FROM (SELECT name_key, type, unit
              FROM v132_shared
             GROUP BY name_key, type, unit
            HAVING count(DISTINCT drywall_category) > 1) AS a;
    IF ambiguous IS NOT NULL THEN
        RAISE EXCEPTION 'V132: the DRYWALL library files these positions under two categories: %',
            ambiguous;
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 2. The rows to re-file, captured BEFORE the update so section 4 and the self-checks can still
--    find them (afterwards their trade no longer tells you they were moved).
-- -------------------------------------------------------------------------------------------------
CREATE TEMP TABLE v132_refiled AS
SELECT ci.id, ci.owner_id, ci.name, ci.type, ci.unit,
       ci.trade    AS old_trade,
       ci.category AS old_category,
       s.drywall_category AS new_category
  FROM catalog_items ci
  JOIN v132_shared s
    ON s.name_key = lower(trim(ci.name))
   AND s.type  = ci.type
   AND s.unit  = ci.unit
   AND s.other_trade = ci.trade
 WHERE ci.custom_trade_id IS NULL          -- a custom trade means trade = OTHER by invariant (V91)
   AND EXISTS (SELECT 1 FROM user_trades ut
                WHERE ut.user_id = ci.owner_id AND ut.trade = 'DRYWALL');

UPDATE catalog_items ci
   SET trade    = 'DRYWALL',
       category = r.new_category
  FROM v132_refiled r
 WHERE ci.id = r.id;

-- -------------------------------------------------------------------------------------------------
-- 3. V118 PART 4, verbatim. A row the library ships takes its template's rank; a master's own row
--    takes the first library rank in the same (trade, category); a category we ship nothing for
--    goes last, alphabetically. Overwriting the column wholesale is safe for the same reason V118
--    gave: the catalog board has never had drag grips, and PUT /api/catalog/items/order has no
--    caller in the app.
-- -------------------------------------------------------------------------------------------------
WITH item_key AS (
    SELECT ci.id, ci.owner_id, ci.name,
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
-- 4. The lines already authored. Mirrors EstimateService.resolveTrade: the owner's own catalog,
--    matched on lower(trim(name)) + type + unit, and the catalog's answer is taken as it stands.
--    NON-SIGNED estimates only - see the header.
-- -------------------------------------------------------------------------------------------------
WITH match AS (
    SELECT ei.id AS item_id, ci.trade AS catalog_trade
      FROM estimate_items ei
      JOIN estimates e ON e.id = ei.estimate_id
      JOIN projects  p ON p.id = e.project_id
      JOIN v132_refiled r ON r.owner_id = p.owner_id
                         AND r.type = ei.type
                         AND r.unit = ei.unit
                         AND lower(trim(r.name)) = lower(trim(ei.name))
      JOIN catalog_items ci ON ci.id = r.id
     WHERE e.status <> 'SIGNED'
)
UPDATE estimate_items ei
   SET trade = m.catalog_trade
  FROM match m
 WHERE ei.id = m.item_id;

-- -------------------------------------------------------------------------------------------------
-- 5. Self-checks. A green Testcontainers boot IS the assertion for a data-only migration, so these
--    are phrased as OUTCOMES ("no mis-filed row remains") rather than as a row count that a later
--    library change would turn into a false alarm.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    left_behind int;
    before_rows int;
    after_rows  int;
    gaps        int;
    broken      int;
    stale_lines int;
BEGIN
    -- 5a. The outcome itself: for a master who has DRYWALL, not one shared position is still filed
    --     under the other trade.
    SELECT count(*) INTO left_behind
      FROM catalog_items ci
      JOIN v132_shared s
        ON s.name_key = lower(trim(ci.name))
       AND s.type = ci.type AND s.unit = ci.unit AND s.other_trade = ci.trade
     WHERE ci.custom_trade_id IS NULL
       AND EXISTS (SELECT 1 FROM user_trades ut
                    WHERE ut.user_id = ci.owner_id AND ut.trade = 'DRYWALL');
    IF left_behind > 0 THEN
        RAISE EXCEPTION 'V132: % shared positions are still filed under another trade', left_behind;
    END IF;

    -- 5b. Nothing was created and nothing was lost - the one claim the header makes twice.
    SELECT items INTO before_rows FROM v132_before;
    SELECT count(*) INTO after_rows FROM catalog_items;
    IF before_rows <> after_rows THEN
        RAISE EXCEPTION 'V132: catalog_items went from % rows to % - this migration moves rows only',
            before_rows, after_rows;
    END IF;

    -- 5c. V118's invariant: sort_order is a gapless 0-based sequence within each master's catalog.
    SELECT count(*) INTO gaps
      FROM (SELECT owner_id
              FROM catalog_items
             GROUP BY owner_id
            HAVING min(sort_order) <> 0
                OR max(sort_order) <> count(*) - 1
                OR count(DISTINCT sort_order) <> count(*)) AS g;
    IF gaps > 0 THEN
        RAISE EXCEPTION 'V132: % masters have a broken sort_order sequence after renumbering', gaps;
    END IF;

    -- 5d. The V91 invariant the WHERE clause above relies on, re-checked rather than trusted.
    SELECT count(*) INTO broken
      FROM catalog_items WHERE custom_trade_id IS NOT NULL AND trade <> 'OTHER';
    IF broken > 0 THEN
        RAISE EXCEPTION 'V132: % rows carry a custom trade without trade = OTHER', broken;
    END IF;

    -- 5e. No DRAFT line is left pointing at the trade the catalog no longer files it under - that
    --     stale snapshot is precisely what would still lose the master his materials.
    SELECT count(*) INTO stale_lines
      FROM estimate_items ei
      JOIN estimates e ON e.id = ei.estimate_id
      JOIN projects  p ON p.id = e.project_id
      JOIN v132_refiled r ON r.owner_id = p.owner_id
                         AND r.type = ei.type AND r.unit = ei.unit
                         AND lower(trim(r.name)) = lower(trim(ei.name))
     WHERE e.status <> 'SIGNED'
       AND ei.trade IS DISTINCT FROM 'DRYWALL';
    IF stale_lines > 0 THEN
        RAISE EXCEPTION 'V132: % unsigned estimate lines still carry the old trade', stale_lines;
    END IF;
END $$;

DROP TABLE v132_before;
DROP TABLE v132_shared;
DROP TABLE v132_refiled;
