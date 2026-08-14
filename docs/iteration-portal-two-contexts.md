# Iteration — portal: two genuinely separate contexts (signature / economy) + compact mobile payments

PWA `1.15.8 → 1.16.4`. Backend: **V102**.

The client portal was one page serving two different intents through ad-hoc filters
(`estimatesFilter?: 'signed' | 'unsigned'` on one shared sheet, one shared link). This iteration
makes the split real: two separate share-link kinds, two separate public endpoints, two separate
owner-side publish flows — SIGNATURE (Кошторис tab, any-status estimates, for signing, never
payments) and ECONOMY (Економіка tab, SIGNED acts only, read-only, optional payments card). Plus a
new Зведення (summary) panel on both, and a compact mobile redesign of the payments card that adds
the dates it was missing.

---

## 1. A third `ShareLinkKind` and a second visibility flag

**V102** adds `ECONOMY` to the existing `ShareLinkKind` enum (`PORTAL`, `MESSAGE`, now `ECONOMY`)
and a second, independent boolean on `Estimate`: `economy_visible`, alongside the existing
`portal_visible`. Deliberately **two flags, not one repurposed** — "should this estimate be shown
for signature" and "should this act appear in the money summary" are independent questions (an
estimate can be portal-visible while still a DRAFT; an act only ever becomes economy-visible once
SIGNED). The migration backfills `economy_visible = true` for every estimate that was already
`portal_visible = true` AND `status = 'SIGNED'` — the closest approximation of "what a master had
already chosen to show a signed act for" under the old single-flag model.

Both flags read from the same `project_share_links` table, keyed by `(project_id, kind)` — one
PORTAL link and one ECONOMY link can coexist per project, minted/reused idempotently the same way
the existing PORTAL/MESSAGE pair already worked. `payments_visible` (a column on the link) is now
semantically ECONOMY-only by code discipline, not a DB constraint — nothing ever reads or writes it
for a PORTAL-kind link again.

## 2. Backend: `ProjectPortalService` splits into two parallel APIs

`ProjectPortalService` now exposes two independent pairs of methods sharing private helpers
(`stateOf`, `applyVisibility`, `mintOrReuse`, `buildUrl` — branches `?p=` vs `?e=`):

- `state` / `update` / `sendEmail` — SIGNATURE. `update(projectId, estimateIds, ownerId)` dropped
  the `paymentsVisible` parameter entirely (`PortalUpdateRequest` is now just `{estimateIds}`) —
  there is nothing to toggle on a link that never has a payments card.
- `economyState` / `updateEconomy` / `sendEconomyEmail` — ECONOMY. `updateEconomy(projectId,
  estimateIds, paymentsVisible, ownerId)` validates every id is already `SIGNED`, throwing
  `InvalidEstimateStatusException("error.estimate.not-signed-economy")` otherwise — the ECONOMY
  portal is a settled-money view, not a second place to sign something.

`ProjectPortalController` grew the mirror set of owner-side endpoints (`GET`/`PUT
/api/projects/{id}/portal/economy`, `POST .../economy/send-email`) alongside the unchanged
SIGNATURE ones.

## 3. `PublicEstimateService`: `viewPortal` never has payments; `viewEconomyPortal` is new

`viewPortal` (SIGNATURE) now **always** passes `payments: null` — it no longer reads
`link.isPaymentsVisible()` at all, so a stale/legacy `true` value on a PORTAL link (or any future
mistake) can never leak a payments card into the signing portal. `resolveLink`/`resolveProject` are
now parametrized by `ShareLinkKind` rather than hardcoded to `PORTAL`, since three kinds
(`PORTAL`/`MESSAGE`/`ECONOMY`) now share the token-resolution shape.

