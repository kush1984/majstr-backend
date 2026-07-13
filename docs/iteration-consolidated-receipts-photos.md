# Iteration: consolidated estimate + receipt-items (LLM) + object photos (private receipts / shared-with-client)

Three interrelated capabilities on the object screen, requested together by masters:

1. **Consolidated estimate («Зведений кошторис»)** — a button next to «Новий» on the
   object; a sheet lets the master tick which of the object's estimates to fold into
   one. All picked items are copied (works + materials) into a **new DRAFT estimate**
   that opens in the normal editor. No new entity, no migration — it's a normal
   `Estimate` created through the existing machinery (counts against the FREE
   3-per-object cap, like any estimate).

2. **Add items from a receipt photo (LLM)** — a fab-menu item on **every** estimate.
   Visible to all plans; **FREE tap → upgrade painted-door** (trigger `RECEIPT_IMPORT`),
   PRO/TEAM → camera/upload → Claude vision extracts store/terminal/hand-written receipt
   lines → review → **appended into the current estimate** (sums recompute). **Prices are
   NOT upserted into the catalog** (unlike the estimate-import feature). New
   `Feature.RECEIPT_IMPORT` (PRO+TEAM). File parsed then discarded.

3. **Object photos («Фото» tab)** — the long-standing placeholder tab becomes real,
   reviving the dead `Feature.PHOTO_REPORTS` enum. One table `project_photo`, two
   sources:
   - **RECEIPT** — after a receipt commit the master is asked «Зберегти фото чека?»;
     if yes the PWA re-uploads the same bytes as a **PRIVATE** photo linked to the
     estimate (`estimate_id` + `estimate_name_snapshot`). Financial → never shareable.
   - **MANUAL** — «+ Фото» on the tab; a progress photo, default **PRIVATE**, with a
     «Показати клієнту» toggle → **SHARED**.
   **Privacy is the point:** no photo is served from the public `/api/files/**`. Two new
   read paths — an **authenticated owner-only** stream, and a **portal-token-gated**
   stream that only serves `SHARED` photos of that token's object. This closes the
   open-question "Public file serving needs auth once non-public assets exist" for this
   asset class.

- **Status:** 🔨 backend code complete (build on the user, as always); **PWA done & green**
  (tsc / 91 tests / vite build).
- **App version:** PWA `0.7.0 → 0.8.0` (headline capability set → minor bump). ✅ bumped.
- **Migration:** `V47__project_photo.sql` (one new table). Consolidation and receipt-append
  add no schema; `Feature` values are code-only (no DB CHECK on features).

## Decisions (locked)

- **Consolidation = plain concat**, no dedup/merge of same-name lines (the master tidies
  quantities in the editor — same tool as always; safe: we never guess which price to keep
  when the same material cost differently across source estimates). `measurementRefs` are
  **not** carried over (a consolidated line is an independent snapshot); `quantityManual`
  defaults. Name defaults to «Зведений кошторис» when the master doesn't type one.
- **Receipt input is image-only** (photo / uploaded image). No Excel branch — receipts are
  photographed. Reuses `ClaudeEstimateExtractor` with a **receipt-specific system prompt**;
  the same JSON schema (deposit ignored). Append via a dedicated
  `POST /api/estimates/{id}/receipt-items/commit` (SIGNED → 409, like every other item write).
- **Receipt gate = new `Feature.RECEIPT_IMPORT`, PRO+TEAM** (kept distinct from
  `ESTIMATE_IMPORT` so it can be sold/tuned independently). FREE sees the fab item and gets
  the painted-door upsell — the established "visible-to-all, upgrade-on-tap" pattern.
- **Photo tab gate = existing `Feature.PHOTO_REPORTS`** — already granted to **all plans**
  (incl. FREE) by design ("show the client the product" — sharing progress is that growth
  lever). So the tab is available to everyone; the PRO value is the receipt-AI. The gate is
  routed through `FeatureGuard` anyway, so flipping it to PRO later is a one-line matrix edit.
- **Receipt photo storage is PRO in practice** — reachable only through the PRO receipt flow;
  always `PRIVATE`, no share toggle (financial data).
