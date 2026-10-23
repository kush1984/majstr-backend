# Packages on the shopping list, and the plaster and screed calculators

**Status:** stage 1 done 2026-10-23 (backend V174 + PWA 1.58.0); stages 2-3 next.
**Source:** `C:\Work\prompts\calc-packages-and-new-calculators-prompt.md`. **Migration:** V174.

## Stage 1 — the list keeps the package

The result screen said «6 × мішок по 25 кг»; the same line on the shopping list said «150 кг», and
the list is what goes into the shop.

- **Frozen on the row** (`shopping_list_item.package_size` / `package_name`, CHECK both-or-neither),
  like the name and unit: the dictionary may change, the master's list may not.
- **Written on the server only.** `MaterialCalculatorService.toShoppingList` resolves the package
  with the calculator's own `packageSize(material, sheetArea)`; the sheet takes the master's
  `GKL_SHEET` habit, which is OWNER-wide (`master_material_pref`), not per estimate, so nothing is
  taken from the client. Read only when a sheet is on the list.
- **`applyCalculated`** stamps a new row and refreshes the package on every rewrite — an `edited`
  row included: its quantity stays the master's, the package follows the recalculation. A
  hand-written row (`addManual`) has none.
- **The count is never stored** — derived from the row's CURRENT quantity on every read:
  `packageCount` in `src/lib/packages.ts` (`Math.ceil(q / size - 1e-9)`, now shared with the
  calculator screen) and `ShoppingListPdfService.packageCount` (`divide(size, 0, CEILING)`). A
  top-up row counts its difference («ще 1 × мішок по 25 кг»); the recalculation's offer carries its
  own count (`shopping.suggestionWithPackages`).
- **Backfill** from the dictionary by `material_id`, bought and cleared rows included (a caption),
  EXCEPT `GKL_SHEET` — the next recalculation fills it.
- **PDF:** a second line in the quantity cell; still no price.

### Confirmed, not changed

- `material.package_unit` equals `unit` wherever a package exists — no conversion is needed.
- Neither the list screen nor the PDF merges rows across estimates; the server merges only within
  one apply and only for one material, so two different package sizes can never be summed. Two
  estimates calculated before and after a sheet-habit change are two rows, each with its own size.
- The home card and the calculator screen are unchanged apart from the shared helper.
- `ShoppingListItemResponse.packageSize`/`packageName` are OPTIONAL in `api/types.ts`: a phone's
  offline cache predates them.

### Tests

`ShoppingListIntegrationTest` (freeze, manual = null, edited keeps the number and takes the new
package, the CHECK, the backfill re-run on real rows), `MaterialCalculatorServiceTest` (dictionary
package, the master's sheet), `ShoppingListPdfServiceTest` (caption, corrected quantity,
2,1 / 0,3 = 7); PWA `packages.test.ts`, `ShoppingListPage.test.tsx` (with/without, corrected,
top-up, bought, offer, old cache).
