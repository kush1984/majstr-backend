# Iteration: Economy tab rework + money-model simplification

- **Status:** DONE (backend + PWA implemented, tests green; backend build confirmation is on the
  user per the standing constraint — Claude cannot run Gradle in this sandbox)
- **Version:** majstr-pwa 1.12.1 → 1.13.0
- **Prompt:** `C:\Work\prompts\economy-rework-prompt.md`
- **Migration:** V95 (`estimates.superseded_by_estimate_id`)

## Goal

Six changes, all "надбудова" over the payments-economy-portal work (V93/V94), no rewrite:

1. Signed estimates disappear from the "Кошторис" tab entirely and live only in "Економіка".
2. The per-estimate act panel in Економіка becomes clickable → the existing read-only estimate
   view (no new component — `EstimateEditorPage` already renders SIGNED read-only).
3. A summary panel (Σ Роботи/Матеріали/Надбавка/Знижка/Разом over *counted* signed estimates)
   sits above Платежі.
4. The PRO internals block drops the works/materials/cash split entirely:
   `Прибуток = contracted(counted) − Σ all expenses`. One number.
5. The payment dialog splits plan (no "Отримано" field) from fact ("Позначити отриманим" /
   "+ Отриманий платіж") — UI-only, `project_payment`'s schema is unchanged.
6. Signing a duplicate whose parent is still SIGNED auto-reopens the parent to DRAFT
   (`superseded_by_estimate_id`) instead of the old "negative difference" double-count workaround.

## Recon findings (see prompt's own recon-first instruction)

