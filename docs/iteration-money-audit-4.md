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

---

# Part 2 — §2-§4 of `FIXES-4.md`, backend, 2026-10-08/09

**Status:** shipped in two commits; `./gradlew build` green. The second one carries migration
**V149** (B-92 + B-105 + B-103, which drops `estimates.economy_visible`).

## Acts
- **B-93** — a SENT act can no longer be shrunk under its advance: `replaceItems` and act-receipt
  update/delete run `ActAdvanceGuard` when the act is SENT (the portal signature skips the guard by
  design). IT `aSentActCannotBeShrunkUnderItsAdvance`.
- **B-95** — V144's three unique indexes map to 409 (`WORK_ACT_OPEN`, `WORK_ACT_FINAL_EXISTS`,
  `WORK_ACT_ADDENDUM_TAKEN`) instead of a 500; handler test.
- **B-96** — the «receipts to expenses off» IT flipped a detached entity; it now goes through
  `updateHeader` and asserts the flag. The estimate-signed push moved after the commit
  (`AfterCommit`, like the act sign since B-81).
- **B-97** — a line's amount is capped at 999 999 999 999.99 on both the estimate line and the act
  line (`EstimateItemRequest.MAX_LINE_AMOUNT`): the act line had no product bound (a 500 on
  `numeric(15,2)`), and the estimate's 9 999 999 999 999.99 was ten times what `HryvniaInWords`
  can spell. The sign-time reconciler reads object receipts FOR UPDATE
  (`findIdentifiedByProjectIdForUpdate`), so a master's concurrent PATCH waits or loses its own
  version check — the client's signature never fails with 409. The photo-only «це той самий чек?»
  warning is recorded in open-questions.

## Economy, payments, cash, crew
- **B-98** — `ClientSafeName` strips EVERY signed rate anywhere in the name («Санвузол +20%
  (копія)», «Санвузол +20% +5%»), and the act portal (and the act PDF model) use it.
- **B-99** — the accepted crew margin prorates each «%» line by the line it follows, running
  `ActAdjustmentCalculator.adjustmentsPerType` over the client's sheet and over the crew's (closed
  shares scaled per line) — not one Σ adjustments ÷ Σ client % ratio. The review's own case (+10 %
  of L1 priced, −10 % of the estimate unpriced, an act closing L2) now accepts 800 (was −109.09).
  `sumSignedActAdjustments` is gone; the callers pass `sumSignedLineTotalsByEstimateItem`.
- **B-101** — `ProjectDeleteGuard` also refuses an object holding any till receipt worth money.
- **B-102** — owner ruling: refuse. `PaymentService` refuses a material refund on a planned stage
  (add and edit, 400 `error.payment.refund-on-stage`); the PWA no longer offers the tick on a stage
  and lets an old stage refund only LOSE its tick.
- **B-104** — a «Мої гроші» edit that omits the date or the category keeps the stored ones (payment,
  till receipt, own row); `CashEntryRequest.materialRefund` is a required `@NotNull Boolean`
  (contract snapshot refreshed and copied to the PWA, whose type already required it).
- **B-106** — a discount must be strictly below 100 % (100 % floored every price at 0,01 ₴); the
  cash summary reads the clock once; the editor shows no crew margin for a superseded copy.

## Norms, params, storage
- **B-111** — `StorageCleanup.onRollback` deletes a just-stored photo when the transaction rolls
  back (at the save or at commit); the old catch-and-`afterCommit` never ran on a rollback. Both
  receipt creators were already safe (non-transactional callers delete on any failure).
- **B-112** — a params save takes the estimate's row lock first (concurrent first saves no longer
  500 on the partial unique index); values carry `@Digits(4, 3)` (0.0004 was a 500); answers about
  a line are dropped when its name or unit changes; `MaterialParamsControllerTest` covers the PUT
  over HTTP (bounds, a foreign estimate → 404).
- **B-113** — `GKL_SHEET` refuses a side outside 500-4000 mm on write (the open question is
  resolved); the rest of B-113 had already landed with V146.

## Tests touched by the new rules
- `PaymentTransferIntegrationTest` seeds its stage refund by SQL (a row an older build could write).
- `IdorMatrixIntegrationTest`'s cash body carries `materialRefund`.

## Part 2b — the rest of §2-§4 (2026-10-09)

- **B-92 (owner ruling: fix forward, V149 PART 1)** — an `ADDITIONAL` act line that carries an
  `estimate_id` (no additional line ever does), or that sits on a SIGNED act whose ADDENDUM holds no
  line of its name, is an estimate line whose position was deleted: it gets `line_kind = 'ESTIMATE'`
  back. Only the kind moves — no amount, no quantity. An act with no ADDENDUM at all is left alone.
  `WorkActLineKindMigrationOnLiveDataIntegrationTest` now asserts the orphan is ESTIMATE and that the
  unconditional (ADDITIONAL) total holds only real off-estimate work. **Run this before deploying
  V149 to see what it touches** (read-only):

  ```sql
  SELECT wa.project_id, wa.number, wai.id, wai.name, wai.line_total, wa.status
    FROM work_act_item wai JOIN work_act wa ON wa.id = wai.work_act_id
   WHERE wai.line_kind = 'ADDITIONAL'
     AND (wai.estimate_id IS NOT NULL
          OR (wa.status = 'SIGNED' AND wa.addendum_estimate_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM estimate_items ei
                               WHERE ei.estimate_id = wa.addendum_estimate_id
                                 AND lower(trim(ei.name)) = lower(trim(wai.name)))))
   ORDER BY wa.project_id, wa.number;
  ```
