# Import a master's price list into the catalog (Excel/CSV/paste)

- **Status:** 🔨 Backend code complete; `./gradlew build` pending at the gate (adds the
  Apache POI dependency — first build will resolve it). **No migration.** PWA verified:
  **tsc + vitest(71) + vite build green.**
- **Migration:** none — import creates ordinary owner `CatalogItem`s (confirmed in recon;
  no "imported" provenance flag added, to keep scope tight).
- **Goal (user prompt, 2026-07-06):** kill the biggest onboarding barrier — "it's all in
  my Excel, moving it is a chore." Upload .xlsx/.csv **or** paste rows → the system
  recognizes columns → the master reviews & fixes → "Import N positions" → the catalog is
  filled with their own positions at their own prices. Deterministic parsing only, **no AI**
  (photo/handwriting via vision-LLM is a later step onto the *same* review screen — logged
  in open-questions). Nothing is written silently: the review screen always precedes commit.

## Backend (deterministic parser + commit)

- **`CatalogImportParser`** (`service/importer/`): reads .xlsx/.xls (POI), .csv (auto
  delimiter `, ; \t`, UTF-8 → windows-1251 fallback), or pasted tab-separated text into a
  raw grid, then:
  - **column heuristics** — score each column: name = longest text / least numeric, price =
    numeric (`parsePrice` strips `грн/₴/spaces`, handles `1 200,50` and `1,200.50`), unit =
    matches the synonym dictionary. Returns `guessedMapping` for client remap.
  - **row hygiene** — skips empty, totals (`разом/всього/итого/сума/total`), header words,
    pure-number ordinals, and section rows (name but no price *and* no unit).
  - **`UnitNormalizer`** — synonym dict → `Unit` (`кв.м/м²→M2`, `м.п./пог.м→LINEAR_METER`,
    `шт→PIECE`, …; unrecognized → null + a "unit" issue). Linear-metre wins over plain `м`.
  - **type hint** — material name markers → MATERIAL (a suggestion the master flips), else WORK.
  - A key correctness guard: `parsePrice` rejects any value with letters, so a NAME like
    "Позиція 1" is never mistaken for a price during column detection.
- **`CatalogImportService.commit`** — one transaction, owner-scoped. Dedup by
  `(owner, lower(trim(name)), type)` computed **in Java** over the owner's existing items
  (no `lower()` SQL — avoids the `lower(bytea)` class of bug). A match is price-updated or
  skipped per policy; new positions are created with the chosen trade (null → OTHER).
  Returns `{created, updated, skipped}`. Cap **500** rows/commit.
- **Endpoints** (`CatalogImportController`, `/api/catalog/import`): `POST /parse` (two
  content-types — `multipart/form-data` file and `text/plain` paste) and `POST /commit`.
  The file is parsed in memory and **never stored**.
- **Limits/safety:** file ≤ **2 MB** (existing `spring.servlet.multipart` cap, shared with
  logo — no config change; `MaxUploadSizeExceededException` already maps to 413). Row cap
  and unreadable/empty errors → 400 via `CatalogImportException` + bundle keys
  (`error.import.*`, uk + en). POI zip-bomb: default POI inflate-ratio limits + the 2 MB cap.

## PWA (3-step wizard, mobile-first)

- Entry: **"Імпортувати прайс"** on the Catalog empty state and the actions row →
  `/catalog/import` (full-screen wizard, own back button).
- **Step 1 — source:** upload .xlsx/.csv, or paste tab-separated rows into a textarea (same
  parse endpoint, `text/plain`). Short "which columns" explainer.
- **Step 2 — review** (the heart): editable cards — name / unit (select) / price / type
  toggle; rows with issues (missing unit/price) highlighted; a row can be dropped;
  **column remap** selectors re-derive rows locally (client mirror `importParse.ts`, no
  re-upload); batch **trade** (only shown for 2+ trades; single trade auto, null → OTHER);
  "skipped N service rows" shown; batch **dedup** choice (update price / skip).
- **Step 3 — commit:** "Import N positions" → summary toast (created/updated/skipped) →
  back to the catalog (imported items visible immediately). Commit is blocked while any
  included row is missing a unit or a valid price.

## Not changed / confirmed

- Catalog templates / default-library versioning untouched — import makes **ordinary
  owner positions**. Owner-scoped throughout. FREE limits are on objects/estimates, not
  catalog items — import doesn't touch them. Upload limits unchanged (shared 2 MB cap).

## Tests

- `UnitNormalizerTest` (synonyms, linear-vs-plain metre, unknown→null),
  `CatalogImportParserTest` (dirty xlsx built with POI: header/totals/empty skipped, units
  normalized, `1 200,50 грн`→1200.50; csv `;`; pasted tab text; windows-1251; >500 rows
  rejected; `parsePrice` formats), `CatalogImportServiceTest` (dedup update/skip/create,
  case+trim insensitive, null trade→OTHER). PWA: `importParse.test` (client mirror),
  `CatalogImportPage.test` (paste→review→commit; blocks on a missing-unit row).

## Verify (after backend build green)

1. Dirty .xlsx (2-row header, "Разом" at the bottom, `кв.м`/`пог.м`, `1 200,50 грн`) →
   recognized, service rows skipped.
2. Paste rows from Google Sheets → same review screen.
3. Review: fix a unit, drop a row, flip a type, pick a trade → import → all in the catalog.
4. Re-import the same file → dedup: "update prices" changes prices without dupes; "skip"
   leaves them.
5. CSV with `;` + windows-1251 reads; a 3 MB / 600-row file → friendly refusal.
6. Owner-scoped (never another master's catalog); mobile comfortable; Sentry clean.
