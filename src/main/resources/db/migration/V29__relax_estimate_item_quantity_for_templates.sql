-- Applying an estimate template creates line items with an EMPTY quantity and a
-- price taken from the master's own catalog by name — which may itself be 0 (V27
-- allows 0-price catalog items) or absent (no match → 0). The original V7 CHECKs
-- (quantity > 0, unit_price > 0) rejected those, 500-ing "create from template"
-- (and, latently, adding a 0-price catalog item to an estimate). Relax both to
-- >= 0 — mirrors the catalog price-CHECK relaxation in V27. A normal estimate item
-- still gets real, positive values through the validated add/update form; only a
-- freshly-applied template starts at 0 until the master fills it in.
ALTER TABLE estimate_items DROP CONSTRAINT estimate_items_quantity_check;
ALTER TABLE estimate_items ADD  CONSTRAINT estimate_items_quantity_check CHECK (quantity >= 0);

ALTER TABLE estimate_items DROP CONSTRAINT estimate_items_unit_price_check;
ALTER TABLE estimate_items ADD  CONSTRAINT estimate_items_unit_price_check CHECK (unit_price >= 0);
