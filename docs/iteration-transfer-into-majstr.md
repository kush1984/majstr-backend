# «Перенести в Majstr» — and PDF in both imports

- **Status:** ✅ Backend + PWA. `./gradlew build` and the PWA gate green before push.
- **Migration:** V201 (`projects.transferred_at`).
- **PWA:** 1.77.0 — `TransferPage` (`/transfer`), PDF in the estimate and price imports, three doors.
- **Goal (webinar, 2026-11-11):** «чи можна імпортнути свої позиції чи шаблон з PDF?» — masters did
  not know how to move their existing work in. Keep the imports where they are, teach both PDF, and
  add one place that takes a pile of files and sorts it out.

## 1. One reader for every document (`DocumentReader`)

- The ONE place that knows what we accept: a photo; a **PDF** — its text layer when it has one
  (what estimate software exports: cheaper, numbers read off text), the document block when it is
  a scan (< 200 characters of text); a spreadsheet or CSV as a text grid. A PDF is capped at **20
  pages** (`error.import.too-many-document-pages`); anything else is `error.import.unsupported`.
- The AI layer already spoke PDF (project import); `EstimateExtractor.extract(content)` now takes
  whatever the reader built. The old per-type helpers moved out of `EstimateImportService`.

## 2. PDF in the two existing imports

- **Estimate import** (`/api/estimates/import/parse`): PDF accepted through the reader.
- **Price import** (`/api/catalog/import/parse`): a PDF or a photo goes to `PriceListReader` — the
  model reads it into the SAME `CatalogImportParseResponse` (a three-column grid name · price · unit
  as written, mapping 0/1/2), so the review screen is untouched. Excel/CSV/paste keep the
  deterministic parser and stay free of AI.
- Both AI reads sit behind `Feature.ESTIMATE_IMPORT`, **opened to FREE for now** (owner's decision,
  2026-11-12) — `PlanConfig` + `TEMP_FREE_GETS_ESTIMATE_IMPORT` in the PWA, flipped together. The
  FREE «Надішліть — перенесемо за вас» button returns when the flag goes back.

## 3. «Перенести в Majstr» (`TransferService`, `/api/transfer`)

- **`/parse`** reads ONE file (the PWA loops, with progress — one bad file costs only itself):
  `EstimateExtractor.extractDocument` asks the estimate prompt plus `documentKind`
  (`ESTIMATE` | `PRICE_LIST`), `objectName`, `address`, `clientName`, `title`. A price list's
  quantities are zeroed and it carries no deposit.
- **Trades — «куди розпихати»** (`TradeFiler`): each position is matched against the master's OWN
  catalog first (a trade other than OTHER), then OUR library (`catalog_templates`, trade + category),
  by type, with `CatalogMatcher` — the same exact-then-Dice match with its tie refusal dictation uses.
  No match = no trade (→ «Інше»); nothing is guessed.
- **`/commit`**, one transaction, in this order: (1) the catalog — price lists, plus each estimate's
  lines unless the master unticked it — via `CatalogImportService.commitFiled`, each position under
  its own trade and category (an existing name is updated or skipped by the batch policy); (2) the
  master's profile gains every trade a position landed in; (3) each estimate onto an object — an
  existing one, or a NEW one per distinct name in the batch (two files naming the same object land
  together), its client found by name or created (name only, V198), a detected deposit recorded
  as «Завдаток». Estimates arrive as DRAFT; nothing pretends to a signature.
- **Outside the FREE cap** (owner's decision): `ProjectService.createTransferred` reserves no slot
  and stamps `transferred_at`; `MAX_TRANSFERRED_OBJECTS` = 50 per account bounds the door
  (`error.transfer.too-many-objects`, 400 `TRANSFER_INVALID`). The per-object estimate cap still
  applies through `createFromImport`.

## 4. PWA

- `TransferPage`: pick files (PDF, Excel, CSV, photos; up to 20) or photograph one → «Читаємо файл
  N з M…» → one card per file: Кошторис / Прайс / Пропустити; for an estimate the object (new, named
  off the paper, else its address, else the file name — editable; or an existing object), the
  estimate name, «Додати позиції в каталог»; the trade chips and the positions on demand; a line
  with no unit is shown and left behind. «Оновити ціни в каталозі» is the batch policy.
- Doors: a dashboard card while the master has fewer than three real objects («Пізніше» remembered
  on the device), a profile row, and the catalog (empty state + beside «Імпортувати прайс»).
- The commit goes through `sendOnce` (idempotent replay) with the LLM timeout.

## Not changed / confirmed

- Template import: none — an imported estimate can already be saved as a template.
- The plain price import still takes ONE trade per batch.

## Gotchas

- `CatalogService.normalizeCategory` became public (the importer package needs it).
- The request contract gained `TransferCommitRequest` and its three nested records; the PWA maps
  them in `contract.test.ts`'s `BACKEND_NAME`.
