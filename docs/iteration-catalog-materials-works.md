# Catalog: default materials (V35) + extra works from Ліга Майстрів (V36)

- **Status:** ✅ Migrations written + drill PASSED (prod backup → V36, all invariants
  hold). Backend `./gradlew build` pending at the gate (data-only migrations — the
  **drill** is the real check, the Mockito suite has no DB).
- **Migrations:** `V35__add_default_materials.sql`, `V36__add_ligamaistriv_works.sql`
  (both data-only, no DDL).

## Why

Two gaps in the default catalog after V31:
1. It was **100% WORK** — a labour reference with **zero materials**, yet a real
   client estimate lists the materials too (cable, tile adhesive, pipe, profile…).
2. The user found another published price source — the **Ліга Майстрів** lists at
   `ligamaistriv.com.ua/price` — and asked to cherry-pick any works we were missing.

## V35 — default MATERIAL positions (295)

- 295 materials across 8 trades (ELECTRICAL 45, PAINTER 42, PLUMBING 42, TILING 42,
  BUILDER 40, FLOORING 35, DRYWALL 34, DEMOLITION 15), `type='MATERIAL'`,
  `added_in_version=3`. Existing masters see them under **"Add new from library"**
  (currentVersion = MAX(added_in_version) bumps 2 → 3); fresh registrations get them
  seeded. The catalog was 100% WORK before this.
- Prices are orientative market figures (Epicentr/Nova Liniya-grounded agent research,
  235/295 at H+M confidence; the rest are market estimates the master corrects — same
  posture as the V31 price fills).
- Generated from `materials-all.md` by `gen-v35.cjs` (in C:\Work\prompts, not committed):
  parse → dedup by `trade||name||unit` (keeps legit unit variants like пісок/щебінь per
  T and per M3) → validate trade/unit enums → emit one INSERT. **Materials are NOT added
  to the default estimate templates** — the user held that (templates stay 102, works).

## V36 — extra WORK positions from Ліга Майстрів (15)

- The published Ліга Майстрів price lists (8 trade Excel files; 2 downloadable directly,
  6 SharePoint-gated 403 → the user downloaded all and we parsed the `.xlsx` locally).
- **Gap analysis vs our 673 existing works:** our catalog already covered ~95% of that
  source at **finer** granularity (e.g. we carry cable runs, штроблення, plaster by many
  variants; they list one line each). So the honest yield was small: **15 genuine gaps**,
  `type='WORK'`, `added_in_version=4`:
  - ELECTRICAL — single/three-phase energy meter install, штроблення in газоблок.
  - BUILDER — monolithic slab / walls / columns (cast concrete; we only had foundation
    monolith), bored piles, ceramic-tile roof, valleys (єндови), chimney aprons.
  - PLUMBING — sewer-riser tee swap.
  - DRYWALL — glue-mount GK sheets, acoustic membrane, GK milling, reinforced-profile
    framing (Walraven/TECE).
- Range prices ("280-600", "від N") taken as the source figure / range midpoint. New rows
  slot into **existing categories** per trade (Щит, Монтажні, Покрівля, Каналізація,
  Стіни, Конструкції, Інше, Кабель, Фундамент) so they group naturally in the PWA filter.
- **Deliberately dropped as duplicates / non-fitting:** cable cross-section variants (we
  have a generic run), facade insulation by city (we have it as a complex), trash-removal
  by truck "ходка" (unit not in our set; logistics, not construction), roofing/facade
  files' bulk (our BUILDER already carries роof + facade), and ~90% of the tiling/
  plumbing/painter lists.

## Drill assertions added (scripts/migrate-drill.sh)

`max(added_in_version) after V36 = 4`; `MATERIAL positions (V35) = 295`; `positions at
version 3 (V35) = 295`; `new WORK positions at version 4 (V36) = 15`; `non-WORK rows at
version 4 = 0`. Drill restores the 2026-06-12 prod backup and applies V25→V36 — all
green. Final catalog: **983 templates**, all priced (688 works + 295 materials).

## No entity / DTO change

Both migrations are pure data. No record/constructor signature changes → no fan-out risk.
`added_in_version` is an existing column; `type` already allows WORK|MATERIAL. Bundled with
V34 (upgrade intent) into the pending backend build/commit.

## Verify (after build green)

1. Existing master: "Add new from library" now offers the 295 materials + 15 new works.
2. Fresh master: catalog contains materials (was works-only); every position priced.
3. Catalog filter groups the new works under their trade's existing categories.
