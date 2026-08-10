# Iteration — object-level payments, an honest economy tab, and a client-facing payments card

PWA `1.10.0 → 1.11.0`. Backend: **V93**.

One connected step around MONEY on an object: the deposit moves off the estimate and onto the
project; the economy tab gets a panel per signed estimate plus a payment schedule, visible to FREE
and PRO alike; the client portal gets the same picture; receipts can be shared like progress photos.

---

## 1. Payments move from the estimate to the object

An object usually carries several estimates, but the money arrangement with the client is one
thing, not one per estimate. `Estimate.depositAmount` — a single nullable field, editable only
while the estimate itself is editable — could never express "50% now, 30% after the rough-in, 20%
on completion", and it silently reset if a master duplicated or reopened an estimate.

**`project_payment` (V93)** is a new entity that belongs to the **project**, not an estimate:
`amount` (planned), `paidAmount`/`paidAt` (actual — kept separate because a client pays 8 000 of a
planned 10 000 and that is not an error state, it is a partial payment), `dueDate` + `nextStage`,
`purpose`, `sortOrder`. Status is **derived**, never stored: `RECEIVED` (paid ≥ planned) beats
`PARTIAL` (paid > 0) beats `OVERDUE` (due date passed, nothing received) beats `PLANNED` — in that
order, so a payment that came in late still reads as progress, not as a debt (`ProjectPayment.
status(LocalDate today)` takes the day as a parameter rather than reading the clock, so it stays a
pure function under test).

**`due_date` is a condition, not a debt reminder.** The wording throughout — service, PWA, portal —
is "Оплатити до 05.09, щоб почати чистові роботи", never "ви винні". The whole point is to let a
master explain, without an awkward conversation, that work is paused because a stage isn't paid for
yet, not because he is being difficult.

### Migration: nothing lost

Every existing `estimates.deposit_amount > 0` becomes one `project_payment` row (`purpose=
'Завдаток'`, `paidAmount` = the deposit, `paidAt` = `COALESCE(signed_at, created_at)`, sorted by
estimate creation order). `Estimate.deposit_amount` stays in the schema — nothing reads it for new
math, but dropping the column is deferred to open-questions until the migration has run in
production and the totals have been eyeballed.

### Two ways to create a schedule

- **«Розбити на частки»** — the primary path, because a master thinks in shares of the total, not
  in absolute numbers. Presets (50/50, 30/40/30, 30/30/40) or a custom split; a preview shows the
  computed rows before anything is saved; the **last row absorbs the rounding remainder** so the
  sum always matches the contracted total to the kopeck, never off by a cent from `HALF_UP` on each
  share independently.
- **«+ Платіж»** — a manual row (amount / due date / purpose), which is also how a deposit already
  received gets recorded directly (`paidAmount` + `paidAt` set at creation).

Once created, the numbers are **plain, editable figures** — a split is not a live percentage that
recomputes if the estimate total changes later. If the contracted total moves after a schedule
exists, the UI offers a soft "Кошторис змінився (було X, стало Y) — перерахувати графік?" nudge; it
never rewrites the schedule silently.

`+ Додати завдаток` is gone from the estimate editor's black summary panel — money now lives
entirely in the object's Economy tab.

---

## 2. Economy tab: a panel per signed estimate, then one summary, then the PRO internals

The old economy tab was one flat card. It is now three layers, and — this is the actual product
change — **only the third layer is PRO-gated**:

1. **A panel per SIGNED estimate** (🔒, signed date, Works / Materials / Total) — DRAFT and SENT
   estimates never appear here, they live only in the Estimate tab. **All SIGNED estimates get a
   panel, regardless of the `count_in_economy` toggle** — a master looking at "what did I sign with
   this client" should see every signed sheet, even one he later excluded from the income sum; an
   honesty note explains the exclusion inline rather than making the estimate disappear.
2. **One summary panel**: contracted total (Σ counted-in-economy estimates) · received (Σ
   `paidAmount`) · remaining · the payment schedule (list + horizontal strip + vertical timeline).
   This is cash flow, not profit — visible to FREE.
3. **The PRO-gated internals**: works/materials/spentReceipts/spentManual/profit/cashBalance, the
   three economy strips. Unforeseen (MANUAL-source) expenses are temporarily behind a feature flag
   (see below).

### The gate moved from the endpoint to the field

