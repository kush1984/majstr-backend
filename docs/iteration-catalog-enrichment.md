# Catalog enrichment — fill empty prices + expand catalog + expand templates

- **Status:** ✅ Migration written + drill PASSED (prod backup → V31, all invariants
  hold). Backend `./gradlew build` pending at the gate (no DB in the test suite, so
  the **drill** is the real check for this data-only migration).
- **Migration:** `V31__enrich_catalog_and_templates.sql` (data only — no DDL).
- **Goal (user, 2026-06-28):** Close the 355-empty-price gap in the default catalog,
  broaden the default catalog, and expand the thin estimate templates ("дуже мало
  позицій в кожному шаблоні") + add new ones. Confirmed scope: **one commit**, new
  positions **broad (~60)**, templates **expand existing + add new**, price gaps
  **estimated by similar (master corrects)**.

## What V31 does (three data blocks)

1. **Price fills — 355.** Every default `catalog_templates` row that had
   `suggested_price = 0` is filled from a market-rate map. Source: ~250 services
   scraped from **rabotniki.ua**, fuzzy-matched (token Jaccard) to our position
   **within the same trade**; where no direct match, **estimated unit-aware** (best
   fuzzy in same trade+unit → median same trade+unit → cross-unit median → trade
   median). 264 of 355 are grounded in a real market price; the rest are
   order-of-magnitude estimates the master corrects. Emitted as ONE `UPDATE … FROM
   (VALUES …)`, **guarded on `suggested_price = 0`** so a real price is never
   overwritten.
2. **New positions — 62**, inserted at `added_in_version = 2`. Broad coverage across
   all 8 trades (decorative plasters, machine screed, parquet sanding, premium boiler
   room, smart-home/UPS, PVC-membrane & ventilated facade, balcony demo, …). Because
   `currentVersion = MAX(added_in_version)`, existing masters now see these under
   **"Add new from library"** with no code change; fresh masters get them seeded.
3. **Estimate templates — 88 → 102.** Every default bundle expanded to ~5 positions
   (was ~3.8) and ~14 new bundles added. Reseed = `DELETE … WHERE is_default = TRUE`
   then re-INSERT — template items carry names+units (not FKs), so this never touches
   a master's own templates. Each position's unit is resolved by name-match against
   the catalog (existing + the 62 new); the generator reports any unmatched name
   (**0 unmatched** in the final run, so every position price-substitutes at apply
   time).

## How it was generated (not committed — lives in C:\Work\prompts)

- `gen-v31-prices.cjs` → `v31-price-map.json` (the 355 fills + a quality report).
- `v31-new-positions.csv` (62 curated new positions, `trade,section,name,unit,price_hint`).
- 8 subagents (one per trade) expanded the templates from per-trade reference files
  (`gen-tref.cjs`), constrained to **existing catalog names only** → `v31-templates.md`.
- `gen-v31.cjs` assembles all three into the migration. **Trade-code gotcha:** the CSV
  and price map use the *agent* codes (TILER/PLUMBER/ELECTRICIAN); the DB uses the
  *enum* codes (TILING/PLUMBING/ELECTRICAL). The generator maps every trade through
  `dbTrade()` before emitting SQL — without it the price UPDATE would silently never
  match those three trades.

## "Стяжка будівельна" fix

The BUILDER "Стяжка будівельна" template referenced 3 screed positions that lived only
under FLOORING — a pure BUILDER master wouldn't have them seeded, so the template
wouldn't price-substitute. Screed is legitimate builder work, so V31 adds those 3
positions to BUILDER too (same FLOORING prices). Now the bundle is self-contained.

## Drill assertions added (scripts/migrate-drill.sh)

`max(added_in_version) = 2`, `new positions at version 2 = 62`, `unpriced default
templates = 0` (gap fully closed), `default estimate templates = 102`, and **no
default template ships with zero items**. Drill restores the 2026-06-12 prod backup
(at V24), applies V25→V31, all green. Final catalog: 673 templates, **all priced**.

## PWA follow-up (separate repo, separate commit)

The "Мої шаблони" page interaction was inverted per user feedback: a **row tap** now
opens a **read-only** composition view (own + defaults alike); the **pencil** opens the
**full editor** (rename + add/remove positions) for own templates. The editor's
"add position" gained a **"З каталогу" / "Вручну"** tab pair — the catalog tab reuses
`TradeFilterChips` + multi-select (positions already in the template are shown disabled,
so no accidental duplicates) and appends each pick as a template item (name+type+unit;
no batch endpoint for template items, so it appends sequentially). Tests:
`TemplatesPage.test.tsx` (pencil → editor, present-item disabled, fresh pick → addItem;
row tap → read-only, no add control). PWA `tsc -b` + vitest (39) + `vite build` green.

## Lands on prior uncommitted work

Latest migration was **V30** → this is **V31**. Data-only; no entity/DTO change, so no
record fan-out risk. Bundled into the single commit with the catalog-default tests,
estimate templates (V28/V29), template position-editing, PRO price 299, and the
trade-filter/batch (V30).

## Verify (after build green)

1. Fresh master: every default catalog position shows a non-zero suggested price.
2. Existing master: "Add new from library" offers the 62 new positions (and only those).
3. Templates picker: bundles are visibly richer (~5 positions); the ~14 new bundles appear.
4. Apply a template → positions drop in with empty quantities, prices substituted from
   the master's own catalog by name (no unmatched names → all priced).