- **File privacy:** photos never go through public `/api/files/**`.
  - `GET /api/projects/{id}/photos/{photoId}/file` — JWT, owner-scoped (`loadOwned`); serves
    any of the master's own photos.
  - `GET /api/public/estimates/{token}/photos/{photoId}/file` — share-token validated; serves
    a photo only if it belongs to the token's object **and** `visibility = SHARED`.
  The storage key (`photos/uuid.jpg`) is never exposed to the client as a URL.
- **Receipt bytes on save:** `parse` discards the file (privacy invariant). To save the
  receipt photo the **PWA re-uploads the same `File`** it still holds in memory (source=RECEIPT
  + estimateId) — the backend never buffers the file between parse and save.

## Data model — `project_photo` (V47)

```
project_photo
 ├─ id UUID PK
 ├─ project_id UUID NOT NULL  FK → projects(id) ON DELETE CASCADE
 ├─ storage_key TEXT NOT NULL           -- StorageService key, e.g. photos/uuid.jpg
 ├─ source VARCHAR(20) NOT NULL         -- RECEIPT | MANUAL   (CHECK)
 ├─ visibility VARCHAR(20) NOT NULL     -- PRIVATE | SHARED   (CHECK); receipts always PRIVATE
 ├─ caption TEXT                        -- optional master note (MANUAL)
 ├─ estimate_id UUID  FK → estimates(id) ON DELETE SET NULL  -- RECEIPT link (survives estimate delete)
 ├─ estimate_name_snapshot TEXT         -- estimate name at capture time (durable label)
 └─ created_at TIMESTAMPTZ NOT NULL DEFAULT now()
CREATE INDEX idx_project_photo_project ON project_photo(project_id);
```

Owner isolation is via the object (project) in the service (`ProjectService.loadOwned`),
mirroring `measurement_room` — cascade drops photos with the object.

## API contract

Consolidation (EstimateController, mirrors `/api/projects/{projectId}/estimates`):
- `POST /api/projects/{projectId}/estimates/consolidate`
  body `{ name?, estimateIds: [UUID, …] }` → creates a DRAFT estimate with copied items,
  returns the full `EstimateResponse`. Ownership on the project + each source estimate;
  FREE per-project cap enforced.

Receipt items (EstimateController area; RECEIPT_IMPORT gate):
- `POST /api/estimates/{id}/receipt-items/parse` (multipart image) → review payload
  `{ items: [{ name, unit, quantity, unitPrice, type, category?, issues[] }] }`.
- `POST /api/estimates/{id}/receipt-items/commit` (JSON reviewed items) → appends to the
  estimate (SIGNED → 409), returns the updated `EstimateResponse`. No catalog upsert.

Photos (new ProjectPhotoController; PHOTO_REPORTS gate, owner-scoped):
- `GET    /api/projects/{id}/photos` — list (metadata; each with a file URL to the auth stream).
- `POST   /api/projects/{id}/photos` (multipart image + source + optional caption + estimateId) — upload.
- `PATCH  /api/projects/{id}/photos/{photoId}` — set visibility (MANUAL only).
- `DELETE /api/projects/{id}/photos/{photoId}` — delete (removes the stored object too).
- `GET    /api/projects/{id}/photos/{photoId}/file` — **authenticated** stream (owner).

