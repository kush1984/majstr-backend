-- =================================================================================================
-- V170 — two indexes on `catalog_items` that nothing reads, dropped.
--
-- `catalog_items` is the largest table by far (one copy of the library per master), and on prod it
-- had grown to ~4× its live size, mostly in its eight indexes: every catalog-wide migration that
-- renames, re-files or renumbers masters' rows (V118, V158-V169) writes a new entry into each index
-- the changed column belongs to, and vacuum only makes that space reusable, never smaller.
--
--   * idx_catalog_items_owner_id (V5) — every composite index on the table already LEADS with
--     owner_id (owner_category, owner_sort, ux_…_owner_name_type_unit), so each owner-scoped query
--     and the ON DELETE CASCADE from `users` are served without it;
--   * idx_catalog_items_type (V5) — no query filters on `type` without `owner_id`; the owner-scoped
--     ones (`findByOwnerIdAndTypeOrderByNameAsc`, `searchByOwner`) go through the owner indexes.
--
-- Dropping an index removes its file, so its bloat goes with it at once. The other indexes keep
-- theirs until a manual `REINDEX TABLE CONCURRENTLY catalog_items` (which cannot run inside the
-- transaction Flyway wraps a migration in). No data changes.
-- =================================================================================================

DROP INDEX IF EXISTS idx_catalog_items_owner_id;
DROP INDEX IF EXISTS idx_catalog_items_type;
