-- Duplicating an estimate with a markup — the бригадир's two-price workflow.
--
-- A foreman quotes the crew one price and the client another. Until now that meant two estimates
-- typed twice, and an object economy that had no way to tell which one was money he actually keeps:
-- counting both said he earned the crew's wages as well as his own margin.
--
-- The shape: the PARENT holds master prices (what he pays out), the DUPLICATE holds client prices,
-- and his earnings are the difference.
--
-- ============ WHY THE SOURCE PRICE LIVES ON THE LINE ===========================
-- The obvious design is to store one markup percent on the estimate and derive everything from it.
-- It breaks on every single thing that happens to a real estimate afterwards:
--   · the markup is applied to SELECTED lines only (works yes, materials usually not), so one
--     percent does not describe the sheet;
--   · the parent can be deleted — DRAFT/SENT delete freely — and the difference would go with it;
--   · the master edits a price in the duplicate, and the percent stops being true;
--   · he adds a line to the duplicate that the crew is not paid for at all.
-- A per-line source price survives all four, because the earning is always the same subtraction:
-- (current price − source price) × quantity. A line ADDED later has no source, and NULL there
-- correctly means "none of this is passed on to the crew" — the whole line is margin.
-- ==============================================================================

ALTER TABLE estimates
    -- ON DELETE SET NULL, not CASCADE: deleting the master-price estimate must never take the
    -- client's signed one with it. The duplicate keeps working — its per-line source prices are
    -- what the economy reads, and they are its own data.
    ADD COLUMN duplicated_from_id UUID REFERENCES estimates(id) ON DELETE SET NULL,
    -- Kept for the master to SEE what he applied («+15%»), never to recompute money from.
    ADD COLUMN markup_percent NUMERIC(5, 2);

COMMENT ON COLUMN estimates.markup_percent IS
    'The markup applied at duplication, for display only. Money is always derived from '
    'estimate_items.source_unit_price, which stays true after edits this number cannot describe.';

ALTER TABLE estimate_items
    ADD COLUMN source_unit_price NUMERIC(15, 2),
    -- WHICH line this was copied from, so deleting a position in the master-price estimate can take
    -- its twin out of the client one. Trimming a 167-position template happens in the parent, and
    -- leaving the copy behind would quietly re-introduce every line the master just removed.
    --
    -- ON DELETE SET NULL, deliberately NOT CASCADE: the copy may be SIGNED, and a signature
    -- certifies exact items and totals — a database-level cascade would delete lines out of a
    -- signed estimate with nothing able to stop it. The service does the deletion, checks the
    -- signature first, and this constraint only cleans up the pointer in the case it skipped.
    ADD COLUMN source_item_id UUID REFERENCES estimate_items(id) ON DELETE SET NULL;

COMMENT ON COLUMN estimate_items.source_unit_price IS
    'What this line cost in the estimate it was duplicated from. NULL on an ordinary estimate, and '
    'on a line added to a duplicate afterwards — there the whole line is the foreman''s margin.';

-- The economy sums per object over counted estimates; both new columns are read in that path.
CREATE INDEX idx_estimates_duplicated_from ON estimates(duplicated_from_id)
    WHERE duplicated_from_id IS NOT NULL;

-- Deleting N lines in a parent looks up their twins by this column; without the index that is a
-- scan of every line of every estimate, on the one action a master does in bulk.
CREATE INDEX idx_estimate_items_source_item ON estimate_items(source_item_id)
    WHERE source_item_id IS NOT NULL;
