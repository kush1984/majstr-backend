-- Bring V96's PAINTER additions to the masters who already registered — same reasoning as V83, but
-- lighter, because V96 was additive: nothing to reconcile for the ~148 positions that survived
-- untouched, only the 4 rows V96 removed and the 79 it added.
--
-- Scope: every user with PAINTER among their trades.
--
-- ============ WHAT IS NEVER TOUCHED ============================================
--   1. estimate_items — never referenced. Snapshots with no FK.
--   2. source <> 'LIBRARY' — a position the master typed in or imported is theirs.
--   3. a price the master changed — compared against painter_v10_removed_baseline, the price we
--      actually shipped for the row before V96 deleted it. If it differs, the master's own edit
--      stays even though we no longer ship that exact wording.
--   4. estimate_templates owned by a master (is_default = false) — not referenced at all.
--   5. every one of the ~148 PAINTER positions V96 left alone — this migration only reaches a
--      master's copy of the 4 removed rows and adds the 79 new ones; nothing else in their catalog
--      moves.
-- ==============================================================================================

DO $$
DECLARE
    v_removed  int;
    v_added    int;
    v_painters int;
BEGIN
    SELECT COUNT(*) INTO v_painters FROM user_trades WHERE trade = 'PAINTER';

    -- ---- 1. the four leftovers go, same rule as V83 --------------------------------------------
    CREATE TEMP TABLE _removed ON COMMIT DROP AS
    SELECT DISTINCT ci.id, ci.owner_id
    FROM catalog_items ci
    JOIN painter_v10_removed_baseline b
      ON b.name_key = lower(trim(ci.name))
     AND b.type = ci.type
     AND b.unit = ci.unit
    WHERE ci.source = 'LIBRARY'
      AND ci.default_price = b.suggested_price
      AND NOT EXISTS (
          SELECT 1 FROM catalog_templates ct
          WHERE lower(trim(ct.name)) = lower(trim(ci.name))
            AND ct.type = ci.type
            AND ct.unit = ci.unit);

    DELETE FROM catalog_items ci USING _removed r WHERE ci.id = r.id;
    GET DIAGNOSTICS v_removed = ROW_COUNT;

    -- ---- 2. the 79 new positions arrive ---------------------------------------------------------
    -- Keyed exactly like ux_catalog_items_owner_name_type_unit, same as V83: an existing row —
    -- whatever its source or price — blocks the insert rather than colliding with it.
    CREATE TEMP TABLE _added ON COMMIT DROP AS
    SELECT gen_random_uuid() AS id, t.user_id AS owner_id, ct.name, ct.type, ct.unit,
           ct.suggested_price AS default_price, ct.category
    FROM (SELECT DISTINCT user_id FROM user_trades WHERE trade = 'PAINTER') t
    CROSS JOIN catalog_templates ct
    WHERE ct.trade = 'PAINTER' AND ct.added_in_version = 10
      AND NOT EXISTS (
          SELECT 1 FROM catalog_items ci
          WHERE ci.owner_id = t.user_id
            AND lower(trim(ci.name)) = lower(trim(ct.name))
            AND ci.type = ct.type
            AND ci.unit = ct.unit);

    INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, category, trade, source)
    SELECT id, owner_id, name, type, unit, default_price, category, 'PAINTER', 'LIBRARY' FROM _added;
    GET DIAGNOSTICS v_added = ROW_COUNT;

    -- ---- 3. tell each master what happened to their list --------------------------------------
    -- catalog_update_notices is a queue since V94 (kind discriminates COUNT vs PRICE_DRIFT), not a
    -- single per-user slot — this just adds one more COUNT row, same as any master's other pending
    -- notices; nothing to merge or overwrite.
    INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed)
    SELECT gen_random_uuid(), owner_id, 'COUNT',
           COUNT(*) FILTER (WHERE src = 'add'), COUNT(*) FILTER (WHERE src = 'del')
    FROM (SELECT owner_id, 'add' AS src FROM _added
          UNION ALL
          SELECT owner_id, 'del' AS src FROM _removed) x
    GROUP BY owner_id;

    UPDATE users u
    SET last_synced_catalog_version = (SELECT MAX(added_in_version) FROM catalog_templates)
    WHERE EXISTS (SELECT 1 FROM user_trades ut WHERE ut.user_id = u.id AND ut.trade = 'PAINTER');

    RAISE NOTICE 'V97 painter: % painters, +% positions, -% leftovers', v_painters, v_added, v_removed;
END $$;

DROP TABLE painter_v10_removed_baseline;
