# Iteration: room-sketch photo → measurements (Claude vision)

A master photographs their hand-drawn field sketch (кроки) of a room with sizes; Claude
vision reads the shapes + numbers; we **redraw the room with our own clean schema** beside
the photo; the master compares two drawings, fixes anything off, confirms — and measurements
are created. Nothing is created without confirmation; the server recomputes every result.

- **Status:** 🔨 Code complete — PWA green (tsc / 128 tests / build); backend build on the user.
- **App version:** PWA `0.11.0 → 0.12.0` (new headline capability → minor).
- **Migrations:** **none** — reuses `measurement_room`/`measurement_item` (V46).
- **PRO-gated:** new `Feature.SKETCH_IMPORT` (PRO+TEAM). Measurements are already PRO, so the
  entry lives inside the PRO measurements section (FREE never reaches it).

## Why a sketch works (where a room photo wouldn't)
The numbers are **written by hand** — the LLM *reads* them instead of guessing scale — and the
drawing gives **topology** (which shape, what connects to what). So it's a reliable extraction
task like the receipt/estimate import, not magic. **The real danger** isn't "can't read" but
"reads a real number and attaches it to the wrong side": the area comes out *plausible*, the
master doesn't notice, and it flows into money. **The guard is our redrawn schema beside the
photo** — the master verifies two pictures in a second, not a column of numbers. The shape
calculator (previous iteration) became the safety net for the LLM.

## Recon (confirmed before coding)
- Prerequisites present: Заміри (V46) + shapes-with-schemas (`shapes.ts`/`ShapeDiagram`/
  `Shapes.java`, previous iteration). Without them there'd be nowhere to import to and nothing
  to compare against — the prompt says WAIT; they're here.
- `ClaudeEstimateExtractor` was already parameterised (estimate + receipt prompts) but the
  round-trip hard-coded one `SCHEMA`. Extracted a reusable `requestJson(content, prompt, schema)`
  so a THIRD prompt reuses the ONE Anthropic client — no duplicated HTTP/error handling.
- `ReceiptImportSheet` is the UX template (camera/upload → parse → review → commit → "keep the
  photo?"); `MeasurementItemForm`/`ShapeInput` already draw our schema + compute — reused for review.

## Backend
- `Feature.SKETCH_IMPORT` + PlanConfig (PRO, TEAM).
- `ClaudeEstimateExtractor`: `requestJson(...)` (public transport) + `imageContent(...)` made
  public static. `call()` refactored to `parse(requestJson(...))` — estimate/receipt behaviour
  unchanged (their tests stay green).
- `SketchImportService` (`service/measurement/`) — the third prompt + a strict JSON schema
  (plain string/number/boolean + sentinels + all-required, matching the existing extractor's
  schema style; enums validated server-side). Maps the model output into the manual editor's
  payload shape: SURFACE planes → `{unit: unitGuess, segments:[{shape,mode,values}], openings}`
  (unreadable/0 letters **omitted** → the review field renders blank); PARTITION/LINEAR dims
  **converted to metres** via the unit guess (those payloads carry no unit). Each `result` is
  computed with `MeasurementCalc` in a try/catch — invalid/incomplete → null + forced low
  confidence. Unknown shapes dropped. Nothing persisted; image discarded.
- `SketchImportController` — `POST /api/projects/{id}/measurements/sketch/parse` (multipart) +
  `/commit` (JSON). Same media-type + empty-file guards as the receipt controller.
- `MeasurementService.createFromSketch` — creates the confirmed rooms+elements in ONE
  transaction, recomputing every `result` server-side (the client's number is never trusted),
  returns the fresh tree. `SketchCommitRequest` reuses `MeasurementItemRequest` so manual and
  sketch commit share one validation + calc surface.
- Failure (no key / call / parse) → `AiExtractionException` → **503 `AI_UNAVAILABLE`** (synchronous,
  the master waits), consistent with the estimate/receipt imports.

## PWA
- `MeasurementItemForm` gained a **review mode** (two optional props): `hostUnit` (the SURFACE unit
  is driven by the parent's sheet-level dial, so one switch reinterprets every surface at once) and
  `onLiveChange` (streams the current request up, hides the Save/Cancel footer). Manual flow
  unchanged — props optional.
- `SketchReviewSheet` — the flow + the **review screen (the heart)**: the sketch photo on top
  (tap → fullscreen), sheet-level warnings, one unit dial (default = the guess), rooms (rename/
  delete) → elements, each rendered as a flagged card (confidence badge + note for low/medium) +
  the shared editor drawing OUR schema. Low-confidence/invalid elements **block commit** until
  fixed or deleted. Commit seeds the measurement cache; then offers to keep the sketch as a
  **PRIVATE** object photo (reuses `photosApi` source MANUAL).
- Stable-callback wrapper `SketchReviewItem` memoises `onLiveChange` off the parent's dispatcher —
  without it the editor's live-effect would loop (new req object each render → setState → render).
- Entry: `📷 Розпізнати ескіз` in the PRO measurements section (header + empty state).
- i18n `sketch.*` (uk + en).

## Tests
- Backend `SketchImportServiceTest` (mock the Anthropic round-trip, real calc/geometry): rectangle
  in cm → 7.5 m² + unreadable letters omitted; unreadable size → null result + forced low; partition
  cm → metres (6.75); unknown shape dropped → no result; feature-gated; commit delegates + recomputes.
- PWA `SketchReviewSheet.test` (mock api): shows rooms/warnings, flags low confidence, **blocks
  commit until the flagged element is fixed/removed**, commits the confirmed rooms. `MeasurementItemForm`
  and `shapes` tests stay green.

## Deviations (deliberate — flag if unwanted)
- **Sketch photo saved as `PhotoSource.MANUAL`** (with a caption), not a new `SKETCH` source — a new
  enum value would need a migration + CHECK change for a purely cosmetic distinction. MANUAL is
  PRIVATE by default, which is the requirement.
- **PARTITION/LINEAR are converted to metres at parse**, so the sheet unit dial only reinterprets
  SURFACE elements. Hand sketches are overwhelmingly surfaces; a wrong unit on a partition is edited
  directly. Noted rather than building a second per-type unit path.
- **FREE upsell** is via the existing MEASUREMENTS teaser (FREE can't reach the PRO section), not a
  separate SKETCH_IMPORT painted-door — the backend gate still exists for correctness.

## Not changed / confirmed
- Manual measurements, the shapes module, substitution into estimates, the unit filter, selection
  memory — untouched; the sketch only *creates* measurements, which then behave as manual ones.
- Estimate + receipt imports unchanged (same client, added a third prompt); their tests stay green.
- Owner-scoped; result server-authoritative; sketch photo PRIVATE (never portal/PDF).

## Gotchas
- The live-change effect **will infinite-loop** if `onLiveChange` isn't identity-stable — the
  `SketchReviewItem` wrapper + `useCallback` is load-bearing, not cosmetic.
- Structured-output schema uses the extractor's proven plain-type + sentinel style (no enums /
  nullable unions); shape/mode/type/unit/confidence are validated in Java, not by the schema.
- `MeasurementCalc` catches invalid geometry → the sketch maps that to "flagged, blank field",
  which is exactly the "don't guess an unreadable size" requirement.
