# Catalog trade filter + multi-select batch add

- **Status:** ✅ Backend + PWA complete. PWA `tsc -b` clean; vitest + `vite build`
  green. Backend `./gradlew build` + drill pending at the gate.
- **Migration:** `V30__add_trade_to_catalog_items.sql` (nullable trade + CHECK + backfill).
- **Goal:** A master with 2+ trades (e.g. Builder + Tiling) saw one flat catalog
  (grouped only by category) with no trade filter anywhere, and added estimate
  items one at a time. Add (1) a trade filter on the catalog + the "from catalog"
  picker, and (2) multi-select batch add.

## Root cause / data

`CatalogItem` had only `category`, not `trade` — the trade was lost when a template
was copied into the personal catalog. So the filter is **backend + UI**, not a
front-only filter.

- **V30** adds an optional `catalog_items.trade` (CHECK mirrors the template trade
  set) and best-effort **backfills** existing items by category — only where a
  category resolves to exactly one trade in `catalog_templates`; ambiguous / manual
  categories stay NULL → "Інше" on the client. (Drill: PLUMBING 12, TILING 2 mapped;
  22 NULL on the V24 backup, which predates the by-trade templates.)
- **Stamped on every copy path** in one place: `CatalogTemplateService.missingItems`
  (register/seed, reset, add-from-template, add-new-from-template) → new items always
  carry the template's trade, independent of backfill quality.
- Manual create/update (`CatalogService`) takes an optional `trade`.
- `CatalogItemResponse` now returns `trade`; the client filters in memory (catalog
  is small — no separate endpoint). `estimate_items` is **not** touched (filter is
  on the selection surface only — small blast radius).

## Batch add

- `POST /api/estimates/{id}/items/batch` (`AddCatalogItemsBatchRequest{items:[{catalogItemId,
  quantity,sortOrder?}]}`) → `EstimateService.addItemsFromCatalogBatch` copies
  price/unit/type/category from each catalog item in **one transaction**, returns the
  updated estimate. Signed estimate → 409 ESTIMATE_SIGNED (same as single add). FREE
  limit is per-estimate, not per-item — unaffected.

## PWA

- `TradeFilterChips` (shared) — trade-level chips above the existing type row, shown
  **only for 2+ trades** (`useMe().trades`); "Інше" only when some item is untagged;
  default "Усі трейди". Used on **CatalogPage** and the **"from catalog" picker**.
- `AddItemSheet` catalog tab → **multi-select**: tap toggles a row, a sticky
  "Додати N позицій" fires one batch request (quantity 1, adjusted in the estimate).
  The **manual** tab keeps its type-ahead autocomplete for precise single adds — the
  two compose (browse = bulk, type = pointed), they don't overwrite each other.
- `CatalogItemForm` gains an optional trade `Select` (shown for 2+ trades), defaulting
  to the catalog's active trade filter on create.

## Tests

- Backend: `EstimateServiceTest` (batch copies each item in one saveAll; signed → 409);
  `CatalogTemplateServiceTest` (copy carries the template's trade); `CatalogServiceTest`
  call sites updated for the new request component.
- PWA: `AddItemSheet.test.tsx` (multi-select two rows → one batch call with both).

## Lands on prior uncommitted work

- Latest migration was **V29** (estimate_items relax) → this is **V30**.
- `CatalogItemResponse`/`Request` get `trade` additively; the single copy-path stamp
  is a one-liner in the method I already touched for templates. No conflict with V28/V29.

## Verify (after build green)

1. Master with 2 trades: trade chips appear on Catalog + the picker; pick a trade →
   only its items; "Усі" → all. 1-trade master sees no trade chips.
2. Backfill: existing items got a trade where the category was unambiguous, rest "Інше";
   new items from register/reset always have a trade.
3. Picker: trade chips + search + multi-select → "Додати N" → all in one go (one refresh).
4. Signed estimate: batch rejected (409); FREE estimate limit untouched.
5. Manual create with a trade → the item lands under that trade's filter.

## Multi-select upgrade (follow-up, user feedback)

The trade filter was single-select (`TradeFilter = Trade | 'ALL' | 'NULL'`). Per user
request it's now **multi-select**: `TradeFilterChips` takes a `Set<TradeKey>` (`TradeKey
= Trade | 'NULL'`), `tradeMatches(itemTrade, selected)` returns true for an empty set
(all) else set membership. Click a trade to add it; click again to remove; "Усі трейди"
clears; **selecting every available chip collapses back to "Усі трейди"** (nothing is
actually filtered). Applied to all three surfaces (CatalogPage, AddItemSheet picker,
TemplatesPage editor picker); CatalogPage prefills a new item's trade only when the
filter narrows to exactly one real trade. Unit test: `TradeFilterChips.test.tsx`
(add/keep/toggle-off/collapse/clear + `tradeMatches`).

**"Інше" is not a bug:** it appears only when `hasUntagged` — i.e. the master has ≥1
catalog item with `trade == null` (legacy items seeded before the trade column, or
manually-added items left untagged). New seeded catalogs are fully tagged (the copy
path stamps trade), so a fresh account won't show it. Tagging those items (the item
form's trade select) or the future bulk-assign removes the chip.

## V33 — one "Інше", not two (user feedback)

On prod the filter showed **two "Інше" chips**: `Trade.OTHER` (a real enum, label
"Інше", from the 2 default OTHER templates / masters with the OTHER trade) **and** the
`null`-untagged bucket (V30's best-effort backfill left ambiguous categories null), also
labeled "Інше". Same duplication in the edit form's dropdown (empty option = null + an
OTHER option). Two different things with one name.

**Fix — collapse "no trade" into the single OTHER catch-all; eliminate null:**
- **V33**: `UPDATE catalog_items SET trade='OTHER' WHERE trade IS NULL`, then
  `ALTER COLUMN trade SET DEFAULT 'OTHER'` + `SET NOT NULL` — the DB enforces no null
  can return. `CatalogItem.trade` is now `nullable=false` `@Builder.Default = OTHER`.
  Write paths coalesce null→OTHER (`CatalogService.create/update`,
  `CatalogTemplateService.missingItems`).
- **PWA**: `TradeKey = Trade` (dropped `'NULL'`); `tradeMatches` maps a null trade to
  OTHER; `TradeFilterChips` shows **one** "Інше" (OTHER) chip — when the master has the
  OTHER trade OR some item is OTHER (`hasOther`), deduped; hidden when <2 chips. The
  item form + `SaveToCatalogPrompt` drop the empty "Інше" option, always offer OTHER,
  and default an unspecified trade to OTHER. `catalogItemSchema.trade` is a plain enum
  (no `''`). Callers pass `hasOther` (`trade == null || trade === 'OTHER'`).
- Tests: `TradeFilterChips.test` rewritten (one "Інше", null→OTHER); the manual→catalog
  prompts now assert `trade: 'OTHER'`. PWA tsc + vitest (57) + build green; V33 drill green.

## Open question

`docs/open-questions.md` — bulk-assign trade to the "Інше" (OTHER) pile by master
feedback (categories the V30 backfill couldn't resolve, now OTHER instead of null).
