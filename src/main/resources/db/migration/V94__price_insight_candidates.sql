-- Community prices: a weekly-refreshed snapshot of price-drift / new-position candidates,
-- aggregated from masters' own ESTIMATE lines (the real source of truth for what things cost —
-- the catalog itself is edited far less often than an estimate is priced). Snapshot rather than
-- computed per-request, so the admin screen stays fast and stable between weekly refreshes — see
-- PriceInsightService.
CREATE TABLE price_insight_candidate (
    id                    UUID PRIMARY KEY,
    kind                  VARCHAR(20) NOT NULL,
    name_key              VARCHAR(512) NOT NULL,
    -- Readable form of what was found — the key is sorted words and unreadable (same reasoning
    -- as catalog_insight_dismissals.sample_name).
    sample_name           VARCHAR(255) NOT NULL,
    item_type             VARCHAR(20) NOT NULL,
    unit                  VARCHAR(20) NOT NULL,
    category              VARCHAR(100),
    -- Only set for PRICE_DRIFT — the default template this candidate proposes to update. Null
    -- for NEW_POSITION, which by definition has no existing template yet.
    catalog_template_id   UUID REFERENCES catalog_templates(id) ON DELETE CASCADE,
    current_default_price NUMERIC(15,2),
    proposed_price        NUMERIC(15,2) NOT NULL,
    -- The two-level median's whole point is "at least 3 masters agree" — a row that ever fails
    -- this was filtered out before it got here, so the invariant is worth stating at the DB too.
    masters_count         INT NOT NULL CHECK (masters_count >= 3),
    min_price             NUMERIC(15,2) NOT NULL,
    max_price             NUMERIC(15,2) NOT NULL,
    first_seen            TIMESTAMPTZ NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT price_insight_candidate_kind_check CHECK (kind IN ('PRICE_DRIFT', 'NEW_POSITION'))
);

-- One row per (kind, work) — a weekly refresh upserts rather than accumulating duplicates.
CREATE UNIQUE INDEX idx_price_insight_candidate_kind_key ON price_insight_candidate (kind, name_key);

-- PRICE_DRIFT joins the existing dismissal mechanism — dismissible the same way
-- NEW_POSITION/RENAMED_POSITION/UNUSED_DEFAULT already are (catalog_insight_dismissals, V78).
ALTER TABLE catalog_insight_dismissals DROP CONSTRAINT catalog_insight_dismissals_kind_check;
ALTER TABLE catalog_insight_dismissals ADD CONSTRAINT catalog_insight_dismissals_kind_check
    CHECK (kind IN ('NEW_POSITION', 'RENAMED_POSITION', 'UNUSED_DEFAULT', 'NEW_TEMPLATE', 'PRICE_DRIFT'));

-- catalog_update_notices becomes a real queue: one row per notice-worthy EVENT, not one slot per
-- master. Community prices can reprice several of one master's positions in a single week, and
-- each is its own notice — a single-slot table would silently drop all but the last one, and
-- would also collide with the rare migration-driven count notice sharing the same slot. `kind`
-- tells the two shapes apart explicitly rather than by which columns happen to be null.
ALTER TABLE catalog_update_notices DROP CONSTRAINT catalog_update_notices_user_uq;

ALTER TABLE catalog_update_notices ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'COUNT';
ALTER TABLE catalog_update_notices ALTER COLUMN kind DROP DEFAULT;
ALTER TABLE catalog_update_notices ADD CONSTRAINT catalog_update_notices_kind_check
    CHECK (kind IN ('COUNT', 'PRICE_DRIFT'));

ALTER TABLE catalog_update_notices ADD COLUMN position_name VARCHAR(255);
ALTER TABLE catalog_update_notices ADD COLUMN old_price NUMERIC(15,2);
ALTER TABLE catalog_update_notices ADD COLUMN new_price NUMERIC(15,2);

-- Each kind carries exactly its own fields — never a half-filled row of the other shape.
ALTER TABLE catalog_update_notices ADD CONSTRAINT catalog_update_notices_kind_shape_check
    CHECK (
        (kind = 'COUNT' AND position_name IS NULL AND old_price IS NULL AND new_price IS NULL)
        OR
        (kind = 'PRICE_DRIFT' AND position_name IS NOT NULL AND old_price IS NOT NULL
             AND new_price IS NOT NULL)
    );

-- The common read is "this master's undismissed notices" — a partial index over exactly that.
CREATE INDEX idx_catalog_update_notices_user_pending
    ON catalog_update_notices (user_id) WHERE dismissed_at IS NULL;

COMMENT ON TABLE catalog_update_notices IS
    'A queue: one row per notice-worthy event for a master (a migration rewriting their catalog, '
    'or one community-price position drift), each independently dismissible. No longer one slot '
    'per master (that was V83''s original, single-purpose shape) — see kind.';