`viewEconomyPortal` (new) resolves the ECONOMY-kind link, filters
`findByProjectIdAndEconomyVisibleTrueOrderByCreatedAtAsc` and then **additionally filters to
`status == SIGNED`** — defense-in-depth against a flag that can outlive its status: auto-reopen on
a superseding duplicate (`doSign`, economy-rework iteration) flips a parent's status back to
`DRAFT` without clearing `economyVisible`, so the read-time filter is what actually keeps a
reopened draft off the settled-money page. The same double-check gates the per-estimate
`resolveEconomyEstimate` used by `askEconomyQuestion`/`renderEconomyPdf`. No sign endpoint exists
for ECONOMY at all — acts shown there are already immutable SIGNED records.

`PublicPortalView` gained a `mode` enum component (`SIGNATURE`/`ECONOMY`) — self-descriptive even
though the static page already knows its own mode from which query param it read.

**New public controller**: `PublicEconomyPortalController` (`/api/public/economy/{token}` — view,
question, pdf, photo; no sign) mirrors `PublicPortalController` minus the sign endpoint.

## 4. Payments card: last-receipt date, and an efficiency fix along the way

`PaymentRow` gained `lastReceivedAt` — the date of the most recent `payment_receipt` against that
stage, used by the compact card (below) for "отримано {date}". Rather than a new repository query,
`buildPaymentsCard` reuses the existing `findByProjectIdOrderByReceivedAtAscCreatedAtAsc` receipts
list (already fetched for the unplanned-receipts list) and groups it in memory by plan-stage id —
the same shape `PaymentService.buildSummary` already uses. Since the list arrives oldest-first, the
last entry per group is the most recent receipt "for free". This also **removed an N+1**: the old
code called `paymentReceiptRepository.sumByPlanPaymentId(p.getId())` once per plan row; the now-dead
`sumByProjectId`/per-row `sumByPlanPaymentId` calls in this path are gone (the latter repository
method is still used elsewhere, for overpayment resolution — only its use *here* was redundant).

## 5. PWA: `SharePortalSheet` becomes genuinely mode-aware

