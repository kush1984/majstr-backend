# Iteration: Economy polish + nav/discount fixes + portal/PDF polish

- **Status:** DONE
- **Prompts:** `economy-polish-prompt.md`, `economy-nav-and-discount-prompt.md`,
  `portal-pdf-polish-prompt.md` — run in that order per the user's explicit rule (both the first
  two touch the act card's discount display; polish does it "properly," nav only double-checks
  and adds navigation — reversing the order risks a duplicate or conflicting edit).
- **Version:** majstr-pwa 1.13.0 → 1.13.1 (one patch bump covering all three prompts + the small
  Кошториси-tab hint added alongside them — none of the four is a new headline capability).

## economy-polish — done

Four parts, mostly PWA + one backend gate shift:

1. **Checkbox moved.** `EstimateRow` (Кошторис tab) no longer renders "Враховувати в економіці" —
   every row there is DRAFT/SENT/REJECTED now (economy-rework iteration), so the toggle had
   nothing live to act on. It lives on the act's own ⋮ menu in `ObjectEconomySection`
   (`EstimatePanel`) instead, via the same `PATCH /count-in-economy` endpoint
   (`useToggleEstimateCounted`, new hook in `useEconomy.ts`).
2. **Discount/markup on the act card.** `AdjustLine` gained an optional `base` prop — when given,
   it derives a percent the same way `EstimateEditorPage.tsx`'s `TypeBreakdown` does
   (`amount / base × 100`, `base = works+materials−markup−discount`). Passed on the per-act card,
   omitted on the multi-estimate summary panel (a blended % across different estimates would be a
   fabricated number). Sign convention aligned with the black panel too (`+markup`, natural
   `−discount`) — this is a small, deliberate change to the amount display, not just an addition.
3. **FREE-gate widened.** Backend: `ObjectExpenseService.economy()` now nulls `payments` for FREE
   too (previously only `internals` was gated) — both computed together, both null together.
   `PaymentService`'s mutations (`add`/`update`/`delete`/`previewSplit`/`commitSplit`) now require
   `Feature.OBJECT_ECONOMY`, mirroring `ObjectExpenseService`'s gate order (plan check before
   `projectService.loadOwned`); `list`/`summary` stay ungated (existing PRO-created data survives a
   downgrade, same rule the expense journal already follows). PWA: `ObjectEconomySection` collapses
   the Σ summary panel + `PaymentsBlock` + Прибуток/Витрати into ONE `!isPro` branch behind a
   single lock teaser — FREE now sees only the acts list, nothing else.
4. **Visual differentiation.** Act cards: `border-l-4 border-l-brand-soft-2` (delicate accent, all
   acts look alike — it's a list). Σ summary panel: `bg-brand-soft border-brand-soft-2` + bolder
   title (an aggregate, should read differently). Прибуток/Витрати block: `bg-ink/5 border-ink/10
   shadow-card` (a distinct "internal kitchen" tone, not a plain white card). Payments block
   already had its orange progress strip from the economy-rework iteration — untouched.

**Type fix found along the way:** `ObjectEconomyResponse.payments` in `api/types.ts` was typed as
always-present (`PaymentsSummaryResponse`, non-nullable) — stale even before this iteration
touched the gate, since nothing enforced it matched the real (already-sometimes-absent-shaped)
backend contract until `tsc` caught it against the new nullable type. Fixed to `| null`, which also
required a null-guard in `useEconomy.ts`'s `patchPayments` (the offline-optimistic-update helper).

**Backend tests:** `PaymentServiceTest` — constructor grew two deps
(`UserRepository`/`FeatureGuard`, real `DefaultFeatureGuard` like `ObjectExpenseServiceTest`
uses); every existing mutation test gained a `user(ownerId, Plan.PRO)` stub; new tests for each
mutation rejecting FREE, plus `list`/`summary` staying reachable with no user stub at all (proof
they never call `requireEconomy`), plus a new `update()` test (mark-received) that didn't exist
before. `ObjectExpenseServiceTest` — `freeUser_economy_...` rewritten: `payments` is now asserted
null, and `paymentService.summaryUnchecked` asserted never-called for FREE (previously it was
always called). Backend build **not run** — Gradle is blocked in this sandbox, confirmation is on
the user per the standing rule.

**PWA tests:** `ObjectEconomySection.test.tsx` rewritten — `economyFixture()` now models
`payments`/`internals` as gated TOGETHER (matching the real backend); new tests for the ⋮ toggle
and the derived percent. One format surprise along the way: `formatNumber(value, 2)` doesn't
actually round to 2 decimals — its `fraction` argument only gates *whether* `number3` (fixed
`maximumFractionDigits: 3`, no minimum) is used, so a percent like 13.043478…% renders "13,043%"
via the SAME pre-existing helper `TypeBreakdown` already calls, not a bug this iteration
introduced — the test's expected string was corrected to match, no rounding code was "fixed"
(matching an existing helper's real behavior isn't in scope of a discount-display polish).

## economy-nav-and-discount — done

**A) Tab-aware back.** `ProjectDetailPage`'s active tab moved out of plain `useState` into the URL
(`useSearchParams`, `?tab=act`/`measurements`/`photos`/`notes`, absent = default Кошторис) — pulled
into a pure `resolveTab(tabParam)` helper (exported, tested standalone rather than mounting the
whole page). Switching tabs uses `{ replace: true }` so tab-picking never grows the history stack.
Both navigate-to-estimate call sites (`EstimateRow` in the Кошторис tab, `EstimatePanel` in
`ObjectEconomySection`) now append `?from=<tab>` to the estimate URL. `EstimateEditorPage` reads
that `from` param and its «← назад» button (`goBack`, plus the post-delete redirect, which shares
the same `backUrl`) returns to `/projects/{id}?tab=<from>` — also pulled into a pure
`resolveBackUrl(projectId, fromTab)` helper, same testing rationale. Because the origin tab now
lives in the object page's own URL, the browser's native back button restores it automatically too
— no separate handling needed for that path, exactly as the prompt's own recon question hoped for.

