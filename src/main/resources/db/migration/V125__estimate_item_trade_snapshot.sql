-- =================================================================================================
-- V125 — an estimate line remembers WHICH TRADE its job belongs to (estimate-trade-tree iteration).
--
-- The catalog has always known each position's trade (V27, refined by V116/V118). The estimate line
-- has not, until now: a line snapshotted its name, unit, price and category from the catalog, but
-- not its trade — because there was no place on the estimate that needed it. The tree-view iteration
-- adds one: on an estimate spanning two or more trades, the category header gets a small badge
-- naming the trade («Гіпсокартон» / «Малярні роботи»), so the client reading the portal can see
-- «а, тут два різних види робіт», and the master can see the same on his board.
--
-- Nullable, on purpose:
--   · An ADDENDUM line (a receipt or off-estimate work billed on an act) is filed under a work
--     document, not under a trade, and stays NULL — the badge is hidden either way, and forcing a
--     value here would be a lie for one out of two rows on an act.
--   · A line the master typed himself and never linked to the catalog (import, manual entry) has
--     no source to snapshot from, and would be assigned OTHER in every backfill regardless — NULL
--     tells "we don't know" apart from "the master's catalog says OTHER", which matters for the
--     "≥2 trades → show the badge" rule (see below): NULL doesn't count as a distinct trade, so a
--     single-trade estimate with some unlabelled rows stays badge-free instead of accidentally
--     showing two "trades" (his real one + OTHER).
--
-- Snapshot rule (same as every other field on this row — name/unit/price/category, V119):
--   the client signed THIS wording, so re-classifying the catalog position later must never change
--   what a signed estimate says. Even when a master edits or deletes the catalog row, the line
--   keeps the trade it was added under.
-- =================================================================================================
ALTER TABLE estimate_items ADD COLUMN trade VARCHAR(50);

COMMENT ON COLUMN estimate_items.trade IS
    'Snapshot of the catalog position''s trade at the moment the line was added (V125). NULL when '
    'the line has no catalog source or is an ADDENDUM (off-estimate act work) — the read side hides '
    'the trade badge either way, and NULL is deliberately not the same as OTHER (see V125 header).';

-- Backfill for already-authored lines. We match a line back to a catalog row of the SAME MASTER on
-- exact (name, type, unit), lowercased+trimmed — the same key `ux_catalog_items_owner_name_type_unit`
-- enforces uniqueness on. Multiple hits are impossible under that key; a single hit's trade wins.
-- No fallback to OTHER: if we can't tell, the column stays NULL and the read-side rule treats it as
-- "no group".
--
-- Signed estimates are included on purpose — the master decided (2026-09-04) that even old
-- signed sheets should show the trade grouping when re-opened by the client. This never touches
-- `line_total`, name, unit or price, so no signed amount can drift by a hryvnia.
WITH match AS (
    SELECT ei.id AS item_id, ci.trade AS catalog_trade
    FROM estimate_items ei
    JOIN estimates    e ON e.id = ei.estimate_id
    JOIN projects     p ON p.id = e.project_id
    JOIN catalog_items ci
      ON ci.owner_id = p.owner_id
     AND ci.type     = ei.type
     AND ci.unit     = ei.unit
     AND lower(trim(ci.name)) = lower(trim(ei.name))
)
UPDATE estimate_items ei
SET trade = m.catalog_trade
FROM match m
WHERE ei.id = m.item_id;

-- Enum guard — cheap, catches a mis-typed literal on the write path before it reaches production.
-- Mirrors the same CHECK on `catalog_items.trade` (V30/V33/V54).
ALTER TABLE estimate_items
    ADD CONSTRAINT estimate_items_trade_check
        CHECK (trade IS NULL OR trade IN
            ('ELECTRICAL','PLUMBING','TILING','BUILDER','PAINTER','DRYWALL',
             'FLOORING','DEMOLITION','METAL','GENERAL','OTHER'));

-- Index — the read path counts DISTINCT trades per estimate to decide whether to show the badge.
-- Estimate ids are already indexed on this table; adding the trade to a covering index keeps that
-- count cheap without a second seq scan.
CREATE INDEX IF NOT EXISTS idx_estimate_items_estimate_trade
    ON estimate_items (estimate_id) INCLUDE (trade);
