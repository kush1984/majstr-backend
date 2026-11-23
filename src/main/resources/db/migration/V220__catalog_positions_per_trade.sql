-- =================================================================================================
-- V220 — every trade holds its own positions (iteration-trade-catalogs)
--
-- Until now a master's catalog held ONE row per (name, type, unit), whatever the trade (V49's
-- ux_catalog_items_owner_name_type_unit). A position the library ships under several trades —
-- «Прибирання приміщення після робіт» is shipped under nine — was copied once, under whichever
-- trade claimed it first, and every reader then had to guess which trade the master meant:
-- `sharedTrades` re-filed it on the catalog screen, CatalogFiling on the way into an estimate, and
-- every reader that forgot (the price list, a typed line, an import) put it under the wrong trade.
-- An electrician's price list grew a «Плитка» section that way (owner, 2026-11-23).
--
-- Now a trade's positions are that trade's rows: the key is (owner, trade, name, type, unit), and
-- the same name under two trades is two rows — each in its own trade's folder, each with its own
-- price (owner ruling 2026-11-23: a price changed under one trade does not move the other).
--
-- This migration gives every master the rows his trades were missing: for each trade on his
-- profile that the library ships a position under, while his only row of that name sits under
-- another trade, a copy is filed under his trade — in that trade's library folder, at HIS price,
-- with his comment and his price-list tick. Nothing is deleted or repriced. A library row under a
-- trade he does not have is hidden from then on like any other position of a trade off his profile.
-- =================================================================================================

DROP INDEX ux_catalog_items_owner_name_type_unit;

CREATE UNIQUE INDEX ux_catalog_items_owner_trade_name_type_unit
    ON catalog_items (owner_id, trade, lower(trim(name)), type, unit);

-- Where each trade files a name: the library's first row of that trade (its own order), the same
-- choice CatalogFiling.categoriesUnder makes.
CREATE TEMP TABLE v220_library ON COMMIT DROP AS
SELECT DISTINCT ON (trade, lower(trim(name)), type, unit)
       trade, lower(trim(name)) AS name_key, type, unit, category, sort_order
FROM catalog_templates
WHERE trade IS NOT NULL
ORDER BY trade, lower(trim(name)), type, unit, sort_order, id;

CREATE TEMP TABLE v220_copies ON COMMIT DROP AS
SELECT DISTINCT ON (c.owner_id, l.trade, l.name_key, c.type, c.unit)
       gen_random_uuid() AS id,
       c.owner_id, c.name, c.type, c.unit, c.default_price, c.description, c.note, c.in_price_list,
       l.trade, l.category, c.sort_order
FROM catalog_items c
JOIN v220_library l
  ON l.name_key = lower(trim(c.name)) AND l.type = c.type AND l.unit = c.unit AND l.trade <> c.trade
JOIN user_trades ut ON ut.user_id = c.owner_id AND ut.trade = l.trade
WHERE NOT EXISTS (
    SELECT 1 FROM catalog_items o
    WHERE o.owner_id = c.owner_id AND o.trade = l.trade
      AND lower(trim(o.name)) = l.name_key AND o.type = c.type AND o.unit = c.unit)
ORDER BY c.owner_id, l.trade, l.name_key, c.type, c.unit, c.id;

-- Each copy sits right after the master's last row, in the order his original rows had, so no
-- two rows share a slot (V118's reason for sort_order).
INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, created_at, category, trade,
                           source, sort_order, custom_trade_id, description, in_price_list, note)
SELECT v.id, v.owner_id, v.name, v.type, v.unit, v.default_price, now(), v.category, v.trade,
       'LIBRARY',
       m.next_sort + row_number() OVER (PARTITION BY v.owner_id ORDER BY v.sort_order, v.trade, v.name) - 1,
       NULL, v.description, v.in_price_list, v.note
FROM v220_copies v
JOIN (SELECT owner_id, COALESCE(MAX(sort_order) + 1, 0) AS next_sort
      FROM catalog_items GROUP BY owner_id) m ON m.owner_id = v.owner_id;

DO $$
DECLARE
    v_copied  int;
    v_missing int;
BEGIN
    SELECT count(*) INTO v_copied FROM v220_copies;
    RAISE NOTICE 'V220: % position(s) filed under the master''s own trade', v_copied;

    -- This migration's own promise: no master keeps a profile trade whose library position he
    -- holds only under another trade.
    SELECT count(*) INTO v_missing
    FROM catalog_items c
    JOIN v220_library l
      ON l.name_key = lower(trim(c.name)) AND l.type = c.type AND l.unit = c.unit AND l.trade <> c.trade
    JOIN user_trades ut ON ut.user_id = c.owner_id AND ut.trade = l.trade
    WHERE NOT EXISTS (
        SELECT 1 FROM catalog_items o
        WHERE o.owner_id = c.owner_id AND o.trade = l.trade
          AND lower(trim(o.name)) = l.name_key AND o.type = c.type AND o.unit = c.unit);
    IF v_missing > 0 THEN
        RAISE EXCEPTION 'V220: % position(s) still missing under a master''s own trade', v_missing;
    END IF;
END $$;
