# Iteration: import an estimate from Excel / photo via Claude (LLM extraction)

A master uploads an Excel/CSV **or a photo** (of an Excel, a printout, or a
hand-written estimate); Claude extracts the line items; the master reviews and
confirms; we create a **ready estimate on an object** and upsert the positions
into the master's catalog. PRO-gated. The uploaded file is parsed then
discarded — never persisted.

- **Status:** 🔨 Backend code complete; `./gradlew build` pending at the gate.
- **App version:** PWA `0.5.4 → 0.6.0` (headline feature → minor bump).
- **Open-questions:** promotes "Import an ESTIMATE from a file" → IN_PROGRESS;
  adds the deferred "import-append into an open estimate" item; notes the
  vision-LLM reuse on the catalog price-list import item; ties to deposit/balance.
- **Migration:** none for the extraction path itself. (Estimate/EstimateItem/
  CatalogItem already exist; `Estimate.depositAmount` shipped in the prior step.)

## Decisions (locked)

- **Provider / model:** Anthropic `POST /v1/messages`, **`claude-opus-4-8`**,
  raw HTTP (Spring `RestClient` + Jackson 3) — matches the codebase's
  no-SDK precedent (`ResendEmailService`, web-push). Most accurate on
  table/number OCR; per-import cost ~$0.07–0.10, negligible for a PRO-gated,
  low-volume feature.
- **Structured output:** `output_config.format` with a `json_schema`
  (`additionalProperties:false` on every object; no numeric constraints — not
  supported). First `text` block is the JSON. No beta headers (structured
  outputs + vision are GA on Opus 4.8). `max_tokens` ~8000, **non-streaming**
  (extraction output is well under the 16K streaming threshold).
- **Two input branches, one service:**
  - **Excel/CSV** → POI (already on the classpath) → text grid → `text` block.
    Claude does NOT ingest `.xlsx` natively, so the POI step is required.
  - **Photo** (printed **and** hand-written) → base64 `image` block (vision).
    Same pipeline, same schema. Accuracy on hand-written digits is lower — the
    review screen is the guardrail; the prompt tells Claude not to invent
    unreadable numbers/positions.
- **No auto-commit:** the endpoint returns a **review payload** (editable item
  list + optional deposit). A second commit call creates the estimate + upserts
  the catalog. Catalog name-conflicts are resolved **on the review screen**
  (per-item, master decides) — not silently.
- **Plan gate:** a **new `Feature.ESTIMATE_IMPORT`, granted to PRO + TEAM**
  (deliberately NOT the TEAM-only `AI_ASSISTANT`, which stays reserved for the
  future "draft estimate from a description"). Gated via `FeatureGuard`.
- **Env-gated, but NOT a silent no-op:** blank `ANTHROPIC_API_KEY` (dev) →
  the endpoint returns a clean error (feature unavailable), because the import
  is **synchronous** and the master is waiting on the result — unlike the
  fire-and-forget email/push integrations that log-and-skip.
- **Privacy:** the uploaded file/image is held only for the duration of the
  request and never written to storage or the DB.

## PWA (shipped this iteration)

- **`EstimateImportPage`** (`/estimates/import?projectId=…`, full-screen wizard,
  registered before `/estimates/:id`): source step (file **or** photo — a camera-
  capture input for mobile) → `estimateImportApi.parseFile` → loading → review step
  (editable rows, unit `Select`, qty/price, type toggle, per-row remove + **до каталогу**
  checkbox, estimate name + завдаток, a global catalog-conflict rule) → `commit` →
  navigate to the editor. **PRO-gated client-side** (`me.plan !== 'FREE'` → upsell
  screen with `UpgradeBanner`, no uploader); backend enforces it too.
- **Two entry points** (both funnel to the wizard): the **`/new`** page gets a third
  "тип кошторису" tile **З файлу/фото** (creates the object, then hands off to the
  wizard); the project page's **"+ Новий"** modal gets a third **З файлу/фото** button
  → the wizard for that object.
- `api/estimateImport.ts`, `api/types.ts` (import DTOs), `config.ts` route +
  `importEstimate(id)` helper, i18n `estimateImport.*` + `templates.fromFile` (uk+en).
- **Simplification vs the plan:** catalog-conflict resolution is a **global** toggle
  (Пропустити / Оновити ціну) applied to the ticked rows, plus a per-row "до каталогу"
  checkbox — not per-item conflict UI (would need a client-side catalog lookup). The
  backend already accepts per-item `catalogPolicy`, so per-item granularity is a later
  refinement. Noted for the review-screen open item.
- **Verified green:** `tsc -b` clean, `vitest run` 83/83 (new `EstimateImportPage.test.tsx`
  — FREE upsell, parse→commit mapping, invalid-row block), `vite build` OK. App version 0.6.0.

## API contract (draft)

