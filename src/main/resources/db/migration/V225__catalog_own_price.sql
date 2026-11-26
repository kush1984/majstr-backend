-- =================================================================================================
-- V225 — a catalog price the master set himself counts in the market medians (plan-regional-prices.md)
--
-- Owner, 2026-11-26: masters mostly send a PDF rather than have the client sign, and many state their
-- rates in the catalog or bring them in as a price list — so the weekly medians (V94 national, V222
-- oblast) read the catalog too, not only estimate lines. Only a price that is HIS: every master gets
-- hundreds of library positions at our price, and counting those would make every median our own
-- library price.
--
-- own_price is set at write time from now on (create, edit, price-list import → true; seeding and
-- «Оновити мою ціну» on a PRICE_DRIFT notice → false). For the rows already there:
--   * MANUAL and IMPORT rows — the master typed or imported the price → true;
--   * LIBRARY rows — true only when the price differs from the same trade's library row AND is not
--     an old library price we told him about in a PRICE_DRIFT notice (a row left at an old library
--     price is still ours). A LIBRARY row whose library twin is gone stays false — nothing to tell by.
-- =================================================================================================

ALTER TABLE catalog_items ADD COLUMN own_price boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN catalog_items.own_price IS
    'The price is the master''s own (typed, edited or imported), not the library''s — only these count in the weekly market medians.';

UPDATE catalog_items SET own_price = true WHERE source IN ('MANUAL', 'IMPORT');

UPDATE catalog_items ci
SET own_price = true
WHERE ci.source = 'LIBRARY'
  AND ci.default_price > 0
  AND EXISTS (SELECT 1 FROM catalog_templates t
              WHERE t.trade = ci.trade AND t.type = ci.type AND t.unit = ci.unit
                AND lower(trim(t.name)) = lower(trim(ci.name)))
  AND NOT EXISTS (SELECT 1 FROM catalog_templates t
                  WHERE t.trade = ci.trade AND t.type = ci.type AND t.unit = ci.unit
                    AND lower(trim(t.name)) = lower(trim(ci.name))
                    AND t.suggested_price = ci.default_price)
  AND NOT EXISTS (SELECT 1 FROM catalog_update_notices n
                  WHERE n.user_id = ci.owner_id AND n.kind = 'PRICE_DRIFT'
                    AND lower(trim(n.position_name)) = lower(trim(ci.name))
                    AND n.old_price = ci.default_price);

DO $$
DECLARE
    own_library integer;
    own_total   integer;
BEGIN
    SELECT count(*) INTO own_library FROM catalog_items WHERE own_price AND source = 'LIBRARY';
    SELECT count(*) INTO own_total FROM catalog_items WHERE own_price;
    RAISE NOTICE 'V225: % catalog rows carry the master''s own price (% of them re-priced library rows)',
        own_total, own_library;
    IF EXISTS (SELECT 1 FROM catalog_items WHERE source IN ('MANUAL', 'IMPORT') AND NOT own_price) THEN
        RAISE EXCEPTION 'V225: a MANUAL/IMPORT catalog row was left without own_price';
    END IF;
END $$;
