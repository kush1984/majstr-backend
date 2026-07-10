# Iteration: estimate deposit/balance + measurement calculator

Inspired by a real stretch-ceiling master's Excel (rooms with auto-computed area;
Загальна вартість / Завдаток / Залишок at the bottom). Two independent chunks, quick
wins first. A third, bigger feature — **LLM Excel-estimate import** — is scheduled next
(see open-questions "Import an ESTIMATE from a file"), not in this iteration.

- **Status:** Chunk 1 ✅ shipped (PWA gate green). Chunk 2 🔨 code-complete — awaits
  the user's `./gradlew build` (migration + entity change). PWA side already green.
- **Migrations:** `V43__add_estimate_deposit.sql` (chunk 2).
- **App version:** PWA `0.4.1 → 0.5.0` (minor — new feature).

## Chunk 1 — Measurement → quantity calculator (frontend-only)

- **Goal:** master measures sides, app computes the quantity. Small helper on the
  quantity field in the line-item form: area (д×ш → м²), length/perimeter (→ м.пог),
  minus openings (прорізи ш×в×к-ть). Result is written into the existing `quantity`
  field; dimensions are **not** persisted.
- **Where:** new `MeasureCalculator.tsx`, wired into `ItemForm.tsx` next to the
  quantity field (uses `setValue('quantity', …)`). Default mode picked from the unit
  (M2 → area, M/LINEAR_METER → length).
- **No backend change.** Verified via `tsc -b` + `vitest` + `vite build`.

## Chunk 2 — Deposit → balance (завдаток / залишок)

- **Goal:** record a deposit on an estimate; show Разом / Завдаток / Залишок on the
  estimate, the client portal, and the PDF. Balance = `max(0, total − deposit)`.
- **Backend:**
  - `V<N>__add_estimate_deposit.sql` — `ALTER TABLE estimates ADD COLUMN deposit_amount NUMERIC(15,2)` (nullable).
  - `Estimate.depositAmount`; `EstimateResponse` gains `depositAmount` + computed
    `balance`; `EstimateUpdateRequest` gains optional `depositAmount`.
  - `EstimateService.update` sets the deposit (locked once SIGNED, like other edits).
  - `PublicEstimateView` (portal DTO) + `EstimatePdfService` show завдаток/залишок when
    a deposit is set (client-facing — deliberately not isolated).
- **PWA:** deposit input in the estimate editor (⋮ actions or summary); Разом/Завдаток/
  Залишок display; portal + PDF already come from the backend.
- **Tests:** service (deposit set → balance; signed → locked), portal-isolation stays
  green, controller mapping.

## Not changed / confirmed
- **Isolation:** `PublicEstimateIsolationTest` is a **denylist** (expense/profit/economy/
  cost/margin) — `depositAmount`/`balance` are client-facing and pass unchanged. Deposit
  is *meant* to reach the portal/PDF.
- **Signed lock:** deposit is only settable via `EstimateService.update`, which throws
  `EstimateSignedException` on a SIGNED estimate — so deposit is locked with everything
  else once signed (covered by the existing signed-rejection test).
- Balance is **computed, never stored** (`max(0, total − deposit)`), in three places that
  each already summed line totals: `EstimateService.toResponse`, `PublicEstimateService.
  buildView`, `EstimatePdfService.addTotals`.

## Gotchas
- **Record fan-out:** adding fields to `EstimateResponse`, `EstimateUpdateRequest` and
  `PublicEstimateView` changed their canonical constructors. Grepped `new <Type>(` across
  main+test and fixed all call sites: `EstimateTemplateServiceTest`,
  `EstimateTemplateControllerTest` (EstimateResponse fixtures, +`null, ZERO`), and three
  `EstimateServiceTest` update calls (+`null` deposit).
- **Frontend `balance` is required** on `EstimateResponse` (always present) while
  `depositAmount` is optional (`?: number | null`) — the backend strips null via
  `non_null` inclusion, so the field is simply absent when there's no deposit.

## Tests
- Backend: `EstimateServiceTest` +3 (`setsDepositAndComputesBalance`,
  `clearsDepositWhenNull`, `depositExceedingTotal_clampsBalanceToZero`).
- PWA: `MeasureCalculator.test.tsx` (6, `computeMeasure`). Deposit UI is display/wiring
  over the tested backend math. PWA gate: **tsc + vitest 80/80 + vite build** green.
