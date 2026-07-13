# Iteration: object economy rework — honest numbers (count-in-economy flag, cash-flow, negative cash)

The object economy used to sum **all** of an object's estimates (minus REJECTED) as
income. That's wrong now that an object can have variant estimates (econom/premium), a
**consolidated** estimate (its sources + the consolidated), receipt-added lines, and
deposits — the same work got counted 2–3×, and deposits weren't modelled at all.

- **Status:** 🔨 backend code (build on the user); PWA done & green (tsc / vitest / build).
- **App version:** PWA `0.8.1 → 0.9.0` (headline capability change).
- **Migration:** `V51__estimate_count_in_economy.sql` (one boolean column + backfill).

## The model (agreed with the user)

An object is **one deal**. Income = the estimate(s) the master flagged as "the deal",
never the sum of all documents. And three money dimensions are kept **separate** instead
of muddled into one:

| Field | Formula | Negative? |
|---|---|---|
| `contracted` | Σ line totals of **flagged** estimates | — |
| `received` | Σ deposits (завдаток) of flagged estimates | — |
| `spent` | Σ expenses (materials/labor/other) | — |
| `profit` | `contracted − spent` (end-of-job economics) | yes (loss) |
| `cashBalance` | `received − spent` — cash position **right now** | **yes, NOT clamped** |
| `dueFromClient` | `contracted − received` | yes (overpaid) |

**The negative-cash case is the whole point** (the user's example): client paid a 3000
deposit, the master bought 5000 of materials out of pocket on a store run → `cashBalance =
−2000`. The master funds it, then updates the deposit as the client pays; the number
recalculates. We only report honestly — it is **not** clamped to 0 (unlike the client-facing
estimate `balance`, which stays ≥0).

## The mechanism: `count_in_economy` per estimate

`estimates.count_in_economy` (boolean, V51). Income = flagged estimates only.
- **Auto-on** when an estimate is **SIGNED** (`PublicEstimateService.sign`).
- **Consolidate** flags the new consolidated estimate **on** and its sources **off**
  (`EstimateService.consolidate`) → no double-count, automatically.
- Drafts/variants default **off**; the master flags the accepted one.
- **Manual toggle:** `PATCH /api/estimates/{id}/count-in-economy` (`{countInEconomy}`),
  owner-only, any status — for the 99% on phones who don't portal-sign, and to pick
  premium-over-econom. Surfaced on `EstimateSummary.countInEconomy`; a ⋮-menu toggle on
  each estimate row in the PWA.
- Backfill: existing SIGNED estimates → true (V51).

## Receipt → expense (cash-flow loop)

A receipt is both a client-billing line **and** the master's real cost. After a receipt
commit, the PWA offers **«Записати у витрати?»** (amount = the committed lines' total) →
logs an `object_expense` (MATERIALS). PWA-only (reuses `POST /api/projects/{id}/expenses`);
fail-soft (the estimate lines are already committed). This is what drives `spent` up and
`cashBalance` negative in the store-run scenario.

## Changes

- **Backend:** V51 + `Estimate.countInEconomy`; `EstimateRepository.sumIncomeCounted` /
  `sumDepositsCounted`; sign + consolidate auto-flag; `EstimateService.setCountInEconomy` +
  `CountInEconomyRequest` + `PATCH …/count-in-economy`; `EstimateSummary.countInEconomy`;
  `ObjectEconomyResponse` reshaped (contracted/received/spent/profit/cashBalance/dueFromClient);
  `ObjectExpenseService.economy` recomputed. Old `sumIncomeExcludingRejected` left unused.
- **PWA (0.9.0):** `ObjectEconomySection` new numbers (profit + cash tiles; cash red when
  negative; due-from-client / overpaid line); estimate-row ⋮ toggle «враховувати в економіці»
  (`estimatesApi.setCountInEconomy`); receipt→expense prompt in `ReceiptImportSheet`;
  types + i18n (uk+en).
- **Tests:** `ObjectExpenseServiceTest` (flagged income; **negative cash** store-run case);
  `EstimateServiceTest` (consolidate flags new / unflags sources; toggle); `PublicEstimateServiceTest`
  (sign auto-flags). PWA `ObjectEconomySection.test` (new labels + negative cash),
  `ReceiptImportSheet.test` (expense prompt → photo prompt).

## Not changed / confirmed

- Client-facing estimate `balance` (`total − deposit`) stays clamped ≥0 — the portal/PDF never
  show a negative or the owner-only economy.
- Economy DTO stays owner-only; `PublicEstimateIsolationTest` still guards `PublicEstimateView`
  (it doesn't reference the economy DTO).
- Deposits are the current cash-in source; a full payments journal (multiple staged payments)
  stays an open question — the master updates the estimate's `depositAmount` to reflect
  what's received so far.

## Refinement v2 (after live feedback, 2026-07-13) — earnings ≠ materials

The first cut treated income as one `contracted` number and cash as `received − allSpent`.
The master refined the model: **materials are not earnings** (they pass through the master),
and **hand-entered expenses are unforeseen** costs that eat into earnings. Reshaped to:

| Field | Formula |
|---|---|
| `works` | Σ **WORK** line totals of counted estimates (labour = earnings base) |
| `materials` | Σ **MATERIAL** line totals of counted estimates (passthrough; reference) |
| `received` | Σ deposits of counted estimates |
| `spentReceipts` | Σ **RECEIPT**-source expenses (real material cost) |
| `spentManual` | Σ **MANUAL**-source expenses (unforeseen) |
| `cashBalance` | `received − spentReceipts` (materials pot; **not clamped**, negative = out of pocket) |
| `profit` | `works − spentManual` **+ `cashBalance` once the object is COMPLETED** |

- **Leftover deposit → profit at close:** while the object is open, the materials pot
  (`received − spentReceipts`) is shown separately as `cashBalance`. When the object is
  **COMPLETED**, that pot settles into profit — a leftover deposit becomes earnings, an
  overspend reduces them (`ObjectExpenseService.economy` reads `project.getStatus()`).
- **Expense source (V52):** `object_expenses.source` (RECEIPT|MANUAL, default MANUAL,
  backfill MANUAL). The receipt→expense flow sends `source=RECEIPT`; the hand-entry sheet
  omits it → MANUAL. `ObjectExpenseRepository.sumBySource`. `ExpenseResponse` carries source.
- **Works/materials split:** `EstimateRepository.sumWorksCounted` / `sumMaterialsCounted`
  (filter `estimate_items.type`).
- **Two UX fixes shipped alongside:**
  - Deposit (and any estimate) edit now refreshes the economy — `useInvalidateEstimate`
    also invalidates `['object-economy']` (it didn't before → stale numbers).
  - The count-in-economy toggle moved from the hidden ⋮-menu to a **visible checkbox**
    on each estimate row («Враховувати в економіці об'єкта»).
- Label «+ Витрата» → «+ Непередбачувана витрата» (manual expenses are unforeseen now).
- Tests: `ObjectExpenseServiceTest` (works=earnings / manual reduces / materials=cash;
  store-run negative cash; **completed → leftover into profit**). PWA `ObjectEconomySection.test`
  (new labels + breakdown). App version `0.9.0 → 0.9.1`. V52 dry-run clean (3 rows → MANUAL).

## Gotchas

- `cashBalance` / `dueFromClient` / `profit` can be negative — `formatMoney` must render the
  minus (it does); the UI colours negative cash/profit red.
- The flag is owner-only and never leaks; `EstimateSummary` is a list DTO (add the field to
  its factory only).
- Consolidate's auto-unflag runs on the **loaded** source entities (dirty-checked), so it also
  corrects a previously-signed source.