- `EstimateRepository.sumWorksCounted`'s duplicate-difference CASE formula goes negative for a
  discount duplicate — this is the "різниця (від'ємна)" bug named in the prompt. Deleting
  `sumWorksCounted`/`sumMaterialsCounted` as part of item 4's simplification removes the bug as a
  side effect rather than patching the SQL — the markup-duplicate case (task #48, still valid) has
  no equivalent formula on `sumIncomeCounted` (plain full-sum, used for "За договором"), so nothing
  there regresses.
- `EstimateService.duplicate()` already sets the SOURCE's `countInEconomy=false` at duplicate-creation
  time (unrelated to signing) — the supersede mechanism only needs to touch status + the new
  `superseded_by_estimate_id` marker, not `countInEconomy`.
- All 8 item/estimate-mutation methods in `EstimateService` already funnel through one
  `requireNotSigned()` guard — extended to clear `supersededByEstimateId` on any edit, one place.
- `TypeBreakdown`/`AdjustNote`/`adjustTotals` (`EstimateEditorPage.tsx`) are already exported, pure,
  and reused for the portal — the read-only act view needs no new summary component, just navigation.

## What shipped

**Backend**
- `V95__estimate_supersede.sql` — `estimates.superseded_by_estimate_id UUID REFERENCES
  estimates(id) ON DELETE SET NULL` + partial index.
- `PublicEstimateService.doSign` — after setting SIGNED, if the estimate is a duplicate
  (`duplicatedFromId != null`) and its parent is currently SIGNED, calls
  `EstimateService.applyReopen(parent, null)` (system call, no owner attribution) and stamps
  `parent.supersededByEstimateId = <this estimate's id>`.
- `EstimateService.reopen` factored into a shared `applyReopen(estimate, reopenedBy)` used by
  both the owner-facing endpoint and the system path above.
- `EstimateService.requireNotSigned` (the single guard all 8 write paths funnel through) now also
  clears `supersededByEstimateId` — any edit dismisses a stale banner automatically.
- `EstimateService.dismissSupersededNotice` + `POST /api/estimates/{id}/dismiss-superseded` — an
  explicit "I've seen it, leave both" action that touches nothing else.
- `EstimateSummary` gained `supersededByEstimateId` (no denormalized name field — the PWA already
  has the whole project's estimate list loaded, so it resolves the other side's name locally).
- `SignedEstimatePanelResponse` gained `markup`/`discount` (mirrors `PublicEstimateService
  .totalsOf`'s «% від кошторису» split); `findSignedEstimateSummaries`'s native query extended
  with two more CASE-summed columns.
- `ObjectEconomyInternalsResponse` cut to `{expenses, profit}`. `ObjectExpenseService.internalsOf`
  is now `profit = contracted − expenses` where `expenses = ObjectExpenseRepository.sumAll`
  (new query, every category/source) and `contracted` is the SAME figure the FREE payments block
  already shows (`payments.contractedTotal()`), so the two numbers can never disagree.
- Deleted `EstimateRepository.sumWorksCounted`/`sumMaterialsCounted` (dead code once internals
  stopped needing the works/materials split) — this is what actually removes the "negative
  difference" bug, not a patch to the formula.
- `ObjectExpenseService`'s `UNFORESEEN_EXPENSES_ENABLED` flag removed — the expense-add button and
  journal list are no longer hidden. The prompt's Part 4 explicitly called for un-hiding this
  ("прибрати попереднє тимчасове приховування") since MANUAL expenses (materials, and now crew
  wages logged as LABOR) are load-bearing for the new profit formula, not optional UI.

**PWA**
- `ProjectDetailPage.tsx` — `activeList` (SIGNED filtered out) drives the Кошторис tab's list,
  count header, and empty state; `list` (unfiltered) still drives the estimate limit, the
  consolidate-source picker, and the share sheet. `EstimateRow` gained a `supersededByName`
  banner (amber, dismissable) resolving the other estimate's name from the same already-loaded
  `list`, wired to the new dismiss endpoint.
- `economyNote.ts` — `economyPairHint` no longer returns a discount hint (the "different, negative"
  claim it made is no longer true); the markup hint and crew-prices hint are untouched.
- `ObjectEconomySection.tsx` — `EstimatePanel` navigates to `routes.estimate(id)` on click; new
  `EstimatesSummaryPanel` sums works/materials/markup/discount/total over `countedInEconomy`
  panels only; the PRO block is now one `Прибуток`/`Витрати` tile pair, expense-add button and
  journal list unconditionally visible.
- `PaymentsBlock.tsx` — "+ Платіж" opens a choice modal (`AddPaymentChoiceModal`): "Запланований"
  opens the existing `PaymentSheet` with its paid-amount field removed entirely (create always
  sends `paidAmount: null`; editing preserves whatever fact already exists); "Вже отримано" opens
  a new one-step `QuickReceivedSheet` (purpose+amount+date, `paidAmount = amount`). An existing
  row's edit sheet shows a status line ("—" / "Отримано X · date") with a
  "Позначити отриманим"/"Змінити" action opening a new `MarkReceivedSheet` (date + amount,
  defaults to the planned amount) that PATCHes only the fact fields while resending the untouched
  plan fields (`update()` is a full replace).

## Verification

- Backend: new/updated unit tests in `EstimateServiceTest`, `PublicEstimateServiceTest`,
  `ObjectExpenseServiceTest`; integration test `ObjectEconomyQueriesIntegrationTest` trimmed of the
  deleted-formula tests, gained `sumAllExpensesAcrossCategoriesAndSources`. **Not run by Claude**
  (sandbox blocks Gradle) — build confirmation is on the user per the standing house rule.
- PWA: full gate run and green — `npm run lint` (0 warnings), `npx tsc -b`, `npm run
  typecheck:tests`, `npx vitest run` (87 files / 532 tests, including 2 new files —
  `PaymentsBlock.test.tsx`, and a rewritten `ObjectEconomySection.test.tsx`), `npx vite build`.
- Mobile: **not visually verified in a live click-through** — no logged-in test account was
  available in this session to walk login → sign an estimate → check the Кошторис/Економіка tabs
  on a 375px viewport. Said explicitly per the mobile-first rule rather than assumed fine; worth a
  manual pass before/soon after this ships.

## Not changed (confirmed)

- `project_payment` schema — untouched, per the prompt's own note.
- Markup-duplicate ("бригадир") flow, `sumIncomeCounted` — untouched.
- Portal/PDF isolation — internals never flowed there; unaffected.

## Behavior change worth flagging

The old `sumWorksCounted`-based profit ignored materials and computed a duplicate's margin via a
per-line difference formula. The new `Прибуток = contracted − Σ expenses` is simpler but means a
master who duplicates-with-markup (бригадир flow) and does NOT log what he pays the crew as a
LABOR expense will now see `Прибуток` at the FULL client price, not just his margin — the old
formula derived the margin automatically from the duplicate's `source_unit_price`; the new one
needs it entered as an expense. Not flagged as an open question because the prompt's Part 4 was
explicit about this trade-off ("що майстер платить — логується як витрата"), but worth knowing if
a бригадир master's profit number looks too high right after this ships.
