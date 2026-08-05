-- The master's own catalog gets an ORDER he controls.
--
-- Until now the list was sorted alphabetically — category, then name — computed on every read, with
-- nothing stored. That is fine for a catalog nobody arranges and wrong for one he does: the order a
-- master wants is the order he works in («спочатку те, що я роблю щодня»), and no sort key derived
-- from the text can express it.
--
-- ============ WHY THE BACKFILL MATTERS MORE THAN THE COLUMN ====================
-- Adding the column with a plain DEFAULT 0 would make every position equal, and the list would come
-- back in whatever order Postgres felt like — 800 rows reshuffled on the master's next visit, with
-- no action of his to explain it. So the backfill writes the CURRENT alphabetical order into the new
-- column, per owner. The first render after this migration is byte-identical to the last one before
-- it; the order only ever changes when he drags something.
--
-- Ordering is per OWNER, not global: each master's catalog is his own list, and positions of two
-- masters never appear together.
--
-- NULLS LAST on category mirrors the Java comparator being replaced (CatalogService's
-- BY_CATEGORY_THEN_NAME), so «Без категорії» stays at the bottom where it already was.

ALTER TABLE catalog_items
    ADD COLUMN sort_order INT NOT NULL DEFAULT 0;

WITH ordered AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY owner_id
               ORDER BY category NULLS LAST, lower(name), id
           ) - 1 AS position
    FROM catalog_items
)
UPDATE catalog_items ci
SET sort_order = ordered.position
FROM ordered
WHERE ci.id = ordered.id;

-- The list endpoint reads a whole catalog in order on every open, and a master can hold 800+ rows.
CREATE INDEX IF NOT EXISTS idx_catalog_items_owner_sort
    ON catalog_items (owner_id, sort_order);