- `POST /api/estimates/import/parse` (auth, `ESTIMATE_IMPORT`) — multipart
  `file` (xlsx/csv/png/jpeg). Returns the review payload:
  `{ items: [{ name, unit, quantity, unitPrice, category?, type? }], depositAmount? }`.
  File type detected by content; Excel → POI branch, image → vision branch.
- `POST /api/estimates/import/commit` (auth, `ESTIMATE_IMPORT`) — takes the
  (master-edited) review payload + a target `projectId` (+ estimate name?) and
  per-item catalog decisions; creates the estimate on the object and upserts the
  catalog; returns the new estimate id.

(Exact request/response records finalized in code.)

## Work chunks

- [x] `Feature.ESTIMATE_IMPORT` + grant to PRO/TEAM in the plan matrix.
- [x] `AnthropicProperties` (`app.anthropic.*`: apiKey/model/maxTokens, env-only);
      registered in `MajstrApplication`; `application.yml` block added.
- [x] `ClaudeEstimateExtractor` — builds `content[]` (text grid | base64 image),
      POSTs to `/v1/messages` (Opus 4.8, `output_config.format` JSON schema, no beta),
      parses the first text block. All-required schema + sentinels (0/"") for
      unreadable, mapped to null/flag server-side.
- [x] Excel→text-grid reader (POI) + CSV decode + image passthrough + content-type
      detection (`EstimateImportService`).
- [x] `EstimateImportController` (`parse` multipart + `commit` JSON), DTOs
      (`EstimateImportParseResponse` / `…CommitRequest` / `…CommitResponse`), FeatureGuard gate.
- [x] `AiExtractionException` → 503 `AI_UNAVAILABLE`; bundle keys (uk+en);
      multipart limit raised 2→10 MB for phone photos.
- [x] Commit path: `EstimateService.createFromImport` (LimitService + ownership +
      churn counter + deposit) + catalog upsert via reused `CatalogImportService.commit`
      (per-item `toCatalog` + policy), one transaction.
- [x] Tests (Mockito, no live Anthropic): `ClaudeEstimateExtractorTest` (JSON→items,
      sentinels), `EstimateImportServiceTest` (routing/gate/issue-flags/commit
      orchestration), `EstimateServiceTest.createFromImport` (items+deposit+balance).
- [ ] **Build gate:** user runs `./gradlew build` locally → green → push. CLAUDE.md
      updated with the new subsystem note.

## Follow-up (live-test feedback, 2026-07-11)

- **New unit `KM` (км)** — for cable runs quoted per km. Enum + **`V45__add_km_unit.sql`**
  (drops/recreates the unit CHECK on `catalog_items`, `estimate_items`, `catalog_templates`,
  `estimate_template_items` — same V18/V26/V27 pattern) + `UnitLabel` (км) + `UnitNormalizer`
  (км/km) + PWA (`Unit` type, both zod enums, `UNIT_OPTIONS`, both import pages' `UNITS`,
  i18n `units.KM`/`unitOptions.KM`).
- **`м.кв.` → м²** — `UnitNormalizer` now maps `мкв` to `M2` (and `к-сть`/`к-ть`/`кількість`
  → PIECE); `normalizeToken` also strips hyphens (`к-сть` → `ксть`). Same synonyms mirrored in
  the PWA `importParse` guessUnit. "кількість" is treated as **an alias of `шт` (PIECE)** — no
  separate enum, since a count is pieces.
- **Allow 0 quantity / 0 price** — a master often knows the unit price but not yet the count
  (e.g. number of fixtures); the line total stays 0 until a quantity is set. The PWA review now
  requires only **name + unit** (`isBad`), so 0/empty qty & price commit fine. Backend already
  accepted 0 (`@DecimalMin("0.0")`). Review hints updated.
- Tests: `UnitNormalizerTest` (+мкв/км/к-сть), PWA `EstimateImportPage.test.tsx` (+commit-with-0,
  block-only-on-missing-unit). PWA re-verified green (tsc, 84/84, build). **Backend build gate
  still pending** (new migration + enum → `./gradlew build`).

## Not changed / confirmed

- No new migration; Estimate/EstimateItem/CatalogItem/`depositAmount` unchanged.
- `AI_ASSISTANT` stays OPEN and TEAM-only — this feature uses its own PRO gate.
- Signed-estimate immutability, money scale (BigDecimal, HALF_UP), owner
  isolation — all unchanged; the commit path goes through the existing
  estimate/catalog services so those invariants hold.

## Gotchas

- Jackson 3 (`tools.jackson.*`) for the ObjectMapper — not `com.fasterxml`.
- Structured-outputs schema: no `minimum`/`maximum`/`minLength`; keep it plain.
- POI dependency is `poi-ooxml` (already declared for `CatalogImportParser`).
- Numbers come back as JSON numbers → bind to `BigDecimal`, then apply MONEY_SCALE.
