-- Bring the rebuilt tiling catalog (V82) to the masters who already registered.
--
-- V82 only changed reference data, which by itself reaches nobody: default templates are copied
-- BY VALUE at registration, so a master who signed up last month still holds the old 73
-- positions and would keep holding them forever. The product answer to that is the "Додати нові
-- позиції" button — but it is a button, and the whole reason this rebuild exists is that masters
-- were not finding things they never knew to look for. So the positions are pushed, not offered.
--
-- Scope: every user with TILING among their trades. Not every user in the database — a plumber
-- does not want 167 tiling positions, and "всі майстри" is read here as "every tiler, with none
-- left out", which is what the button was failing to achieve.
--
-- ============ WHAT IS NEVER TOUCHED ============================================
-- The removal half of this migration is the dangerous half, so it is fenced four ways:
--
--   1. estimate_items — never referenced. Snapshots with no FK; every estimate a master has
--      already written keeps every name, unit and price it was written with, whatever happens
--      to the catalog it was written from.
--   2. source <> 'LIBRARY' — a position the master typed in (MANUAL) or brought from a
--      price-list or receipt (IMPORT) is theirs. Only rows WE put there are candidates.
--   3. a price the master changed — compared against tiling_v9_baseline, the price we actually
--      shipped, captured in V82 before the old templates were deleted. If the number differs,
--      the master worked on that row and it stays, even though we no longer ship it.
--   4. estimate_templates owned by a master — not referenced at all. Their bundles keep naming
--      whatever they name; a bundle line resolves against their catalog at apply-time and an
--      unresolved one prices at 0 rather than failing, so nothing breaks.
--
-- And one more, implicit but worth stating: a position that is still shipped by ANY trade is
-- kept regardless. Some names («Винесення сміття», «Прибирання після робіт») live in several
-- trades, and a tiler who is also a builder must not lose their builder copy because the tiling
-- catalog stopped naming it.
-- ==============================================================================

CREATE TABLE catalog_update_notices (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    positions_added  INT         NOT NULL,
    positions_removed INT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    dismissed_at     TIMESTAMPTZ,
    CONSTRAINT catalog_update_notices_user_uq UNIQUE (user_id)
);
COMMENT ON TABLE catalog_update_notices IS
    'One pending "your catalog was updated" notice per master. Written by a catalog migration, '
    'read once by the app on entry, then dismissed. A master whose catalog we silently rewrite '
    'should be told what changed — otherwise their price list moved under them overnight.';

DO $$
DECLARE
    v_removed int;
    v_added   int;
    v_tilers  int;
BEGIN
    SELECT COUNT(*) INTO v_tilers FROM user_trades WHERE trade = 'TILING';

    -- ---- 1. our untouched leftovers go -------------------------------------------------------
    -- DISTINCT because the baseline is a snapshot of the OLD catalog, which is not guaranteed to
    -- be free of same-key duplicates (V70-V73 cleaned a lot of them, but this must not depend on
    -- that having been perfect). Without it a doubly-matched row would be counted twice and the
    -- master would be told we removed more than we did.
    CREATE TEMP TABLE _removed ON COMMIT DROP AS
    SELECT DISTINCT ci.id, ci.owner_id
    FROM catalog_items ci
    JOIN tiling_v9_baseline b
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

    -- ---- 2. the rebuilt catalog arrives ------------------------------------------------------
    -- Keyed exactly like ux_catalog_items_owner_name_type_unit, so an existing row — whatever its
    -- source or price — blocks the insert instead of colliding with it.
    CREATE TEMP TABLE _added ON COMMIT DROP AS
    SELECT gen_random_uuid() AS id, t.user_id AS owner_id, ct.name, ct.type, ct.unit,
           ct.suggested_price AS default_price, ct.category
    FROM (SELECT DISTINCT user_id FROM user_trades WHERE trade = 'TILING') t
    CROSS JOIN catalog_templates ct
    WHERE ct.trade = 'TILING'
      AND NOT EXISTS (
          SELECT 1 FROM catalog_items ci
          WHERE ci.owner_id = t.user_id
            AND lower(trim(ci.name)) = lower(trim(ct.name))
            AND ci.type = ct.type
            AND ci.unit = ct.unit);

    INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, category, trade, source)
    SELECT id, owner_id, name, type, unit, default_price, category, 'TILING', 'LIBRARY' FROM _added;
    GET DIAGNOSTICS v_added = ROW_COUNT;

    -- ---- 3. tell each master what happened to their list --------------------------------------
    INSERT INTO catalog_update_notices (user_id, positions_added, positions_removed)
    SELECT owner_id, COUNT(*) FILTER (WHERE src = 'add'), COUNT(*) FILTER (WHERE src = 'del')
    FROM (SELECT owner_id, 'add' AS src FROM _added
          UNION ALL
          SELECT owner_id, 'del' AS src FROM _removed) x
    GROUP BY owner_id;

    -- Nothing newer is left to pull, so the "N нових позицій" badge must not light up for work
    -- that has already been done for them.
    UPDATE users u
    SET last_synced_catalog_version = (SELECT MAX(added_in_version) FROM catalog_templates)
    WHERE EXISTS (SELECT 1 FROM user_trades ut WHERE ut.user_id = u.id AND ut.trade = 'TILING');

    RAISE NOTICE 'V83 tiling: % tilers, +% positions, -% leftovers', v_tilers, v_added, v_removed;
END $$;

DROP TABLE tiling_v9_baseline;
