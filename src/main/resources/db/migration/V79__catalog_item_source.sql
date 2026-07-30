-- Where a master's catalog row came from.
--
-- The admin "від майстрів" screen needs to tell "the master invented this" from "we gave them
-- this". Its first version inferred it — a position absent from catalog_templates was treated as
-- master-made — and that is wrong in exactly the case that matters: V70-V73 DELETED positions
-- from the defaults, so everything we ever shipped and later removed instantly looked
-- master-invented. The giveaway in production was a position credited to 64 masters, which is
-- not a thing anyone independently invents; it is the signature of a seeding batch.
--
-- Provenance cannot be derived after the fact. It has to be recorded when the row is written,
-- and there are exactly three places that write one: the library copy (registration seeding and
-- "Додати нові позиції"), the master's own form, and the price-list import.

ALTER TABLE catalog_items ADD COLUMN source VARCHAR(20);

-- Backfill for rows that predate the column. Provenance is gone, so this is a heuristic, and
-- the heuristic is the batch: a library copy is written with saveAll — hundreds of rows for one
-- owner in a single transaction, landing within the same second. A hand-typed position is a
-- lone row. Nobody types five positions in one second.
--
-- Known imprecision, stated rather than hidden: a SMALL "Додати нові позиції" batch (fewer than
-- five positions) is indistinguishable from manual entry and will be labelled MANUAL. That
-- misclassifies a handful of rows into the candidate list, where a human sees them — the
-- opposite error (hiding something a master really did invent) would be silent, so this is the
-- direction to be wrong in. It also decays: every row written from now on carries the real value.
WITH batches AS (
    SELECT owner_id,
           date_trunc('second', created_at) AS second,
           COUNT(*)                         AS rows_in_batch
    FROM catalog_items
    GROUP BY owner_id, date_trunc('second', created_at)
)
UPDATE catalog_items ci
SET source = CASE WHEN b.rows_in_batch >= 5 THEN 'LIBRARY' ELSE 'MANUAL' END
FROM batches b
WHERE ci.owner_id = b.owner_id
  AND date_trunc('second', ci.created_at) = b.second;

-- Anything the join missed (NULL created_at is impossible, but be explicit rather than lucky).
UPDATE catalog_items SET source = 'MANUAL' WHERE source IS NULL;

ALTER TABLE catalog_items ALTER COLUMN source SET NOT NULL;
ALTER TABLE catalog_items ADD CONSTRAINT catalog_items_source_check
    CHECK (source IN ('LIBRARY', 'MANUAL', 'IMPORT'));

-- The insight screens filter on it, and it is low-cardinality per owner.
CREATE INDEX idx_catalog_items_source ON catalog_items (source) WHERE source <> 'LIBRARY';

COMMENT ON COLUMN catalog_items.source IS
    'LIBRARY = copied from catalog_templates (seeding / add-new), MANUAL = the master typed it, IMPORT = price-list import.';
