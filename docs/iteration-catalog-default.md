# Default catalog by trade + catalog versioning ("Add new from library")

- **Status:** ✅ Backend + PWA complete. Backend `./gradlew build` pending a local
  run (HARD GATE). PWA `tsc -b` clean, vitest 31 green, `vite build` green.
  **V27 migration drill NOT yet run** (Docker Desktop was down) — run it before push.
- **Migration:** `V27__default_catalog_by_trade_and_versioning.sql` (611 rows + DDL).
- **Goal:** Two related things — (1) replace the thin starter templates with a real
  industry default catalog (611 items) grouped by trade; (2) a versioned mechanism
  so masters can pull **newer** defaults later without re-adding what they
  deleted/renamed or losing their own prices.

## 1. Default catalog import (611 items)

- Source: `majstr-default-catalog.csv` (`trade,section,name,unit,price_hint,note`),
  generated to SQL by `C:\Work\prompts\gen-catalog-migration.cjs` (handles quoting +
  apostrophe escaping; validates every trade/unit against the enums).
- All items are **WORK** (the CSV is a labour reference, no material column).
  `section` → `category`. Trades after mapping: BUILDER 155, PLUMBING 104,
  ELECTRICAL 102, PAINTER 86, TILING 65, FLOORING 37, DEMOLITION 36, DRYWALL 26.
- **New trades:** PAINTER, DRYWALL, FLOORING, DEMOLITION (added to `Trade` enum +
  the `user_trades` / `catalog_templates` CHECK constraints).
- **New units:** POINT, PERCENT (M3/T already existed from the builder iteration).
  Added to `Unit` enum + all four unit CHECKs (`catalog_items`, `estimate_items`,
  `catalog_templates`) + `UnitLabel` (which had also been **missing** M3/T —
  fixed in the same pass so `UnitLabelTest.everyUnitHasAnExplicitLabel` is green).
- **Prices:** the CSV had a market `price_hint` for 256 of 611 rows; those are
  imported as `suggested_price`, the other 355 are **0** (the master fills them
  in). To allow 0 the price CHECK is relaxed `> 0` → `>= 0` on **both**
  `catalog_templates` and `catalog_items` (a master can now save an item with an
  empty/0 price too).
- The migration **`DELETE`s** the old templates and re-inserts the 611 — templates
  are global read-only and `CatalogItem` copies *values*, not FKs, so reseeding is
  safe (V15 precedent). A master's existing catalog is untouched.
- **The starter set is per-trade:** `reset-from-template` / `add-from-template`
  still give a master only THEIR trades' items (unchanged `copyMissing` dedup by
  name+type+unit). Nothing here duplicates that mechanism.

## 2. Catalog versioning + "Add new from library"

Model (all in V27):
- Global current version = `MAX(catalog_templates.added_in_version)`. All current
  rows are **v1**.
- `catalog_templates.added_in_version INT NOT NULL DEFAULT 1` — the version a
  template first appeared in.
- `users.last_synced_catalog_version INT NOT NULL DEFAULT 0` — the version a master
  last pulled from. **Existing users stay at 0** (their catalog came from the OLD
  thin starter templates, so the new by-trade default set IS new to them) — the
  button offers them the full default catalog for their trades, de-duplicated
  against what they own. `register`/`reset` set it to the current version, so new
  users see nothing until a future v2+ ships.

Backend:
- `CatalogTemplateRepository.findByTradeInAndAddedInVersionGreaterThan(trades, since)`
  + `currentVersion()`.
- `CatalogTemplateService.addNewFromCatalog(user)` — copies templates newer than the
  user's synced version, for their trades, that they don't already have; advances
  `lastSyncedCatalogVersion` to current (even if 0 added). `countNewFromCatalog(user)`
  is the read-only preview (shares the extracted `missingItems` helper with
  `copyMissing`, so the count and the actual add can never diverge).
- Endpoints (both load the user via `findWithTradesById` → no lazy-init):
  - `GET /api/catalog/template-updates` → `TemplateUpdatesResponse{available}`.
  - `POST /api/catalog/add-new-from-template` → `CatalogResetResponse{itemsAdded}`.

PWA:
- "↻ Додати нові з довідника" button on a non-empty catalog (the empty state's
  "Стартовий набір" already does the full pull). Click → `template-updates`:
  - `available > 0` → confirm modal *"Знайдено N нових позицій… Ваші ціни та власні
    позиції не зміняться."* [Скасувати] [Додати] (brand amber button, **not** the
    destructive ConfirmDialog) → `add-new-from-template` → toast.
  - `available == 0` → "Усе актуально" info modal.
- `Trade`/`Unit` types, `TRADE_VALUES`, `TRADE_EMOJI`, `UNIT_OPTIONS`, both zod
  enums, and uk/en i18n extended for the 4 trades + POINT/PERCENT.

### What this deliberately does NOT do
- **No price updates to existing items.** v2+ can introduce items; it never
  rewrites a master's prices or renames their items. Updating *market prices* of
  already-owned items is a separate, deferred decision (see open-questions).
- Deleting/renaming a default item then "Add new" never re-adds it — the version
  cutoff guarantees it (only `addedInVersion > lastSynced` is offered).

## Tests

- Backend `CatalogTemplateServiceTest`: `addNewFromCatalog` pulls only newer-version
  templates, skips already-owned, advances sync (incl. when nothing new);
  `countNewFromCatalog` counts without writing and never advances sync.
- Backend `CatalogControllerTest`: `template-updates` returns `available` and uses
  `findWithTradesById`; `add-new-from-template` returns `itemsAdded`.
- `UnitLabelTest` (existing) now also covers M3/T/POINT/PERCENT via the every-unit
  assertion.
- PWA: `tsc -b` clean, vitest green (button/dialogs are wiring over tested hooks).

## Verify (after local build green + migration drill)

1. Fresh register in a painter trade → catalog seeded with PAINTER defaults,
   `last_synced_catalog_version` = current → "Додати нові" shows "Усе актуально".
2. Existing user (version 0) → "Додати нові з довідника" offers the new default
   catalog for their trades, de-duplicated against what they already own; accept →
   items appear with empty prices, their own items/prices untouched.
3. Adding a trade in the profile → "add starter set?" prompt now actually appears
   (post-save `me` refetch no longer wipes it) → accept → that trade's items added.
4. Item with empty price saves (CHECK relaxed).
5. PDF renders a POINT/PERCENT/M3 unit with its Ukrainian label.

## Migration drill (run before push — Docker was down this session)

```
restore daily_majstr-db-2026-06-12-193550.sql.gz (V24) → apply V25, V26, V27
→ assert: 611 templates, max(added_in_version)=1, all existing users last_synced=0,
  only allowed trades/units present, a 0-price catalog_item inserts OK.
```

## Follow-up fixes (post-review)

1. **Existing users were wrongly set to v1** — the migration's
   `UPDATE users SET last_synced_catalog_version = 1` meant a master with an
   existing catalog clicking "Add new from library" got "nothing new" (1 > 1).
   Removed the UPDATE; existing users stay at the column default **0**, so the
   button offers them the full v1 default set (deduped). New users still get
   synced to current on register/reset.
2. **"Add starter set?" prompt didn't appear when adding a trade in the profile**
   — `useUpdateProfile` primes + invalidates the `['me']` cache, so `me` changed
   right after save and `ProfileEditModal`'s seed effect re-fired, calling
   `setAddPrompt(null)` before the prompt rendered. Guarded the seed effect with a
   `seededRef` so it runs once per open, not on every `me` change.
