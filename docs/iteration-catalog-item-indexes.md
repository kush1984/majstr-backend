# Catalog item indexes (V170)

**Status:** done 2026-10-14. **Migration:** V170.

## Why

On 2026-10-14 prod Postgres crashed during the V157-V169 deploy: its 500 MB Railway volume filled
up (`No space left on device` writing `pg_wal`). The volume was resized to 5 GB and the migrations
landed. The database was 157 MB, of which `catalog_items` alone was 137 MB for ~70 k rows — about
4× its live size, mostly index bloat: every catalog-wide migration that renames, re-files or
renumbers masters' rows writes new entries into every index on a changed column, and vacuum makes
that space reusable but never returns it.

## What changed

- Dropped `idx_catalog_items_owner_id` (every composite index already leads with `owner_id`, which
  also serves the `users` ON DELETE CASCADE) and `idx_catalog_items_type` (no query filters on
  `type` without `owner_id`). Eight indexes → six. No data change.
- Test: `CatalogItemIndexesIntegrationTest`.

## Manual follow-up (prod, once)

`REINDEX TABLE CONCURRENTLY catalog_items;` via psql (`railway connect Postgres`) — rebuilds the
remaining indexes without blocking masters; cannot run inside Flyway's transaction.

## For future catalog-wide migrations

Update only the rows whose value actually changes (`WHERE x IS DISTINCT FROM new_value`), never the
whole table — each needless row version lands in every index on the changed column.