Portal (PublicEstimateController; token-gated, SHARED only):
- `PublicEstimateView` += `sharedPhotos: [{ id, caption }]` (list of the object's SHARED photos).
- `GET /api/public/estimates/{token}/photos/{photoId}/file` — stream a SHARED photo of the token's object.

## Work chunks

- [x] Backend: `Feature.RECEIPT_IMPORT` + grant PRO/TEAM in `PlanConfig`.
- [x] Backend: `EstimateService.consolidate` + controller route + tests (copy/renumber/cross-project/default-name).
- [x] Backend: receipt prompt in `ClaudeEstimateExtractor` (refactored `call(content, systemPrompt)`)
      + `ReceiptImportService` (parse image → review; commit → `appendItems`) + DTOs + `ReceiptImportController` + tests.
- [x] Backend: V47 + `ProjectPhoto` entity + `PhotoSource`/`PhotoVisibility` enums + repository.
- [x] Backend: `ProjectPhotoService` (PHOTO_REPORTS gate, owner-scoped, StorageService, magic-byte
      validation) + `ProjectPhotoController` (list/upload/patch/delete/auth-stream) + tests.
- [x] Backend: portal `sharedPhotos` in `PublicEstimateView` + token-gated stream; `PublicEstimateIsolationTest`
      already guards the new `SharedPhoto` subtree (no forbidden fields); `PublicEstimateServiceTest` ctor updated.
- [x] Backend: reused `error.import.*`/`error.ai.*`/`error.upload.*` bundle keys (made `error.ai.unavailable`
      modality-neutral); Swagger via annotations; CLAUDE.md note added. **Build gate: user runs `./gradlew build`.**
- [x] PWA: consolidate button (list≥2) + `ConsolidateSheet` picker on `ProjectDetailPage`.
- [x] PWA: «Додати з чеку» fab item on `EstimateEditorPage` (FREE → `UpgradeIntentModal` trigger `RECEIPT_IMPORT`
      / PRO → `ReceiptImportSheet` wizard: camera/upload → parse → review → commit → save-photo confirm).
- [x] PWA: real «Фото» tab (`PhotosSection`: gallery via authenticated blob fetch, upload, visibility toggle,
      receipt label) replacing the placeholder.
- [x] PWA: portal `static/portal/index.html` renders a shared-photos gallery (`renderPhotos`) when present.
- [x] PWA: i18n `consolidate.*`/`photos.*`/`receipt.*` (uk+en), tests (`ConsolidateSheet`, `ReceiptImportSheet`),
      version bump 0.8.0, tsc/vitest(91)/build green.

## What shipped (files)

- **Backend:** `EstimateService.consolidate`/`appendItems`, `EstimateController` route, `EstimateConsolidateRequest`;
  `ReceiptImportService`/`ReceiptImportController`/`ReceiptItemsCommitRequest`, receipt prompt in `ClaudeEstimateExtractor`;
  `V47__project_photo.sql`, `ProjectPhoto`/`PhotoSource`/`PhotoVisibility`/`ProjectPhotoRepository`,
  `ProjectPhotoService`/`ProjectPhotoController`/`ProjectPhotoResponse`/`PhotoVisibilityRequest`;
  `PublicEstimateView.sharedPhotos` + portal stream; `Feature.RECEIPT_IMPORT` + `PlanConfig`.
  Tests: `EstimateServiceTest` (+consolidate/append), `ReceiptImportServiceTest`, `ProjectPhotoServiceTest`,
  `PublicEstimateServiceTest` (ctor).
- **PWA:** `api/estimates.consolidate`, `api/receiptImport.ts`, `api/photos.ts`, types; `usePhotos.ts`;
  `ConsolidateSheet.tsx`, `PhotosSection.tsx`, `ReceiptImportSheet.tsx`; wired into `ProjectDetailPage`/`EstimateEditorPage`;
  i18n + `ConsolidateSheet.test`/`ReceiptImportSheet.test`; version 0.8.0.

## Not changed / confirmed

- Estimate money math / statuses / SIGNED immutability / reopen — unchanged; consolidation and
  receipt-append go through the existing item-save invariants (SIGNED → 409).
- `/api/files/**` stays public and logo-only; photos use the new non-public paths.
- `ESTIMATE_IMPORT` and `AI_ASSISTANT` unchanged; receipts use their own `RECEIPT_IMPORT` gate.
- No dedup on consolidation; no catalog write on receipts (both by explicit decision).

## Follow-up: catalog duplicates (V48 + V49)

Surfaced while testing: a real master's catalog showed exact duplicates (same name+type+unit,
e.g. «Монтаж будівельного риштування» ×2). Root cause = **three inconsistent dedup keys**
across insert paths (template copy `lower(name)|type|unit` no-trim; import `trim.lower(name)|type`
no-unit; manual `create` had **no** dedup), so an item created by one path was invisible to
another's dedup.

