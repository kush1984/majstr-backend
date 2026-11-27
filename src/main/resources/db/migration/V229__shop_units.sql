-- V229 — the shop's own counts: мішок, пачка/упаковка, відро, каністра, рулон, лист.
-- «Три мішки клею» from the bot was filed as «3 шт» because a bag had no unit (owner's video test, 2026-11-27).
-- Labels are short like «шт» («3 міш.»), so nothing declines. Old migrations are immutable, so every live unit
-- CHECK is dropped and recreated with the extended set — the V126 pattern. Purely additive.
-- `measurement_item` keeps its own, smaller set: nothing is measured in bags.

ALTER TABLE catalog_items           DROP CONSTRAINT catalog_items_unit_check;
ALTER TABLE catalog_items           ADD  CONSTRAINT catalog_items_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR', 'LITRE', 'BAG', 'PACK', 'BUCKET', 'CANISTER', 'ROLL', 'SHEET'));

ALTER TABLE estimate_items          DROP CONSTRAINT estimate_items_unit_check;
ALTER TABLE estimate_items          ADD  CONSTRAINT estimate_items_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR', 'LITRE', 'BAG', 'PACK', 'BUCKET', 'CANISTER', 'ROLL', 'SHEET'));

ALTER TABLE catalog_templates       DROP CONSTRAINT catalog_templates_unit_check;
ALTER TABLE catalog_templates       ADD  CONSTRAINT catalog_templates_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR', 'LITRE', 'BAG', 'PACK', 'BUCKET', 'CANISTER', 'ROLL', 'SHEET'));

ALTER TABLE estimate_template_items DROP CONSTRAINT estimate_template_items_unit_check;
ALTER TABLE estimate_template_items ADD  CONSTRAINT estimate_template_items_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR', 'LITRE', 'BAG', 'PACK', 'BUCKET', 'CANISTER', 'ROLL', 'SHEET'));

ALTER TABLE material                DROP CONSTRAINT material_unit_check;
ALTER TABLE material                ADD  CONSTRAINT material_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR', 'LITRE', 'BAG', 'PACK', 'BUCKET', 'CANISTER', 'ROLL', 'SHEET'));

ALTER TABLE material_norm           DROP CONSTRAINT material_norm_unit_check;
ALTER TABLE material_norm           ADD  CONSTRAINT material_norm_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR', 'LITRE', 'BAG', 'PACK', 'BUCKET', 'CANISTER', 'ROLL', 'SHEET'));

ALTER TABLE shopping_list_item      DROP CONSTRAINT shopping_list_item_unit_check;
ALTER TABLE shopping_list_item      ADD  CONSTRAINT shopping_list_item_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR', 'LITRE', 'BAG', 'PACK', 'BUCKET', 'CANISTER', 'ROLL', 'SHEET'));

ALTER TABLE material                DROP CONSTRAINT material_package_unit_check;
ALTER TABLE material                ADD  CONSTRAINT material_package_unit_check
    CHECK (package_unit IS NULL OR package_unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR', 'LITRE', 'BAG', 'PACK', 'BUCKET', 'CANISTER', 'ROLL', 'SHEET'));