The prior `estimatesFilter?: 'signed' | 'unsigned'` prop (a display-only filter over one shared
API) is replaced with a required `mode: 'portal' | 'economy'` prop that drives which **API module**
the sheet calls — `portalApi` (3-arg `update`, no payments) vs the new `economyPortalApi` (4-arg
`update` with `paymentsVisible`, its own `state`/`sendEmail`). `ProjectDetailPage` wires
`mode={tab === 'act' ? 'economy' : 'portal'}`; `EstimateEditorPage` wires `mode={signed ? 'economy'
: 'portal'}` (the editor's own share button can open on a read-only SIGNED act reached via the
Економіка tab's click-through, not only a live DRAFT/SENT one). Both modes filter their picker list
by status (`economy` → SIGNED only, `portal` → everything else) — the same filtering the old
`estimatesFilter` did, just now a direct consequence of which link is being published rather than a
separate flag threaded alongside it. The payments-visibility checkbox only renders for `mode ===
'economy'`.

## 6. Зведення (summary) panel — top of both portal pages

A new `renderSummary()` in `static/portal/index.html`, called right after the Об'єкт card on both
SIGNATURE and ECONOMY pages: one line per shown estimate (name · Роботи · Матеріали · Разом, plus
the same small Знижка/Надбавка recap the per-estimate section already carries) and a grand total
("Разом за всіма"). Built entirely from fields the `Section` DTO already exposes — no backend
change needed for this part.

## 7. Compact mobile payments — dates, at last

The payments card (ECONOMY only, since SIGNATURE never has one) was rewritten for a tight ≤375px
layout:

- Dropped the old three-line `Разом за договором` / `Отримано` / `Залишок` totals block — replaced
  by a single compact header, **"Отримано X з Y ₴ · Z%"**, plus the existing progress bar and the
  same curated (i) info-popover.
- Each `.payment-row` (status dot + purpose left, amount right) gets a small gray date/condition
  line underneath: **received** → "отримано {date}" (from the new `lastReceivedAt`); **planned**
  with a due date → "до {date} — {next stage}" (still framed as a condition to unblock the next
  stage, never a debt — same rule the original schedule already followed, just reworded to fit the
  new one-line shape).

## 8. Adjacent fix: raw `PERCENT` unit in the portal's items table

While already inside `static/portal/index.html`, fixed the long-standing gap flagged in
open-questions: `UNIT_LABEL` had no `PERCENT` entry, so a PERCENT-unit line's unit cell rendered
the literal enum text instead of "%". One-line fix (`PERCENT: '%'`). The PDF's own items table
(`EstimatePdfService.addItemsTable`) has the same gap and was **not** touched — out of scope here,
left open.

## 9. A bug caught during self-review, not by a test

`load()`'s final dispatch — `render(portalToken ? data : adaptLegacy(data))` — only ever checked
`portalToken`. Left as-is, an ECONOMY response would have been silently routed through
`adaptLegacy`, which expects the legacy single-estimate shape (`data.status`/`data.items`) rather
than the portal shape (`data.estimates`) — the ECONOMY page would have rendered garbage or crashed
on first load. Fixed to `(portalToken || economyToken) ? data : adaptLegacy(data)` before it ever
shipped. No automated test covers this file (it has no build step / no PWA-style test harness); a
Node smoke test against the extracted rendering functions (Зведення panel, payments header, the two
date-condition wordings) confirmed the new logic separately — see the session notes for the
throwaway script, not checked into the repo.

---

## Isolation

`PublicEstimateIsolationTest` already loops over both `PublicEstimateView` and `PublicPortalView` —
since `PublicPortalView` is now shared by both modes (`mode` is the only new discriminator field),
the existing reflection-based checks (`publicViewsCarryNoEconomyData`,
`publicViewsCarryNoObjectNoteType`) cover ECONOMY for free. `publicPortalPaymentsCardCarriesNoPrivateAggregates`'s
exact-allowlist was extended for `lastReceivedAt`. New behavioral tests in `PublicEstimateServiceTest`
pin the two isolation properties that matter concretely: `viewPortal` never builds a payments card
even if the underlying link's `paymentsVisible` is (incorrectly) `true`; `viewEconomyPortal` excludes
an act whose `economyVisible` flag outlived a SIGNED→DRAFT auto-reopen.

## Tests

- **Backend** — `ProjectPortalServiceTest` rewritten for the split (3-arg `update`, new
  `updateEconomy`/`economyState`/`sendEconomyEmail`, the not-signed rejection);
  `PublicEstimateServiceTest` extended with a parallel `viewEconomyPortal`/`askEconomyQuestion` test
  block (SIGNED-only filtering, defense-in-depth exclusion, payments-card isolation, unplanned
  receipts) and a new regression pinning that `viewPortal` never builds a payments card;
  `PublicEstimateIsolationTest` allowlist extended for `lastReceivedAt`.
- **PWA** — `SharePortalSheet.test.tsx` rewritten around `mode` instead of `estimatesFilter`: 12
  tests split across a `mode: 'portal'` block (no payments toggle, SIGNATURE endpoint only) and a
  `mode: 'economy'` block (payments toggle present and wired, ECONOMY endpoint only, never calls
  the other one). Full PWA gate green: lint (`--max-warnings 0`), `tsc -b`, `typecheck:tests`,
  vitest (12/12 in the touched suite), confirmed clean.
- **Backend build** — on the user; Gradle cannot run in this sandbox (loopback socket blocked).
- **Mobile click-through** — not performed live (no dev server / test account in this session); the
  compact payments layout was reasoned through against the existing `@media (max-width: 480px)`
  rules already in the stylesheet (unchanged), and verified functionally via the Node smoke script
  against real sample data rather than a rendered browser.

## Follow-up (live testing, same session, PWA 1.16.1)

Four real bugs surfaced testing the shipped feature against a live object with a real signed act:

1. **A SIGNED estimate kept appearing on the SIGNATURE portal.** `portalVisible` is never cleared
   by signing — an estimate shared from Кошторис, then later signed, kept its stale `true` flag
   forever. The first fix attempt filtered `status != SIGNED` directly in `viewPortal`/
   `resolvePortalEstimate` — **reverted**: `signPortal` returns that exact view immediately after
   signing so the client sees their own estimate render with its «✓ Підписано» confirmation banner
   (pre-existing behavior), and a status filter there made it vanish from its own confirmation
   response instead; the same filter on `resolvePortalEstimate` also blocked asking a question
   about, or downloading the PDF of, an estimate right after signing it. Caught by
   `signPortal_signsAVisibleEstimateOfTheTokensProject` failing with an `ArrayIndexOutOfBounds` —
   reverted before it shipped. **The actual fix stays write-side only**:
   `ProjectPortalService.state`/`update` mask a SIGNED estimate as never portal-visible
   (`isSignaturePortalVisible`) — this alone breaks the resurrection loop, since the picker's
   `selected` set was seeding itself from the raw (unmasked) flag, silently re-including the stale
   id on every publish (it wasn't even rendered as a checkbox — the PWA already filtered SIGNED out
   of the visible list, just not out of the *ticked* set). `update` also coerces
   `portalVisible=false` for any SIGNED id regardless of what was requested, so the stored flag
   self-heals the moment the master publishes anything — which is exactly the workflow the report
   described (open one specific estimate, share its link — `onCopy` always publishes before
   copying). No read-side filter, no migration needed.
2. **Зведення showed «Матеріали: X» and «Разом: X» as the identical figure.** `worksSubtotal`/
   `materialsSubtotal` are net — a TOTAL-kind (or frozen) PERCENT line is baked into its own type's
   bucket by the same raw item-type sum the app itself uses (`useEstimate.ts`'s `sum('MATERIAL')`).
   The app's own `TypeBreakdown`, though, backs that adjustment back OUT before display, to show a
   pre-adjustment "base" next to a small Знижка/Надбавка sub-line — the portal never did this, so
   "Матеріали" silently meant something different there than in the app, and the discrepancy became
   obvious once Зведення put Матеріали and Разом on adjacent, near-identical lines. Fixed the actual
   root, not just the panel: `PublicEstimateItemView` gained `percentBaseKind`/`baseOriginLabel`
   (additive, needed so the page can tell which lines to back out); a new `typeBase(items, type,
   subtotal)` in `static/portal/index.html` mirrors `TypeBreakdown`'s math exactly and now drives
   both Зведення's per-estimate row AND the existing per-estimate "Сума робіт"/"Сума матеріалів"
   block (same bug, same fix, one place — that block was never panel-specific, it just wasn't
   noticed until Зведення made the identical-looking numbers impossible to miss).
3. **The ECONOMY payments card only showed as a standalone top card for ≥2 shown acts** — for
   exactly one act it got folded into that estimate's own card, at the bottom of the page, buried
   under the full item tables. That rule predates the two-context split (from when the portal was
   one page and payments were a bonus feature bolted onto whichever estimate happened to be
   showing). Removed entirely: payments now always render standalone, right after Зведення, on
   ECONOMY — `renderSection` dropped its `paymentsCard` parameter altogether.
4. Fixed together since (2) and (3) touch the same render path: no separate migration for either.

**Tests**: `viewPortal_stillRendersASignedEstimate_soTheJustSignedConfirmationBannerCanShow`
(regression guard locking in the revert — viewPortal must stay status-blind),
`view_exposesPercentBaseKindOnlyForPercentLines_forTheClientPageToBackTheAdjustmentOut` (both
`PublicEstimateServiceTest`); `update_coercesASignedEstimateToNeverBePortalVisible_evenIfRequested`,
`state_masksASignedEstimateAsNeverVisible_evenIfTheStoredFlagIsStillTrue` (both
`ProjectPortalServiceTest`). The `typeBase` fix was verified with the same throwaway Node
smoke-script approach as the original iteration (no build step / test harness for the static HTML
file) against the exact numbers from the live report (3397.75 gross / −509.66 discount / 2888.09
net) — confirmed matching the app's own black-panel figures exactly.

## Follow-up 2 (live testing round 2, same session, PWA 1.16.2)

A dense round of polish requests off a second live look, plus one real correctness bug caught
mid-fix (`signPortal` regression — see below). All confined to `static/portal/index.html` and two
small PWA files; no backend change.

1. **Зведення visual hierarchy.** The Роботи/Матеріали/Разом line and the Знижка/Надбавка note
   under it read at nearly the same weight (0.85rem vs 0.82rem) — shrunk the figures line to
   0.82rem and the adjust note to 0.72rem, so the note reads as a supporting explanation, not a
   peer figure. The estimate name itself now reuses the SAME `.estimate-title` dark band the
   per-estimate sections already use (full-width, flush with the card) instead of a plain bold
   line — one visual language for "this is an estimate's name" everywhere on the page.
2. **Unnamed-estimate naming was inconsistent in THREE places.** The portal fell back to
   `'Кошторис ' + (index+1)` ("Кошторис 2"); `ObjectEconomySection` (PWA) fell back to a bespoke
   "Кошторис без назви"; only the Кошторис-tab list used the real convention, `estimateName()`'s
   "Кошторис від {date}". Unified all three to the same date-based fallback: portal gained
   `defaultEstimateName()` (uses the `createdAt` the `Section`/summary DTOs already carry, a new
   `nameDateFormat` — day + month, no year, matching the PWA's own `formatDate`); `ObjectEconomySection`
   now calls the SAME `estimateName()` helper the rest of the app uses (using `signedAt` as the date
   source — the panel has no `createdAt` field, and nothing else displays a signed estimate's
   createdAt to compare against, so no visible inconsistency). Dropped the now-dead
   `economy.unnamedEstimate` i18n key (uk+en).
3. **Removed the (i) info-popover from the portal entirely** — the master reported it doing
   "something strange" on tap. Rather than debug a hand-rolled popover with no shared component to
   fall back on, cut it: the button, its CSS (`.info-trigger`/`.info-scrim`/`.info-panel`/
   `.info-panel-close`), and its JS (`openInfoPopover`/`closeInfoPopover`/the two delegated
   listeners) are all gone. The payments header text alone ("Отримано X з Y ₴ · Z%") already carries
   the same information the popover repeated.
4. **"Разом за всіма:" → "Разом:"** in the Зведення grand-total row — shorter, and the "за всіма"
   was redundant once the panel already lists what it's summing.
5. **Зведення hidden entirely for a single estimate** — with one section below it, the panel just
   repeated that section's own totals with nothing to summarize across.
6. **Compact payment row amount**: "5 000,00 грн з 5 000,00 грн" → "5 000,00/5 000,00" once
   anything has been received (currency and «з» are already established by the header above and
   the row's own context) — the fully-nothing-received case keeps `money()` (with ₴) since it's the
   only figure on that row.
7. **`SharePortalSheet` preselect default.** Previously the ticked set was seeded ONLY from what's
   already published — opening the picker for the first time on a brand-new estimate showed
   everything unchecked, an extra tap before the master could even copy a link. Now: nothing
   published yet → default to the sole pickable estimate if there's exactly one, or the most
   recently created one if there are several (the rest stay one tap away, never force-added).
   Preselect from the editor's own share button still wins, same as before.

**A real bug caught mid-fix, not shipped**: the first pass at fix #1 above (SIGNED estimates
resurrecting on the SIGNATURE portal) filtered `status != SIGNED` directly in `viewPortal`/
`resolvePortalEstimate`. Reverted before it reached the user — `signPortal` returns that exact
view immediately after signing, and the status filter made the just-signed estimate vanish from
its own «✓ Підписано» confirmation instead of rendering it. Caught by the pre-existing
`signPortal_signsAVisibleEstimateOfTheTokensProject` test failing with an
`ArrayIndexOutOfBoundsException`. The surviving fix is write-side only —
`ProjectPortalService.state`/`update` mask a SIGNED estimate as never portal-visible, which is
what actually breaks the resurrection loop (the picker's ticked-set was seeding itself from the
raw flag) without touching the read path signing itself depends on.

**Tests**: `SharePortalSheet.test.tsx` — two new tests for the preselect default (sole pickable
estimate; most-recently-created among several). No portal HTML test harness exists (established
pattern for this file); verified with the same throwaway Node smoke-script approach as both
earlier rounds — fallback naming, compact amount text (all three branches), and the
hide-when-single-estimate guard, all against literal expected strings. Full PWA gate green: lint,
`tsc -b`, `typecheck:tests`, full vitest suite (93 files / 588 tests).

## Follow-up 3 (portal-payments-compact prompt, same session, PWA 1.16.3)

A separate attached prompt (`portal-payments-compact-prompt.md`) asked for a full compact "2+1"
redesign of the payments block, on **both** surfaces with one shared design — not another polish
pass on the round-2 layout, a different layout entirely.

**Recon (reported before coding, per the prompt's own requirement)**: the portal
(`static/portal/index.html`, vanilla JS, no build step) and the PWA's `PaymentsBlock.tsx` (full
React CRUD component with its own sheets) are genuinely separate implementations with zero shared
code — converging them meant reimplementing the same list logic twice, not extracting a shared
component (no shared JS/TS module crosses the repo boundary here).

**A real conflict, resolved by asking**: the prompt says to *keep* the existing (i)-popup, but
Follow-up 2 (same session) had the master explicitly ask to remove it after reporting "something
strange" happening on tap — and it had been fully removed, CSS and JS. Asked the user directly;
their answer (not one of the offered presets) was to rebuild it correctly rather than restore the
old code, **and** fix what it explained: the old text talked about "the next payment and its due
date," but the (i) sits next to the header's aggregate line (received vs. contracted total), so
the text was explaining the wrong thing. Rebuilt from scratch — position computed once at open
time, closes on scroll instead of trying to continuously re-track the trigger (the old version had
no scroll handling at all, the likely root cause of the original "strange behavior" report) — with
corrected copy: portal `"Скільки клієнт уже реально сплатив із суми за договором."`; PWA reuses
the already-established `economy.receivedInfo` string verbatim (same meaning, same wording, one
source of truth).

**Design, both surfaces**: header is always `"ПЛАТЕЖІ"` + `"Отримано X з Y ₴ · Z%"` + the (i) + a
thin progress bar. ≤5 payments total → "2+1": fully-RECEIVED stages **and** every unplanned
receipt (both just mean "money already landed") collapse into one green `"✓ Отримано · N
платежів"` row, tap expands them individually; open stages (PARTIAL/PLANNED/OVERDUE) render one
line each, sorted by due-date condition, a partial one showing `"received/plan"`. Nothing open at
all → `"Усе сплачено ✓"`, no list. >5 total → the list is replaced by one highlighted "Наступний
платіж" card (nearest unpaid by due date) plus an `"Усі платежі (N)"` expand toggle that reveals
the same "2+1" view; expand state persists in `localStorage`, keyed per share-link on the portal
(`majstr-portal-payments-expanded:<token>`) and per-object in the PWA
(`majstr.payments-expanded.<objectId>`) — never global, so one client's preference on one object
can't leak onto another. Dates compact everywhere (`"11 серп"`, no year/`"р."`); a due-date
condition reads `"до 19 серп — почати {next stage}"` — caught and fixed mid-implementation against
the prompt file's literal wording (a first draft omitted "почати"). Currency shown once (the
header); row amounts are bare numbers via a new `formatAmount()` (PWA) / already-existing `amount()`
(portal) — except a lone not-yet-received figure, which keeps its own currency since nothing
nearby establishes it.

**PWA-specific**: the old 3-tile header (За договором / Отримано / Залишок, each with its own
`InfoPopover`) is gone, replaced by the same one-line header the portal uses. `economy.remaining`,
`economy.remainingInfo`, `economy.contractedInfo` became dead and were deleted (uk+en);
`economy.received`/`receivedInfo` stayed (now doing double duty — the tile label before, the one
shared (i) text now) and five new keys were added
(`paymentsAllDone`/`paymentsReceivedCount_{one,few,many}`/`paymentsNextLabel`/`paymentsExpandAll`/
`paymentsCollapseAll`). The always-visible per-row "+ Отримати платіж" quick-pay button was
dropped — tapping a row still opens its edit sheet, which already has its own "Отримати платіж"
button, so no capability was lost, just one more control per row than the compact spec allows.

The model, the derived status function, and the contracted/received/remaining figures themselves
were not touched anywhere — this was a pure view change on both surfaces, no migration.

**Tests**: `PaymentsBlock.test.tsx` — two existing tests assumed a receipt breakdown / an unplanned
receipt rendered directly inline, which the new design no longer does (both are now behind the
collapsed received group); rewritten to expand the group first. Two new tests added: the
all-done terminal state, and the >5-payment next-card-plus-expand path. `ObjectEconomySection.test.tsx`
— three tests broke because their one-payment fixture was fully `RECEIVED`, which now renders
`"Усе сплачено ✓"` instead of the payment's own name (these tests are about FREE/PRO gating around
the block, not its internal collapse behavior) — fixture changed to `PARTIAL` so the row still
renders directly. `static/portal/index.html` still has no build step or test harness (established
pattern for this file); verified the same way as every prior round in this doc — a throwaway Node
script evaluating the actual shipped functions (`groupPayments`, `paymentsWord` pluralization, the
2+1/>5 threshold branches, the due-date wording) against literal expected output, not checked in.
Full PWA gate green: lint (`--max-warnings 0`), `tsc -b`, `typecheck:tests`, vitest (93 files / 590
tests), `vite build`. Backend build — on the user, unaffected regardless (no backend changes this
round).

## Follow-up 4 (live testing of the compact redesign, same session, PWA 1.16.4)

Four more real bugs surfaced testing the Follow-up 3 redesign against live data — three UI defects
plus a genuine backend correctness bug that predates this session but only became visible once the
Економіка tab's per-estimate panel was actually looked at closely.

1. **Estimate names silently invisible on the portal.** `p.estimate-title` (white text on a dark
   band) lost to the earlier `.card p { color: var(--ink) }` rule whenever the title sat inside a
   `.card` — the Зведення summary's per-row title, specifically — because `.card p` (one class +
   one type selector) out-specifies a bare `.estimate-title` (one class alone). Dark text on an
   identically dark background reads as nothing rendering at all. Diagnosed by inspecting computed
   styles on the user's own live page (`color` and `background-color` were the exact same RGB
   triple) rather than guessing from a screenshot. Fixed by bumping the selector to
   `p.estimate-title` — same specificity as `.card p`, wins the tie-break by appearing later in the
   file, no `!important` needed.
