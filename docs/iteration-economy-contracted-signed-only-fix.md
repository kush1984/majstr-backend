# Iteration: Fix — «За договором» counted unsigned estimates

- **Status:** DONE
- **Prompt:** `economy-contracted-signed-only-fix-prompt.md` — prod bug, found by the user: an
  object with only DRAFT/SENT estimates (none SIGNED) showed a nonzero "За договором" in
  Платежі while the act panels above it — correctly — showed nothing.
- **Version:** majstr-pwa 1.13.2 → 1.13.3 (patch — bug fix, no new capability).

## Root cause

`EstimateRepository.sumIncomeCounted` — the source of "contracted" in `PaymentService.summary` /
`previewSplit` / `commitSplit` — filtered `count_in_economy = true AND status <> 'REJECTED'`, with
no `status = 'SIGNED'` check. `Estimate.countInEconomy` defaults `true` even on a fresh DRAFT (it
means "counts once it becomes the deal," not "is the deal"), so a flagged DRAFT/SENT estimate's
line total leaked into contracted. The act panels (`findSignedEstimateSummaries`) were already
`status = 'SIGNED'`-only — money and panels disagreed.

## Fix

- `sumIncomeCounted`: added `AND e.status = 'SIGNED'` to the native-query `WHERE` clause. All
  three `PaymentService` call sites (`summaryUnchecked`, `previewSplit`, `commitSplit`) share this
  one method, so the single query fix covers all of them — no service-layer change needed.
- `sumDepositsCounted` — same missing filter, but **zero callers** anywhere in `src/main` (grepped).
  Fixed for consistency (a JPQL fully-qualified enum literal, `e.status =
  com.majstr.backend.entity.EstimateStatus.SIGNED`, since it's not a native query) and the javadoc
  now says plainly that it's dead code, so whoever revives it doesn't reintroduce the same bug.
- **PWA UX addendum (from the prompt's own "ДОДАТКОВО"):** with contracted now correctly `0` for
  an object with nothing signed yet, `ObjectEconomySection`'s Платежі block would otherwise show a
  bare 0/0/0 card — noise, not information. It now renders a neutral empty state ("Ще немає
  підписаних кошторисів") instead, **unless** the master already logged payment rows by hand
  (`eco.payments.payments.length > 0`), which still render as-is per the prompt.

## Tests

- **Integration** (`ObjectEconomyQueriesIntegrationTest`, real Postgres via Testcontainers — the
  only way to reach a `nativeQuery = true` method): new
  `contractedIgnoresUnsignedEstimatesEvenWhenFlagged` — DRAFT(40)+SENT(19725), both flagged →
  contracted 0; sign a third (1200) → contracted 1200. `unflaggedEstimatesAreExcludedToo…` updated
  to use two SIGNED estimates (one flagged, one not) instead of SENT+DRAFT, since it was
  incidentally relying on the pre-fix (buggy) behavior to isolate what the flag itself excludes.
- **PWA** (`ObjectEconomySection.test.tsx`): two new tests — no acts + no payments → empty state,
  not the summary/payments cards; no acts but a manually-created payment → shown as-is. Full PWA
  gate green (lint, `tsc -b`, `typecheck:tests`, vitest) — 542/542.
- Backend build **not run** — Gradle blocked in this sandbox; confirmation is on the user as usual.

## Not changed (confirmed)

- Act panels (`findSignedEstimateSummaries`) — already correct, untouched.
- «Загалом по підписаних» (`EstimatesSummaryPanel`) — sums `SignedEstimatePanelResponse` rows,
  unrelated to `sumIncomeCounted`, untouched.
- `received` (Σ `project_payment.paid`) — untouched.
- «Розбити на частки» — now correctly splits the SIGNED-only sum, since it reads `contracted` from
  the same fixed query; no code change needed there beyond the query fix itself.
- Portal/PDF isolation — this fix is entirely owner-side (`PaymentService`/PWA economy tab).
