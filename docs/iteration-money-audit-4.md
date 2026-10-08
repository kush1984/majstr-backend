# Review round 4 (`FIXES-4.md`), part 1 — «Fix these first», 2026-10-08

**Status:** built, `./gradlew build` green (Docker up, all ITs ran); PWA gate green (lint →
`tsc -b` → `typecheck:tests` → vitest 1295 → `vite build`), offline `shell.spec` green.
**No migration.** PWA 1.49.1.
Source: `C:\Work\prompts\FIXES-4.md` §1, reviewed state backend `da0ee59` / PWA `835674a`.

**Scope:** every item of §1. Owner rulings: B-87 cumulative with an exact square-off, and
**already-signed acts are not touched — a report only** (option «б», 2026-10-08); B-88 re-binds;
B-102 (later) refuses.

## PWA

### P-53 — the act share sheet published in an endless loop
`ActShareSheet`'s publish effect listed `onClose` in its deps; both callers pass an inline arrow,
and the publish invalidates the act — re-render, new `onClose`, publish again, for as long as the
sheet stayed open, each `PUT …/share` taking the row lock a portal signature needs. The callback
now rides a ref; the effect depends on `[open, actId, qc]`.
Test: a parent re-rendering twice → exactly one publish (fails without the fix: 3). The test's own
wrapper minted a `QueryClient` per render, which by itself re-ran the effect — the client is now
one per test, like the app's.

### P-54 / P-55 — the outbox wiped optimistic money, and the focus gate could stay shut forever
- `SyncStatus` gains **`runnable`**: ops not blocked and not (transitively) held behind a blocked
  op — the same closure `dropBlockedOps` deletes, now one helper `heldEntities`.
- **P-55:** `CLIENT_DRIVEN_QUERY`'s gate reads `runnable`, not `pending`. An op waiting behind a
  refusal stays `pending` until the master resolves the sync sheet, so the focus refetch was off
  app-wide for that long and a client's signature surfaced only through a push.
- **P-54:** `shouldRefetchAfterFlush` — a flush refetches only when it landed or was refused AND
  nothing runnable is left. A merely FAILING op (503, half-open link) no longer triggers a refetch
  that answers without it and wipes its optimistic row on every 15 s / 30 s / 60 s / 2 min retry.
  A refusal still refetches (P-47): what is held behind it is not runnable.
Tests: held-behind-blocked is pending but not runnable; partial flush with a failing op asks for
no refetch, the drained one does; a refusal refetches with dependents waiting; the focus gate opens
while the only queued work is held.

### P-57 — the calculator wrote a stale cached figure over the master's answer
- Fields are seeded only from a calculation **fetched since the screen opened**
  (`isFetchedAfterMount && !isPlaceholderData`) — never from the week-old persisted cache.
- Every later set of stored answers **re-seeds the fields still showing what the screen put there**
  (`reseed` + a `filled` ref); a field the master touched always wins. The thickness suggestion
  waits for that seed and is recorded as «ours», so a stored answer may replace it.
- A save sends **only keys that differ** from `data.answers` (`changedOnly`; the perimeter the same).
- A failed save toasts (`materials.paramsNotSaved`), and «Запамʼятали…» is hidden while a save is
  pending or after it failed; «Порахувати/Перерахувати» is disabled while a save is in the air.
Tests: stale cache + fresh answer shows 25, never 15; only the changed key is sent; a refused save
removes «Запамʼятали». All three fail without the fix.

## Backend

### B-86 — the client could sign lines he never saw
`@Version` only moved when the estimate ROW was written; line edits wrote item rows alone.
`EstimateService.requireNotSigned` — the one door every line write passes — now also `touch`es the
estimate (`updatedAt`, the ordinary optimistic-lock UPDATE, as `WorkActService.touch`), and the
parent-delete cascade touches each SENT copy it trims. Material params (V142) deliberately do not
pass that door and do not move the version.
IT `EstimateVersionOnLineEditIntegrationTest`: add a line → old version → 409
`ESTIMATE_CHANGED`; delete moves the version; the unchanged sheet still signs.

### B-88 — a REJECTED act signed at old prices after the estimate was re-priced
Owner ruling: **re-bind**. REJECTED → DRAFT now re-copies every ESTIMATE line from its position as
it stands (price, unit, name, type, category — only when that estimate is SIGNED, i.e. a contract)
and re-derives the ADJUSTMENT lines from the new prices (`ActLineBinder.rebindToCurrentEstimates`
+ `WorkActService.rebindToCurrentContract`). IT: 100 × 200 act rejected, position re-priced to 150,
back to DRAFT → line 150 / 15 000, signs with accepted ≤ contracted.

### B-89 — a REJECTED act whose estimate was deleted billed money nothing counted
`ActLineBinder.requireStillValid` (publish + both signs) refuses an ESTIMATE-kind line whose
`estimate_item_id` fell to NULL: 400 `WORK_ACT_ESTIMATE_LINE_GONE` («Позиції кошторису, яку
закривав цей рядок, більше не існує — приберіть рядок з акта»). The review's second half — block
the estimate delete while a REJECTED act references it — was **not** taken: «dead paper holds
nothing back» is a shipped ruling (`reopeningWithOnlyAREJECTEDact_isStillAllowed`), and the refusal
at publish already closes the money hole. IT: delete the position under a REJECTED act, back to
DRAFT, sign → 400.

