-- Catalog categories: a free-text grouping the contractor controls
-- ("Електрика", "Плитка", ...). Nullable — an empty value means the
-- item lands in the "Без категорії" group. Not an enum: every master
-- groups their own way.

ALTER TABLE catalog_items  ADD COLUMN category VARCHAR(100);
ALTER TABLE estimate_items ADD COLUMN category VARCHAR(100);

-- Helps GET /api/catalog ordering/grouping by category for large libraries.
CREATE INDEX idx_catalog_items_owner_category ON catalog_items (owner_id, category);