Previously `GET /api/projects/{id}/economy` hard-403'd a FREE master before returning anything
(`featureGuard.requireFeature`). That is now wrong — panels and the payment summary are FREE value,
the internals are the paid part of the same response. `ObjectExpenseService.economy()` now always
loads the object and always computes `estimates`/`payments`; `internals` is computed only behind a
**soft** `featureGuard.isEnabled(user, Feature.OBJECT_ECONOMY)` check and is `null` for FREE. The
expense-journal CRUD (`add`/`update`/`delete`/`list`) is untouched and still hard-gates — only the
read side of `economy()` changed shape. `ObjectEconomyResponse` was restructured accordingly:
`{estimates: [...], payments: {...}, internals: {...} | null}` replaces the old flat
seven-field record.

### Unforeseen expenses — hidden, not removed

`UNFORESEEN_EXPENSES_ENABLED = false` (PWA, `ObjectEconomySection.tsx`) hides the "+ Непередбачувана
витрата" button, the `spentManual` tile, and the itemized list — data and endpoints are untouched,
`useExpenses` still fires for PRO so re-enabling is a one-line flip. This mirrors the existing
`ELECTRICAL_MEASUREMENTS_ENABLED` precedent for a built-but-paused UI. Whether it comes back, and in
what shape, is the master's call — tracked in open-questions.

---

## 3. Mobile-first visualizations — no charting library, no horizontal time axis

Three widgets, all SVG/div, all vertical on a phone:

- **Payment strip** — one horizontal bar, filled = received, "20 000 з 51 000 · 39%". Object and
  portal both show it.
- **Three economy strips** (Income / Expenses / Earnings, PRO only) — **never sent to the portal**.
- **Vertical payment timeline** — a status dot (green=received, empty=ahead, red=overdue,
  half=partial) + connector line, purpose/date/amount per row. A timeline visually, a list
  behaviorally — it doesn't break at 375px the way a horizontal Gantt-style axis would.

---

## 4. Client portal: a payments card, gated off by default

**Only when ≥ 2 estimates are shared.** For exactly one shared estimate, a new card would just
duplicate what the existing estimate section already shows — instead that section gains a small
Знижка/Надбавка (discount/markup) recap under the total, in the same small-type style as the app's
own black summary panel, computed by mirroring `EstimateEditorPage.adjustTotals()` server-side in
`PublicEstimateService.totalsOf()` (same TOTAL-percent / frozen-percent detection, same sign
bucketing) — another instance of this codebase's "mirrored formulas" rule.

**For ≥ 2 shared estimates**, a new `PaymentsCard` sits under the object card, before the estimate
list: one line per shown estimate (name · total) + contracted/received/remaining + the same payment
strip and vertical timeline the master sees.

**Gated OFF by default** — a new `payments_visible` column on `project_share_links` (V93), separate
from the existing per-estimate visibility set, surfaced in `SharePortalSheet` as its own checkbox
("Показувати платежі клієнту"). A master shares numbers with a client on purpose, not by default.