2. **Collapsed-payments chevron sat flush against the card's right edge** (0px gap, measured) —
   read as clipping. Added right padding to `.payment-collapsed`.
3. **Redundant green dot on the collapsed "✓ Отримано" row** — the row already leads with "✓" in
   its own text; the separate colored dot next to it was competing icon language for the same
   meaning. Removed just that one dot (individual expanded rows keep theirs — there the dot is the
   only status signal).
4. **A payment total nobody could explain.** Reported live: a payments card reading "Отримано 2 000
   ₴ з 0 ₴" with the list rendering "Усе сплачено ✓" and nothing else — no way to see where the
   2 000 came from. Traced through `PaymentService.buildSummary`: `contractedTotal` is genuinely
   `estimateRepository.sumIncomeCounted(objectId)` (the object's one estimate really did total 0
   ₴), and the 2 000 is a real `PaymentReceipt` row — `totalReceived` sums every receipt on the
   project regardless of whether its plan stage still exists, and a receipt survives its plan stage
   being deleted **by design** (`plan_payment_id ON DELETE SET NULL`, already true before this
   session). Not a data bug. The actual bug: the "Усе сплачено ✓" terminal state (both surfaces)
   replaced the itemized list entirely instead of pairing with it, so a master had no way to
   inspect what actually made up "Отримано" once nothing was left upcoming. Fixed on both surfaces
   — "Усе сплачено ✓" is now always followed by the same collapsible received-group row the ≤5 case
   already uses, tap-to-expand, never a bare unverifiable claim.

