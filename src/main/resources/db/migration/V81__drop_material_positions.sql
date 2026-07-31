-- Materials leave the DEFAULT catalog, in every trade. Works only from here on.
--
-- Why: the shipped material rows carried invented prices («Клей для плитки 25 кг — 380 ₴») that
-- nobody maintains, and the app already has a better answer — the receipt-photo import, which
-- adds materials with the REAL price from the shop. A stale guess competing with a real number
-- is worse than no guess. How materials come back is a separate decision, deliberately deferred.
--
-- This does NOT remove materials from the product: ItemType.MATERIAL stays, the works/materials
-- split in estimate totals stays, and the object economy (materials as passthrough, works as
-- earnings) stays. Only the ability to PICK a material from our list goes away.
--
-- ============ WHAT IS NEVER TOUCHED ============================================
-- Stated as constraints, not intentions, because a master's own work must survive this:
--
--   1. estimate_items — not referenced anywhere below. Estimate lines are snapshots with no FK
--      to the catalog, so every estimate ever written keeps every figure it was written with.
--   2. catalog_items the master added themselves — only `source = 'LIBRARY'` is deleted, i.e.
--      exactly the rows WE copied into their catalog. MANUAL (typed) and IMPORT (from a
--      price-list or receipt) are theirs and stay. This is what V79 exists for.
--   3. A master's own estimate templates — only `is_default` templates lose their material
--      lines. Anything a master built is theirs.
--   4. A LIBRARY row whose price the master CHANGED. Repricing is work: the row came from us,
--      but that number did not. Those rows stay, even though they are materials.
-- ==============================================================================

DO $$
DECLARE
    v_templates    int;
    v_master_ours  int;
    v_master_kept  int;
    v_tpl_items    int;
BEGIN
    -- (4) Rows the master repriced away from what we shipped. Matched the same way the catalog
    -- itself de-duplicates: name + type + unit. A row whose template no longer exists cannot be
    -- compared, so it is treated as untouched-by-the-master and removed with the rest.
    CREATE TEMP TABLE _repriced ON COMMIT DROP AS
    SELECT ci.id
    FROM catalog_items ci
    JOIN catalog_templates ct
      ON lower(ct.name) = lower(ci.name)
     AND ct.type = ci.type
     AND ct.unit = ci.unit
    WHERE ci.type = 'MATERIAL'
      AND ci.source = 'LIBRARY'
      AND ci.default_price IS DISTINCT FROM ct.suggested_price;

    SELECT COUNT(*) INTO v_master_kept FROM _repriced;

    DELETE FROM catalog_items ci
    WHERE ci.type = 'MATERIAL'
      AND ci.source = 'LIBRARY'
      AND ci.id NOT IN (SELECT id FROM _repriced);
    GET DIAGNOSTICS v_master_ours = ROW_COUNT;

    -- Default estimate templates must not point at positions the catalog no longer has: applying
    -- such a template would silently produce a line priced 0. Master-built templates are skipped.
    DELETE FROM estimate_template_items ti
    USING estimate_templates t
    WHERE t.id = ti.template_id
      AND t.is_default
      AND ti.type = 'MATERIAL';
    GET DIAGNOSTICS v_tpl_items = ROW_COUNT;

    DELETE FROM catalog_templates WHERE type = 'MATERIAL';
    GET DIAGNOSTICS v_templates = ROW_COUNT;

    -- Printed into the deploy log: a destructive migration should say what it destroyed.
    RAISE NOTICE 'V81 materials: default catalog -%, master LIBRARY copies -%, repriced kept %, default template items -%',
        v_templates, v_master_ours, v_master_kept, v_tpl_items;
END $$;
