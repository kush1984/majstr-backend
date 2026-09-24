-- =================================================================================================
-- V140 - the lines a shared catalog position filed under somebody else's trade, in DRAFTS only.
--
-- THE BUG, in one sentence: `catalog_items` holds ONE row per (owner, name, type, unit) - V118's
-- rule - so a position two of the master's trades both ship is stored once, under whichever trade
-- claimed it first; and every door that built an estimate line copied that row's `trade` and
-- `category` verbatim. Applying a PAINTER bundle therefore produced «Шпаклювання фінішне» filed
-- under DRYWALL / «Оздоблення під фарбування» and «Прибирання приміщення» under TILING, so a
-- painting estimate grew category headers from two trades the master was not working in: «якісь не
-- зрозумілі категорії з плитки, гіпсокартону». The same stamp is what the material calculator
-- filters norms by, so a foreign trade also silently costs the line its own materials.
--
-- The code fix is `CatalogFiling`, and it is forward-only: an estimate already written carries the
-- stamp it was given. This file repairs what is still being worked on.
--
-- WHY DRAFTS ONLY (the master's ruling). A SIGNED estimate is a snapshot of what the client signed
-- and is never rewritten. A SENT one is already open on the client's phone: the names and the money
-- would not change here - only the section headings - but a document changing under a reader who
-- was invited to read it is not something to do for tidiness. A draft is the master's own working
-- copy, and it is the one he is looking at.
--
-- WHAT IT WILL NOT TOUCH, and each exclusion is a refusal to guess:
--   * a line with NO trade. NULL is a legitimate, deliberate value (V125): a hand-typed line and an
--     ADDENDUM row carry it, and inventing a trade for them would put a master's own wording into a
--     folder he never chose.
--   * an estimate with no clear majority. The bundle that produced a line is not recorded anywhere,
--     so the trade the master was working in has to be inferred - and the only honest evidence is
--     that most of the document agrees. Below half, this file has no opinion.
--   * a name the majority trade does not ship. The re-filing is only ever «the library says this
--     position is ALSO yours, filed here»; a name it does not carry gets no new home from us.
-- So the change is bounded: a line already agreeing with the majority is untouched, and every line
-- that moves moves to a folder the shipped library itself names.
-- =================================================================================================

DO $$
DECLARE
    moved int;
BEGIN
    WITH lib AS (
        -- The library keyed exactly as NameKeys.of() keys it in Java - collapse whitespace, tidy
        -- the stray space inside brackets, trim, lower. A key computed two ways is a join that
        -- silently matches nothing (the V112 lesson).
        -- DISTINCT ON because a trade may ship one name twice: the join below would then pick a
        -- category at the planner's discretion. The library's own order decides, like everywhere
        -- else (V118), so this file and the catalog board agree on which folder is «the» one.
        SELECT DISTINCT ON (name_key, type, unit, trade) name_key, type, unit, trade, category
          FROM (
            SELECT lower(btrim(regexp_replace(replace(replace(name, '( ', '('), ' )', ')'),
                                              '\s+', ' ', 'g'))) AS name_key,
                   type, unit, trade, category, sort_order
              FROM catalog_templates
          ) t
         ORDER BY name_key, type, unit, trade, sort_order
    ),
    line_key AS (
        SELECT i.id, i.estimate_id, i.trade, i.type, i.unit,
               lower(btrim(regexp_replace(replace(replace(i.name, '( ', '('), ' )', ')'),
                                          '\s+', ' ', 'g'))) AS name_key
          FROM estimate_items i
          JOIN estimates e ON e.id = i.estimate_id
         WHERE e.status = 'DRAFT'
    ),
    majority AS (
        -- The trade most of the document is written in, and only when it is genuinely most: at
        -- least half of the lines that name a trade at all. Ties break on the trade name so the
        -- migration is deterministic, never on whatever the planner returns first.
        SELECT estimate_id, trade
          FROM (
            SELECT estimate_id, trade, count(*) AS own,
                   sum(count(*)) OVER (PARTITION BY estimate_id) AS total,
                   row_number() OVER (PARTITION BY estimate_id
                                          ORDER BY count(*) DESC, trade) AS rn
              FROM line_key
             WHERE trade IS NOT NULL
             GROUP BY estimate_id, trade
          ) ranked
         WHERE rn = 1 AND own * 2 >= total
    )
    UPDATE estimate_items i
       SET trade = m.trade,
           category = COALESCE(l.category, i.category)
      FROM line_key k
      JOIN majority m ON m.estimate_id = k.estimate_id
      JOIN lib l ON l.name_key = k.name_key AND l.type = k.type AND l.unit = k.unit
                AND l.trade = m.trade
     WHERE i.id = k.id
       AND k.trade IS NOT NULL
       AND k.trade <> m.trade;

    GET DIAGNOSTICS moved = ROW_COUNT;
    RAISE NOTICE 'V140: re-filed % draft estimate line(s) under the trade their estimate is written in', moved;
END $$;