**A real, separate backend bug caught in the same pass** (not from this session's redesign —
pre-existing, just newly visible once the panel was inspected closely): `EstimateRepository
.findSignedEstimateSummaries`'s `works`/`materials` columns summed the RAW per-type `line_total`,
which — same root cause as the portal's Зведення bug fixed in the first Follow-up round — already
had a TOTAL-kind (or frozen) PERCENT discount/markup line folded in. So a signed act with a 15%
discount against works showed "Роботи: 22 100" on the Економіка tab (the net, post-discount figure,
identical to "Разом", with nothing for the Знижка recap underneath to visibly explain) instead of
"Роботи: 26 000" the way the same estimate's own detail view correctly shows it. Fixed at the SQL
level: `works`/`materials` now exclude the same TOTAL/frozen percent lines that markup/discount
already aggregate separately (mirrors `TypeBreakdown`/`typeBase()`'s math exactly), and
`ObjectExpenseService.signedEstimatePanels` reconstitutes the real total as
`works + materials + markup + discount` instead of the old `works + materials`. PWA's `AdjustLine`
`base` prop simplified to match (`works + materials` directly, since both are gross now — no more
subtracting markup/discount back out a second time).

**A fifth item, from a follow-up message on this same round**: the "hide Зведення for exactly one
estimate" and "hide the per-estimate title band unless there's something to disambiguate" decisions
from Follow-up 2 were **explicitly reversed** — Зведення and the estimate name band are now always
shown, for any estimate count (including exactly one, including an unnamed one). In exchange, the
per-estimate section's own duplicate totals block (Сума робіт / Сума матеріалів / РАЗОМ + the
Знижка/Надбавка recap, sitting between the item tables and «Умови») was removed outright — Зведення
now covers every estimate's totals unconditionally, so that block was pure duplication the moment
it stopped being conditional. `renderSection` dropped its now-always-true `showTitle` parameter
entirely; the sign/question dialog titles simplified the same way (always "Підписати: {title}",
never the old generic fallback).