- **V48** — one genuine seed defect: «Армування кладки сіткою арматурою» existed under BUILDER
  (WORK/M2) and DRYWALL (WORK/LINEAR_METER) — divergent unit broke the (name,type,unit) dedup →
  a BUILDER+DRYWALL master got it twice. Dropped the DRYWALL variant (masonry reinforcement is a
  builder line; it was the only template name across all 983 with a divergent unit/type — «Щебінь
  5-20» M3 vs T is a legitimate volume/weight pair, kept).
- **V49** — structural fix: dedupe existing `catalog_items` (keep highest price, then oldest id,
  per `owner_id, lower(trim(name)), type, unit`) then a **UNIQUE expression index**
  `ux_catalog_items_owner_name_type_unit` → duplicates are now impossible regardless of path.
  Validated on the live DB (0 local deletions, index builds clean). Items differing in unit/type
  are NOT merged (legit variants survive).
- Code: unified the template-copy dedup key to `trim().toLowerCase()` (matches the index);
  `CatalogService.create` is now **idempotent** (same name+type+unit → updates in place, never a
  second row, never hits the constraint); `GlobalExceptionHandler` maps the index violation to a
  friendly **409 `CATALOG_ITEM_DUPLICATE`** (safety net for e.g. a rename clash), not a 500.
  Import / estimate-import upsert paths were verified to never insert a violating row.
- Tests: `CatalogServiceTest` (create-idempotent update-in-place). Build on the user.

## Follow-ups (polish, after live testing)

- **Photo limits + compression** — per-object caps with separate budgets: FREE 5 / PRO+TEAM 50
  progress photos; receipts 50 (FREE 0 — receipts are PRO anyway). `Limit.MAX_PHOTOS_PER_OBJECT`
  / `MAX_RECEIPT_PHOTOS_PER_OBJECT` in `PlanConfig`, enforced by `LimitService.requireCanAddPhoto`
  (counts by source). Server hard cap **8 MB** + **client-side downscale to ~2048px/JPEG**
  (`lib/image.ts`) before upload — a 6 MB phone photo becomes <1.5 MB invisibly. `403
  PHOTO_LIMIT_REACHED` / `RECEIPT_PHOTO_LIMIT_REACHED` (uk «фото» is plural-invariant, no plural
  helper); `PlanLimitsResponse` carries the caps for preventive UI (disable + upsell).
- **Receipt extraction quality** — the receipt vision prompt was rewritten around the exact
  Ukrainian fiscal-receipt layout (qty×price line above the name; VAT letter; `#`-article line
  with the unit) with an anchor «one item per `#`-article line», so dense receipts don't
  under-extract. Parse now sends the **full-resolution** image (downscale only for the *stored*
  receipt photo) — small monospace receipt text was lost at 2048px.
- **Small UX:** `ConsolidateSheet` pre-fills the name «Зведений кошторис» (editable); `ItemForm`
  shows an **empty** quantity/price field when the value is 0 (an imported line) so there's
  nothing to erase on mobile — save still requires a positive number (`decimalString`).
- **Fullscreen photo viewer (lightbox)** — tapping a photo in the «Фото» tab opens it full-screen
  (`object-contain`), close on backdrop / ✕ / Esc, prev/next chevrons + ←/→, a «n / N» counter,
  body-scroll lock. Reuses the authenticated blob fetch (`usePhotoBlobUrl`). Built for phones
  (≈99% of users); no backend change — same `GET …/photos/{id}/file` stream.
- Tests: `LimitServiceTest` (+photo caps), `ProjectPhotoServiceTest` (+limit), `CatalogServiceTest`
  (+create idempotency). PWA green (tsc / vitest / build). App version `0.8.1`.

## Gotchas

- Jackson 3 (`tools.jackson.*`) for any ObjectMapper use.
- Structured-output schema stays plain (no numeric constraints), reused from the estimate extractor.
- Cascade: delete object → photos (FK CASCADE); delete estimate → receipt photo's `estimate_id`
  goes NULL but the `estimate_name_snapshot` keeps the label.
- Photo reads must NOT leak the storage key; the client only ever gets endpoint URLs.
- Portal photo stream must re-check `visibility = SHARED` AND same-object every request.
