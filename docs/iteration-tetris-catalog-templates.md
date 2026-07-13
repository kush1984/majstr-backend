# Iteration: import a master's tetris price lists → default catalog + estimate templates (V50)

A master shared two professional price-list PDFs (tetris: «Будівельні роботи» +
«Внутрішні роботи»). Each ALL-CAPS section (ЗЕМЛЯНІ РОБОТИ, ФУНДАМЕНТ, …) becomes a
**default estimate template** (bundle); the positions under it become the template's
items and — where new — default catalog positions.

- **Status:** 🔨 migration `V50` generated + validated on the live dev DB (applies clean,
  0 unresolvable template items). Backend build is unaffected (pure SQL data, no Java);
  Flyway applies it on app start. Prod backup-drill recommended as usual.
- **Migration:** `V50__tetris_catalog_and_templates.sql` (data-only, no DDL).

## Extraction

- The PDFs' text layer has **no ToUnicode** for the Cyrillic font — `pdftotext -layout`
  drops the names, plain UTF-8 splits columns. So extraction was **vision** (each page
  rendered to JPEG via `pdfjs-dist` + `@napi-rs/canvas`, read with Claude vision). The
  offline artifacts (`source.json`, `source-vnu.json`, `gen.cjs`) live in the session
  scratchpad, not the repo — same pattern as the `gen-v*.cjs` generators.
- **23 sections → 23 templates, 561 positions** (all `WORK` — the price lists have no
  materials). Units normalized to the enum (точка→POINT, комплекс/контур→SET,
  стик/врізка/стояк/різець→PIECE, пог.м→LINEAR_METER, м²/м³→M2/M3, %→PERCENT).
- **Excluded:** lump-sum "комплекс на весь об'єкт" sections (НАВІС легкий/середній/макс,
  БУДІВНИЦТВО ГАРАЖА, ХОЗ БЛОК) — they're whole-object offers, not position lists.
- **САНТЕХНІКА kept as one section** (not split into Опалення/Водопостачання/Каналізація).
  A few positions repeat across its sub-headers at different prices — disambiguated with a
  suffix («Штроблення в бетоні (каналізація)», «Заробка штроб (сантехніка)») so one
  template/catalog can hold both.

## Dedup (the key finding)

The existing default catalog (V27) was seeded from the **same tetris source**, but with
**punctuation stripped** from names («Кладка перегородки з цегли до 50м2» vs the PDF's
«… (до 50м2)»). A punctuation-insensitive (fuzzy) comparison showed:

- **355 of 561 already exist** in the catalog (245 exact + 110 punctuation variants).
- **201 are genuinely new.**

Adding all 311 exact-new would have created 110 near-duplicate pairs — avoided.

## Price rule (agreed with the user)

- Everything from tetris → templates (all 23, all 561 items).
- An existing catalog position with a **non-zero** price keeps it (data-sacred; pulled
  into estimates at apply-time). Tetris price ignored for it.
- A **new** position, or an existing one priced **0**, takes the **tetris price**.
  (In practice 0 zero-price fills were needed — V31 had already priced all matches.)
- Default templates carry **no price** — resolved from each master's own catalog by name.

## What V50 does

1. **INSERT 201 new** `catalog_templates` (tetris name + price + section trade,
   `category` = section name, `added_in_version = 5` → existing masters pull them via
   "Додати з довідника"). Deduped internally + against the catalog.
2. **23 `estimate_templates`** (`is_default`, trade per section) + **561 items**. Item
   names are **canonical**: the existing catalog name where a position already exists
   (so the price resolves), else the new tetris name. Verified: **0 template items are
   unresolvable** against `catalog_templates`.
3. No zero-price UPDATEs were needed (block emitted only if any exist).

## Trades (per section — best-guess, master to confirm/adjust)

BUILDER: ЗЕМЛЯНІ, ФУНДАМЕНТ, МОНТАЖНІ, КЛАДОЧНІ, ПОКРІВЕЛЬНІ, БЛАГОУСТРІЙ, ПАРКАН, СХОДИ,
ЗВАРЮВАЛЬНІ, КЛАДКА, ГІДРОІЗОЛЯЦІЯ · PAINTER: ФАСАДНІ, ШТУКАТУРКА, ДЕКОР. ШТУКАТУРКА,
ВНУТРІШНЄ ОЗДОБЛЕННЯ · DRYWALL: ГІПСОКАРТОН, ЗВУКОІЗОЛЯЦІЯ · TILING: ПЛИТОЧНІ ·
FLOORING: СТЯЖКА, ПІДЛОГА, ТЕРАСИ · ELECTRICAL: ЕЛЕКТРИКА · PLUMBING: САНТЕХНІКА.
Trades are a filter tag only; each section stays a **separate** template. Adjustable later
via the admin catalog/template editor.

## Validation

- V50 dry-run in a transaction on the live dev DB (at V49): catalog 982→1183 (+201),
  default templates 102→125 (+23), template items 536→1097 (+561). 0 unresolvable. Rolled back.
- No DDL, no unique-constraint risk on `catalog_templates` (it has none); internal +
  fuzzy dedup guarantees no new duplicates.

## Gotchas

- Estimate-template price resolution is **exact name match** against the master's catalog,
  so template item names must equal catalog names → the canonical-name step is essential.
- Template items for the 355 pre-existing positions show the catalog's punctuation-stripped
  names (consistent with the current catalog); the 201 new ones use the nicer tetris names.
- `added_in_version = 5` (max was 4) so the 201 new positions reach existing masters through
  the "Add new from library" flow.
