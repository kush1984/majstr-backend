-- =================================================================================================
-- V174 — the shopping list keeps the PACKAGE the calculator rounded to (calc-packages).
--
-- The result screen says «6 × мішок по 25 кг»; the same line on the list said only «150 кг», and the
-- list is what the master takes into the shop. The package is FROZEN on the row, exactly like its
-- name and unit (V126): the dictionary may change, the master's list must not change under him.
-- Only the SIZE and its NAME are stored — the number of packages is derived from the row's current
-- quantity on every read, because the master may have corrected 150 to 140.
--
-- Written by `ShoppingListService.applyCalculated` with the calculator's own rule
-- (`MaterialCalculatorService.packageSize`), so the drywall sheet takes the master's GKL_SHEET habit.
-- A hand-written row has none.
--
-- BACKFILL from the dictionary by `material_id`, bought and cleared rows included (it is a caption,
-- no quantity moves) — EXCEPT `GKL_SHEET`, whose package is the master's own sheet size and cannot
-- be told from here; the next recalculation fills it.
-- =================================================================================================

ALTER TABLE shopping_list_item
    ADD COLUMN package_size NUMERIC(15, 3),
    ADD COLUMN package_name VARCHAR(40);

ALTER TABLE shopping_list_item
    ADD CONSTRAINT shopping_list_item_package_pair
        CHECK ((package_size IS NULL) = (package_name IS NULL)),
    ADD CONSTRAINT shopping_list_item_package_size_positive
        CHECK (package_size IS NULL OR package_size > 0);

COMMENT ON COLUMN shopping_list_item.package_size IS
    'Frozen size of one package in the row''s unit (25 for «мішок по 25 кг»); NULL = not sold by the package.';
COMMENT ON COLUMN shopping_list_item.package_name IS
    'Frozen package name («мішок», «лист»); NULL exactly when package_size is NULL.';

UPDATE shopping_list_item i
SET package_size = m.package_size,
    package_name = m.package_name
FROM material m
WHERE m.id = i.material_id
  AND m.package_size IS NOT NULL
  AND m.package_size > 0
  AND m.package_name IS NOT NULL
  AND m.code IS DISTINCT FROM 'GKL_SHEET';