### B-90 — the portal's supersede still checked signed acts only
`PublicEstimateService.requireSupersedable` asks `existsLiveActLineForEstimate` like the master's
own doors. (Their ADDENDUM half is unreachable here: an ADDENDUM is never duplicated.) IT: a SENT act
on the parent → the client's signature on the copy is 409, the parent keeps counting.

### B-91 — a billed object receipt could post a second expense
Two fixes in `ProjectReceiptService.update`: the QR-identity settlement runs **last** (settled
first, its dropped expense was posted straight back by the same save's `reimbursable:false`), and
`applyReimbursable` never creates or resurrects an expense for a receipt billed on a signed act.
IT assertions added to both «same paper on both tables» tests (receipts_to_expenses on and off).

### B-100 — an omitted `discount` returned 500
`EstimateDuplicateRequest` / `EstimateItemsMarkupRequest` `@AssertTrue` methods unboxed a null
Boolean (HV000090 → 500); they use `!Boolean.TRUE.equals(discount)`, so the `@NotNull` reports 400.

### B-87 — «%» adjustments drifted by a kopeck, and nothing squared off the last act
Two roundings drifted on their own: a position closed over several acts priced each part at
price × quantity (100 × 145 over 33.333 / 33.333 / 33.334 → 14 500.01), and each act rounded its
share of a «%» line alone (+5 % on 10 × 187.50 over 1 + 9 → 93.76; −5 % → 1 781.24).
- **The last unit takes exactly what remains** (`ActRepricer.settleLastUnits`): the row closing a
  position's last unit gets the position's total less what signed acts already billed for it — but
  only within a kopeck per billing row of price × quantity; anything bigger is a re-priced position,
  not rounding, and the act's own price stands.
- **Adjustments are cumulative** (`ActAdjustmentCalculator`): this act's share = round(Σ with this
  act) − round(Σ without it), both over what SIGNED acts have CLOSED (new
  `WorkActItemRepository.sumSignedLineTotalsByEstimateItem`). The shares telescope to the estimate's
  own percentage. Earlier acts are read by what they closed, never by their stored adjustment rows,
  so a pre-V141 act signed without one is NOT caught up on the next act.
- **Re-derived at save, at publish, at the offline signature and when a REJECTED act comes back**
  (`ActRepricer.reprice`) — which also gives a draft saved before V141 the discount it never
  carried. Not at the portal signature: between publish and it nothing can move the figures (one
  open act per object; a live act locks its estimate), and re-deriving there would change a page
  the client has already read.
Tests (all fail without the fix): +5 % and −5 % over 1 + 9 m² land on 1 968.75 / 1 781.25;
100 × 145 in thirds lands on 14 500.00 — accepted = contracted to the kopeck.

**Already-signed acts — the report the owner chose instead of a backfill.** Run on production to
see which fully-closed estimates were signed off a kopeck (or, pre-V141, a whole discount) away
from their contract; nothing writes:

```sql
WITH contract AS (
    SELECT e.id, e.project_id, SUM(ei.line_total) AS total
      FROM estimates e JOIN estimate_items ei ON ei.estimate_id = e.id
     WHERE e.status = 'SIGNED' AND e.count_in_economy
     GROUP BY e.id, e.project_id),
billed AS (
    SELECT wai.estimate_id, SUM(wai.line_total) AS billed
      FROM work_act_item wai JOIN work_act wa ON wa.id = wai.work_act_id
     WHERE wa.status = 'SIGNED' AND wai.line_kind IN ('ESTIMATE', 'ADJUSTMENT')
       AND wai.estimate_id IS NOT NULL
     GROUP BY wai.estimate_id),
still_open AS (
    SELECT ei.estimate_id
      FROM estimate_items ei
      LEFT JOIN (SELECT wai.estimate_item_id, SUM(wai.quantity) AS done
                   FROM work_act_item wai JOIN work_act wa ON wa.id = wai.work_act_id
                  WHERE wa.status = 'SIGNED' GROUP BY wai.estimate_item_id) d
             ON d.estimate_item_id = ei.id
     WHERE ei.unit <> 'PERCENT' AND COALESCE(d.done, 0) < ei.quantity)
SELECT c.project_id, c.id AS estimate_id, c.total AS contract, b.billed, b.billed - c.total AS drift
  FROM contract c JOIN billed b ON b.estimate_id = c.id
 WHERE b.billed <> c.total
   AND c.id NOT IN (SELECT estimate_id FROM still_open)
 ORDER BY abs(b.billed - c.total) DESC;
```

## Not changed / confirmed

- Already-signed acts are never rewritten (B-87 ruling «б»); the report above is the whole remedy.
- `journey.spec` (offline e2e) still fails at its seeding step, before any outbox code — the
  open-questions item «The offline JOURNEY spec has rotted»; CI runs `shell.spec`, which is green.

## Gotchas

- A test wrapper that builds a `QueryClient` inside the wrapper component re-creates it on every
  render — any effect depending on `qc` re-runs, and a «runs once» assertion lies in both directions.
- «Pending» is not «in the air»: an op held behind a refusal is pending forever. Anything gating on
  the queue must read `runnable`.
