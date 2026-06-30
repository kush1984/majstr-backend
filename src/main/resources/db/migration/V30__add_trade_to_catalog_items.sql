-- A master with several trades sees one flat catalog (grouped only by category)
-- with no way to filter by trade — because the trade was lost when a template was
-- copied into the personal catalog (CatalogItem kept category, not trade).
--
-- Add an OPTIONAL trade to catalog_items so the catalog + the "from catalog" picker
-- can filter by it. From here on every copy path stamps it (CatalogTemplateService),
-- and manual create may set it. This migration also best-effort backfills existing
-- items by their category — only where that category belongs to exactly ONE trade in
-- catalog_templates; shared / manual categories stay NULL ("Інше" on the client).

ALTER TABLE catalog_items ADD COLUMN trade VARCHAR(50);
ALTER TABLE catalog_items ADD CONSTRAINT catalog_items_trade_check
    CHECK (trade IS NULL OR trade IN
        ('ELECTRICAL', 'PLUMBING', 'TILING', 'BUILDER', 'PAINTER', 'DRYWALL', 'FLOORING', 'DEMOLITION', 'GENERAL', 'OTHER'));

-- Backfill: map trade from the default templates by category, only for categories
-- that resolve to a single trade (ambiguous ones are left NULL — safe, not wrong).
UPDATE catalog_items ci
SET trade = m.trade
FROM (
    SELECT category, MIN(trade) AS trade
    FROM catalog_templates
    GROUP BY category
    HAVING COUNT(DISTINCT trade) = 1
) m
WHERE ci.trade IS NULL AND ci.category = m.category;