**Isolation is the load-bearing property here.** `contractedTotal` sums only the **shown** sections'
totals (never the master's full book), `received`/`payments` come straight from
`ProjectPaymentRepository` (genuinely object-level, safe in full since a payment was never tied to
one estimate). Cost, profit, `cashBalance`, `spentManual`, the three economy strips — none of it is
reachable from `PublicPortalView` even in principle, because `ObjectEconomyInternalsResponse` is a
separate type never referenced by any public DTO. `PublicEstimateIsolationTest` now loops over both
`PublicEstimateView` and `PublicPortalView`, plus an exact-allowlist test enumerating every field
`PaymentsCard` is allowed to carry (10 names, `containsExactlyInAnyOrder`).

The old per-section `depositAmount`/`balance` in `PublicPortalView.Section` are gone — money is
object-level now, and keeping a second, always-null field around would just be a second source of
truth waiting to drift. `PublicEstimateView` (the legacy single-estimate `?t=` link) keeps its
`depositAmount`/`balance` fields unchanged — those URLs were already sent and must keep showing the
frozen historical figure.

---

## 5. Receipts can be shared; a «Чеки» folder holds the ones with no estimate

`ProjectPhoto(source=RECEIPT)` could previously only ever be `PRIVATE` — the restriction is now
lifted, mirroring how progress photos already worked. Sharing a receipt puts it in the portal's
existing photo section and into the PDF's «ЧЕКИ» appendix, the same mechanism progress photos and
the estimate-bound receipt picker already use — one set of SHARED assets, not three.

The Фото tab now splits into **Фото прогресу** and **Чеки** — the latter always rendered, even when
both are empty, so the upload entry point is reachable regardless of gallery state (the first draft
of this accidentally nested it inside the empty-state branch and hid it). A receipt uploaded here
has no `estimateId` — for a master who doesn't want to itemize a receipt into an estimate's Materials
section, just prove the spend to the client.

---

## 6. Reopen is hidden, not removed

SIGNED→DRAFT `reopen` stays fully live server-side. The UI hides it in both places it appeared — the
editor's signed-view banner and the object detail page's row action menu — behind independently
declared `REOPEN_ENABLED = false` consts (same feature-flag-const pattern as the economy flag
above). A SIGNED row's ⋮ menu disappears entirely rather than opening onto an otherwise-empty menu.
The reasoning: a signed estimate is now treated as an act — the read path for "the deal changed" is
duplicating into a new estimate (already shipped, V85), not mutating the signed one. Whether reopen
comes back in some gated form is open — see open-questions.

---

## 7. Small UX

`Unit.PERCENT` already rendered as "%" (not the raw enum name) from an earlier iteration — verified,
no change needed here. Economy counting only SIGNED estimates was already the default
(`count_in_economy` auto-ON at sign, V51) — documented as intentional, not changed.

---

## Tests

- **Backend** — `PaymentServiceTest` (split-share rounding/remainder-absorption, preset purpose
  naming, custom-percent validation, offline-idempotent add/reject-foreign-id, idempotent delete,
  `summaryUnchecked` remaining clamped at zero on overpayment); `ProjectPaymentTest` (pure
  `status(today)` derivation — PLANNED/PARTIAL/OVERDUE/RECEIVED, including PARTIAL beating OVERDUE
  and a zero `paidAmount` reading as not-yet-paid); `ObjectExpenseServiceTest` rewritten for the
  nested response shape and the field-level gate (FREE sees panels+payments, no internals; PRO gets
  both); `EstimateServiceTest`/`EstimateImportServiceTest` fan-out for the removed
  `EstimateUpdateRequest.depositAmount`; `ProjectPortalServiceTest`/`PublicEstimateServiceTest` for
  the new `paymentsVisible` toggle and the portal isolation math;
  `PublicEstimateIsolationTest` extended to both public DTOs plus the `PaymentsCard` allowlist;
  `ProjectPhotoServiceTest` for RECEIPT+SHARED now succeeding.
- **PWA** — `useAddPayment`/`useUpdatePayment`/`useDeletePayment` offline-first via the same
  `offlineMutate` pattern as expenses; `usePreviewSplit`/`useCommitSplit` deliberately **not**
  offline (need the live server-computed contracted total, same rule as apply-a-template);
  `ObjectEconomySection.test.tsx` rewritten for the nested fixture (FREE sees panels + payments, not
  internals; PRO sees internals, not the hidden unforeseen-expenses note); `SharePortalSheet.test.tsx`
  for the new payments toggle; `PhotosSection.test.tsx` for the always-visible Чеки folder and the
  share-toggle on both photo sources; `offlinePrefetch.test.ts` split so economy prefetches for FREE
  now, while measurements/expenses stay PRO-only. Full suite: 84 files / 509 tests, `vite build`
  clean. Backend build — on the user (Gradle can't run in this sandbox).

## What this left open

Seven items, logged in `docs/open-questions.md`:

1. **Additional works vs. a replacement estimate** — duplicating an estimate for scope changes
   mid-job risks double-counting income; the master will decide the rule, `count_in_economy` stays
   the manual lever meanwhile.
2. **Reopen: hide-in-UI vs. remove entirely** — deferred; the endpoint stays live either way.
3. **Unforeseen expenses** — return them once the master decides on the shape.
4. **Dropping `estimates.deposit_amount`** — once the V93 migration has run in production and the
   totals have been spot-checked.
5. **A raw receipt-as-photo (no line-item parsing)** — which plan tier gates sharing it, distinct
   from `RECEIPT_IMPORT` (the PRO parsing feature).
6. **Client payment reminders** (email/portal) — not built; wanted but unscoped.
7. **Auto-recalculating a payment schedule when the contracted total changes** — today it's a manual
   "recalculate?" nudge, never automatic.
