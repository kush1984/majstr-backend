# Iteration: receipts tied to the estimate (surfacing + PDF + consolidation)

- **Status:** BUILT — Phases 1–4 complete (pending the user's backend `./gradlew build`; PWA gate green).
- **Repos:** PWA (`majstr-pwa`) + backend (`majstr-backend`). Migration **V90**.
- **PWA version:** 1.8.0 (all four phases ship together).

## Goal

A receipt already links to the estimate it paid for (`project_photo.source = RECEIPT`,
`estimate_id`, PRIVATE — see the consolidated/receipts/photos iteration). This iteration makes that
link *useful* to the master:

1. **Surface receipts under the estimate's Materials section**, not in the object «Фото» tab — a
   receipt belongs to the estimate whose materials it documents. **[Phase 1 — done]**
2. **Include receipts in the estimate PDF** — on «Сформувати PDF», ask whether to attach receipts,
   let the master pick which (mobile-friendly thumbnail grid), embed them as a «Чеки» appendix.
   **[Phase 2]**
3. **Let a plain photo be used as a receipt** — a receipt sometimes gets saved as a MANUAL photo;
   allow picking it (and optionally «позначити як чек» → relink) in the PDF picker. **[Phase 3]**
4. **A consolidated estimate pulls in its source estimates' receipts** — needs consolidation
   lineage (today `consolidate` stores none). **[Phase 4]**

## Decisions (agreed with the user)

- **D1** Consolidated pulls receipts by tracking **source-estimate lineage** (option a), not by
  relinking photos — sources keep their receipts. Needs a migration (Phase 4).
- **D2** Receipts render **under the Materials group** in the editor; the block appears even with
  **no material lines** (a receipt kept without moving its positions in). Estimate-linked receipts
  are **removed from the object «Фото» tab**; orphan receipts (estimate deleted → `estimateId` null)
  stay in «Фото» so they can't be lost.
- **D3** PDF preflight = a **thumbnail-grid bottom sheet** (checkboxes, all linked receipts selected
  by default), not a long list and not a navigate-away — best for many receipts on a phone.
- **D4** Plain MANUAL photos can be added in the same picker; a «Це чек» action relinks
  (`estimateId` + `source → RECEIPT`) so it's a first-class receipt next time.
- **D5** In the PDF, receipts are a **«Чеки» appendix at the end** (images, 1–2 per page), not
  inline in the materials table. UI placement (under Materials) ≠ PDF placement (appendix).

## Phase 1 — receipts under Materials (shipped)

**PWA only — no backend change.** The project-photos list already carries `source` + `estimateId` +
`fileUrl`, so the editor filters receipts client-side.

- `src/features/photos/PhotoView.tsx` — **new.** Extracted the reusable viewers from
  `PhotosSection` (`usePhotoBlobUrl`, `AuthPhoto`, `PhotoLightbox`) so both the «Фото» tab and the
  estimate's receipts block share the authenticated-blob loading + lightbox.
- `src/features/photos/PhotosSection.tsx` — imports the viewers from `PhotoView`; the object grid now
  filters OUT estimate-linked receipts (`p.source !== 'RECEIPT' || p.estimateId === null`).
- `src/features/estimate/EstimateReceipts.tsx` — **new.** «🧾 Чеки» block: thumbnails (3-across on a
  phone) + lightbox + per-receipt delete (hidden when signed). Filters
  `source === 'RECEIPT' && estimateId === thisEstimate`. Renders `null` when there are none.
- `src/features/estimate/EstimateEditorPage.tsx` — renders `<EstimateReceipts>` right after the
  items board (Materials is the last group, so it sits under it) and OUTSIDE the empty/non-empty
  branch, so it shows even when the estimate has no lines.
- i18n: `estimate.receipts` (uk «Чеки» / en «Receipts»). Reuses `photos.receipt` + the `photos.delete*`
  keys.
- Tests: `EstimateReceipts.test.tsx` (filters to this estimate; renders nothing when none; no delete
  when signed). `PhotosSection.test.tsx` (estimate-linked receipts hidden from the grid; manual +
  orphan receipts kept).

**Mobile:** 3-column thumbnail grid at 375px, lightbox is the existing phone-first viewer. On-device
verification of the actual block needs a real receipt (auth + backend) — left to the user.

## Phase 2 — receipts in the PDF (built)

- `GET /api/estimates/{id}/pdf?receipts=id1,id2` — [EstimateController]. `EstimateService.renderPdf`
  gained a 3-arg overload; bytes resolved via `ProjectPhotoRepository` + `StorageService` **inside
  EstimateService** (avoids the `ProjectPhotoService → EstimateService` DI cycle). `EstimatePdfService`
  appends a «ЧЕКИ» page-appendix (OpenPDF `Image`; corrupt image skipped, never fatal).
- PWA: `ReceiptPdfSheet` preflight (thumbnail grid, receipts checked by default, «Вибрати/Зняти всі»,
  «Завантажити PDF (N)» / «без чеків»); `estimatesApi.fetchPdf(id, receiptIds)` sends `?receipts=`.
- Tests: PDF appendix (happy + corrupt), ownership filter, picker (default/deselect/clear).

## Phase 3 — pick a receipt saved as a plain photo (built)

- Backend broadened: `loadPdfImages` embeds **any owned photo of the estimate's project**
  (`findByIdAndProjectId`), not only estimate-linked receipts — still owner-scoped, so no
  cross-tenant leak; a foreign id is dropped. (`findByEstimateIdAndSource` from Phase 2 removed —
  unused.)
- PWA: the picker gained an **«Інші фото обʼєкта»** section (the object's MANUAL photos, OFF by
  default); the PDF button now opens the picker when there are receipts **or** other photos, so a
  receipt saved as an ordinary photo is reachable. Test covers other-photos-default-off.
- **Deferred (small follow-up):** a permanent «Це чек» relink (MANUAL→RECEIPT + `estimateId`) needs
  `ProjectPhoto.source` to become mutable (it's `updatable=false`) + a PATCH; the ad-hoc pick already
  covers the need, so this is convenience only.

## Phase 4 — consolidated pulls source receipts (built)

- **Migration V90** `estimate_consolidation_sources` (consolidated_id, source_id — both FK →
  `estimates` ON DELETE CASCADE, composite PK).
- **Entity**: `Estimate.consolidationSourceIds` (`@ElementCollection Set<UUID>`, LAZY). The mapping is
  validate-checked against V90 at every integration test's context boot, so a schema mismatch fails
  the suite at startup (no dedicated round-trip integration test added for that reason).
- **`consolidate()`** stores the (validated, de-duped) source ids; **`toResponse`** surfaces them as
  `EstimateResponse.sourceEstimateIds` (empty otherwise). No relinking — sources keep their receipts;
  the rollup embeds them by id (already project-scoped in `loadPdfImages`).
- **PWA**: `sourceEstimateIds` on the type; the editor's «Чеки» block and the PDF picker both include
  receipts of `[thisEstimate, ...sourceEstimateIds]`.
- Tests: `consolidate_recordsSourceLineage…` (unit); `EstimateResponse` fan-out updated at all 5 test
  construction sites; PWA `EstimateReceipts` source-receipts test.

## Fix (found while checking prod) — consolidate zeroed percent lines

- **Bug:** `consolidate()` copied only `type/name/category/unit/quantity/unitPrice/sortOrder`, dropping
  `percentBaseKind`/`percentBaseItemId`/`lineTotal`. A «% від позиції/кошторису» line then landed as
  `percentBaseKind = null` → `EstimateMath` read it as MANUAL-of-`unitPrice`(=0) → **0,00 ₴**. The
  discount/markup silently vanished and the rollup total stopped matching the sum of its sources
  (client-facing). Pre-existing; never caught because consolidate tests used only ordinary lines.
- **Fix (freeze, backend only):** `copyForConsolidation` freezes each percent line at its source
  `lineTotal` (`baseDetached = true` keeps it through recalc), presents it as MANUAL with `unitPrice`
  reconstructed from the amount (`amount × 100 / percent`) so it reads «−10 % від 5 000 ₴» rather than
  «база видалена». **Exact** money (detached keeps the stored amount; the reconstructed base is
  display-only). No `EstimateMath` / PWA-mirror change; MANUAL + `baseDetached` are already handled.
- **`duplicate` was already correct** — it copies `percentBaseKind` and re-points `percentBaseItemId`
  to the copy (explicit ⚠️ note in-code); only `consolidate` had the naive copy.
- Test: `consolidate_freezesAPercentLineAtItsSourceAmountInsteadOfZeroing` (1000 − 100 = 900, frozen
  line −100, reconstructed base 1000).

## Not changed / confirmed

- Receipt import (`/receipt-items/parse|commit`) is untouched — it still appends lines + the PWA
  uploads the receipt image as a PRIVATE `project_photo` with `estimateId`.
- Receipts remain PRIVATE (never portal/PDF-shared by the visibility rules); Phase 2's PDF embedding
  is the master's own document, not a portal exposure.