- **B-105 (V149 PART 2)** — `estimates.crew_priced`: a markup copy, or any copy of a crew-priced
  sheet (backfilled recursively along `duplicated_from_id`). The crew-margin panel and the economy's
  `findSignedMarkupDuplicates` gate on it instead of `markup_percent > 0`, and `duplicate()`
  inherits `source_unit_price` only from a crew-priced source. A −5 % copy of a +20 % copy shows its
  1 400 margin; a +20 % copy of a −10 % copy measures against the discount sheet (crew 9 000,
  margin 1 800). Already-stored lines of such chains are not rewritten.
- **B-94** — «ДОВІДКОВО» on a SIGNED act asks «first act?» as of its own `signed_at`
  (`existsByProjectIdAndStatusAndSignedAtBefore`), and `sumIncomeCountedAsOf` counts a parent that a
  copy superseded AFTER the act. Limit: a manual uncount and a consolidation's sources carry no
  timestamp and are still read as they stand today.
- **B-103 — the ECONOMY portal has no picker any more (owner decision 2026-10-09, option «г»).**
  The review's case: two counted estimates 30 000 + 20 000, one ticked, 40 000 received — the client
  read «Залишок 0» while 10 000 was owed. The «shared sections only» isolation rule came from the
  payments-economy-portal iteration, where the economy sheet copied the SIGNATURE sheet's checkboxes;
  for SIGNED estimates the pick protected nothing — the client had already read them, online or on
  the paper the master signed and handed him — and a forgotten tick was the whole bug.
  Now the page is every SIGNED ∧ counted estimate plus the ADDENDUM (`PublicEstimateService.isTheDeal`,
  the one rule the page and its PDF/question doors ask), so «За договором» there IS
  `sumIncomeCounted`. «Counted» is the one switch for «not this client's deal». `EconomyUpdateRequest`
  carries only `paymentsVisible`; V149 PART 3 drops `economy_visible`; the PWA sheet lists what the
  client sees read-only. «Прибрати все з порталу» had nothing to untick, so the economy sheet closes
  the LINK instead: `DELETE /api/projects/{id}/portal/economy` revokes it, the next publish mints a
  new token (IDOR case added). **Behaviour change for links already sent:** a client whose master
  ticked only some signed estimates now sees all of them — the preview SQL below counts those
  objects; it must run BEFORE the deploy, the column it reads is dropped.
- **B-96** — `AfterCommitTest` (a rolled-back transaction sends nothing; a committed one sends
  once) and the deterministic receipt race in `WorkActConcurrencyIntegrationTest` (both pre-flights
  pass, the loser meets a duplicate key, one row, the loser reads the winner).

# Part 3 — §5 of `FIXES-4.md`, PWA (2026-10-09)

PWA gate green (lint → `tsc -b` → `typecheck:tests` → vitest 1306 → `vite build`), offline
`shell.spec` green. PWA 1.49.3.

- **P-58** — every money/quantity field submits what its validator parsed: `ItemForm` (price and
  quantity through `parseMoney`/`parseQuantity`; «1'200» was NaN), the duplicate and markup sheets
  (`parseMoney` with the server's bounds — two decimals, a markup ≤ 999,99 %, a discount < 100 %),
  `SaveToCatalogPrompt` (blank still 0), `catalogItemSchema`, dictation and receipt import (blank 0,
  garbage blocks the commit instead of becoming 0 ₴), and estimate import seeds cells at the stored
  scale (a negative price arrives blank). P-66's two rounding remainders in the same code: the «%»
  preview uses `roundMoney`, the markup sheet's totals `sumMoney`.
- **P-59** — the optimistic payment patches round in kopecks, a pre-filled remaining is
  `roundMoney`'d («3000.2», not «3000.2000000000003»), and a stage must be at least 0,01.
- **P-60** — a receipt STUCK in transport is still the master's money on its way: shown and counted,
  it holds Sign/Share back, and the gate names it («не вдалося відправити — спробуйте ще»). Only a
  server refusal is set aside.
- **P-61** — TRANSFER carries a client id, one per opening of the sheet, so a retry is a replay.
- **P-62** — the estimate's PDF and share flush the queue and refuse while an op on the estimate (or
  one waiting on it) is left; the add/edit/dictation/receipt sheets close when the estimate becomes
  signed. Guarded by a source-reading test (`EstimateEditorPage.queueGate.test.ts`).
- **B-102's PWA half** shipped in part 2.

- **B-103's PWA half** — `SharePortalSheet` in economy mode: read-only list, payments toggle,
  «Закрити посилання» once a link exists.

Still open from `FIXES-4.md`: §6 (P-63 … P-72).

## Preview before the V149 deploy — economy portals that will show MORE (B-103)

Read-only; run on prod BEFORE deploying, `economy_visible` does not survive V149.

```sql
SELECT p.id AS project_id, p.name AS object, u.email AS master,
       count(*) FILTER (WHERE NOT e.economy_visible) AS newly_shown,
       sum(e_total.total) FILTER (WHERE NOT e.economy_visible) AS newly_shown_total
FROM project_share_links l
JOIN projects p ON p.id = l.project_id
JOIN users u ON u.id = p.owner_id
JOIN estimates e ON e.project_id = p.id
LEFT JOIN LATERAL (SELECT COALESCE(SUM(line_total), 0) AS total
                   FROM estimate_items WHERE estimate_id = e.id) e_total ON true
WHERE l.kind = 'ECONOMY' AND NOT l.revoked AND (l.expires_at IS NULL OR l.expires_at > now())
  AND e.status = 'SIGNED' AND e.count_in_economy AND e.kind <> 'ADDENDUM'
GROUP BY p.id, p.name, u.email
HAVING count(*) FILTER (WHERE NOT e.economy_visible) > 0
ORDER BY newly_shown_total DESC;
```