**Tests**: no test harness exists for `static/portal/index.html` (established pattern — verified via
a throwaway Node script reading the shipped functions directly: computed-style-equivalent checks
aren't possible outside a real browser, so this round's UI fixes were confirmed by loading the
user's own live page in a headless browser session and inspecting `getComputedStyle`/
`getBoundingClientRect` directly, then re-verified against source after each fix). PWA:
`PaymentsBlock.test.tsx`'s all-done test extended to assert the breakdown is reachable (tap-to-expand
still surfaces the receipt); `ObjectEconomySection.test.tsx`'s discount-recap test's expected percent
corrected from ~13.043% (an artifact of the old net-works formula) to the mathematically correct 15%.
New backend integration test `findSignedEstimateSummaries_worksAndMaterialsAreGross_notNetOfTheDiscount`
in `ObjectEconomyQueriesIntegrationTest` (Testcontainers, since this is a native SQL query no Mockito
test can reach) pins the gross-works fix with real Postgres. Full PWA gate green: lint, `tsc -b`,
`typecheck:tests`, 590/590 vitest, `vite build`. Backend build — on the user; this round's Java
change (the works/materials SQL fix) has not yet been compiled/deployed by the time of writing —
the user's live server was still serving pre-Follow-up-3 static assets partway through this round,
confirmed by reproducing against it directly and seeing stale output.

## What this left open

Logged in `docs/open-questions.md`:

1. **Portal payments card PRO-only?** — re-scoped, not re-decided: the question now applies
   unambiguously to the ECONOMY portal (the only place a payments card can appear at all). Still OPEN.
2. **Should the client be able to sign from ECONOMY?** — new item, decided **no** for this
   iteration (keep it strictly read-only); revisit only if masters actually ask.
3. **Raw PERCENT unit in the PDF items table** — the portal half is fixed; `EstimatePdfService`'s
   own items table still shows the raw unit code. Still IN_PROGRESS.
