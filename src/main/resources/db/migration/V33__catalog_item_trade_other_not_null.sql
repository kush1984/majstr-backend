-- One "Інше", not two. The catalog had two distinct "Інше": the OTHER trade enum
-- AND untagged (NULL) items — V30's best-effort backfill left ambiguous categories
-- NULL. Collapse "no trade" into the single OTHER catch-all so there is exactly one
-- "Інше" everywhere, and make the DB enforce it (no NULL can come back).
UPDATE catalog_items SET trade = 'OTHER' WHERE trade IS NULL;
ALTER TABLE catalog_items ALTER COLUMN trade SET DEFAULT 'OTHER';
ALTER TABLE catalog_items ALTER COLUMN trade SET NOT NULL;
-- The V30 CHECK (trade IS NULL OR trade IN (...)) still holds — trade is now never
-- NULL, and the allowed-values part is unchanged.