**B) Discount in the open act.** Already true — verified, nothing to add. `EstimateEditorPage`'s
`SummaryCard`/`MobileSummarySheet` (the black panel) render unconditionally for any estimate
regardless of `signed`, and both already call `TypeBreakdown`/`AdjustNote`, which show markup/
discount. `economy-polish`'s act-card recap (above) was the only genuinely new discount surface
this pair of prompts added.

**Tests:** two new pure-function test files (`ProjectDetailPage.resolveTab.test.ts`,
`EstimateEditorPage.resolveBackUrl.test.ts`) rather than mounting either page component — neither
page has ever had a dedicated test file (both are large, integration-heavy), and the actual new
LOGIC (URL param ↔ tab, project id + fromTab → back URL) is small and pure once pulled out, so it
gets real regression coverage without the cost of mounting either page's full dependency surface.

## portal-pdf-polish — done

Two presentation-only fixes on the two client-facing surfaces (portal HTML + PDF); no sums or
math touched.

**1) % in the discount/markup recap.** `PublicEstimateService.totalsOf` gained `markupPercent`/
`discountPercent` alongside the existing `markupAmount`/`discountAmount` sums, generalizing
`TypeBreakdown`'s own rule (`EstimateEditorPage.tsx`, the black summary panel) from per-type to
the portal's cross-type combined figure: a % is named only when exactly ONE live (non-frozen)
`TOTAL`-kind line explains that bucket's whole sum — several contributing lines, or a frozen
carried-over line sharing the bucket, falls back to sum-only rather than showing a fabricated
blended number. `PublicEstimateView`/`PublicPortalView.Section` both carry the two new nullable
fields; the reflection-based `PublicEstimateIsolationTest` needed no change (it blocklists
economy-leak substrings, not an exact field allowlist). `static/portal/index.html`'s
`adjustNote()` now takes the percent alongside the amount and renders «Надбавка 12% · 550,80 грн
· Знижка 5% · 229,50 грн» — percent omitted (sum-only) when the server named none; `adaptLegacy()`
carries the two new fields through for legacy `?t=` links. `EstimatePdfService` has no equivalent
markup/discount recap row at all (confirmed during recon), so the PDF side of this fix is a no-op
— nothing there to touch.

**2) «Разом по розділу» only for ≥2 items.** A one-line section repeating its own line as a
"subtotal" was noise. `EstimatePdfService.addItemsTable` and the portal's `renderItems` both gained
the same `size() >= 2` gate on top of the existing `sectioned` check — a lone-item section still
gets its category band, just not the subtotal row underneath. An estimate with no categories at
all is unaffected (that path never rendered subtotals to begin with).

**Tests:** `PublicEstimateServiceTest` — three new tests for the percent rule (single line names
it, two lines fall back to sum-only, a frozen line sharing the bucket also falls back).
`EstimatePdfServiceTest` — new test asserting «Разом по розділу» appears exactly once in the
existing 2-section fixture (Плитка has 3 items and keeps its subtotal, Підготовка has 1 and loses
it). `portal-check.mjs` (the portal's own Node-run script, no Gradle involved) — extended with an
`adjustNote` slice + sandbox and three new assertions (% shown, sum-only fallback, empty when
nothing to adjust), plus reworked the existing sections fixtures so the ≥2/single-item split is
exercised on both sides instead of accidentally passing either way. Run directly:
`node src/test/resources/portal-check.mjs src/main/resources/static/portal/index.html` — all 9
checks pass. Backend build **not run** — Gradle blocked in this sandbox, confirmation on the user.

## Extra: Кошториси-tab hint that signed estimates moved (user request mid-iteration)

The user asked, mid-session, for a small notice under the Кошториси tab explaining that signed
estimates live in Економіка now (economy-rework had already moved them out of this tab's list,
but nothing on the page said where they went). Added as a one-line `text-xs text-muted` hint,
shown only when `list.length > activeList.length` — i.e. only once the object actually has a
signed estimate for the hint to point at; a master with nothing signed yet sees nothing new.
`projects.signedMovedToEconomyHint` in both locale bundles. No dedicated test (a boolean
length-comparison JSX condition, same weight as the untested `atEstimateLimit` condition right
below it in the same component) and no live browser check this session — plain muted text with no
fixed width, same class as neighboring hints, low regression risk; flagged here explicitly per the
mobile-first rule rather than silently assumed fine.

## Not changed (confirmed)

- The portal's own payments card (`PublicPortalView.PaymentsCard`) — built independently off
  `ProjectPaymentRepository` directly, never touches `PaymentService`/`ObjectExpenseService`. A
  FREE master's `payments_visible` toggle still works exactly as before; it just has nothing to
  show now that mutations are gated (open-questions: confirmed as an accepted consequence, not a
  bug, per the prompt's own framing).
- `project_payment` schema, markup-duplicate flow, `sumIncomeCounted` — untouched.
