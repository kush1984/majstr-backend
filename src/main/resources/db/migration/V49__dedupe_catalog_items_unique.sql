-- Make duplicate catalog items structurally impossible.
--
-- Root cause: three code paths inserted catalog items with THREE different dedup keys
-- (template copy: lower(name)|type|unit, no trim; import: trim.lower(name)|type, no unit;
-- manual create: no dedup at all). An item created by one path could be invisible to
-- another path's dedup, so a master's catalog accumulated exact duplicates (same name,
-- type, unit) — e.g. "Монтаж будівельного риштування" twice.
--
-- Fix = one canonical identity per owner: (owner_id, lower(trim(name)), type, unit).
-- 1) Collapse existing duplicates, keeping the "best" row (highest price — the real one
--    over a 0/placeholder — then the oldest id as a stable tiebreak). Items differing in
--    unit (e.g. щебінь M3 vs T) or type are NOT duplicates and are kept.
-- 2) A UNIQUE expression index enforces the identity so no future path can duplicate.
-- catalog_items has no inbound FKs (estimate items are value copies), so deletes here
-- never cascade into estimates.

DELETE FROM catalog_items ci
USING (
    SELECT id,
           row_number() OVER (
               PARTITION BY owner_id, lower(trim(name)), type, unit
               ORDER BY default_price DESC, id ASC
           ) AS rn
    FROM catalog_items
) d
WHERE ci.id = d.id
  AND d.rn > 1;

CREATE UNIQUE INDEX ux_catalog_items_owner_name_type_unit
    ON catalog_items (owner_id, lower(trim(name)), type, unit);
