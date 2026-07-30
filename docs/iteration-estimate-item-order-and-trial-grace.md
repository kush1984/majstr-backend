# Iteration: explicit line order across categories + the PRO-trial grace fix

> **Retrospective doc.** Written during the 2026-07-27 catch-up from `905eff8`, `7c7aed9`
> (ordering) and `a8dc1f2` (trial grace). Two small, unrelated changes recorded together because
> neither warrants its own file.

- **Status:** ✅ shipped
- **Migration:** none
- **PWA:** drag-and-drop UI + category dropdown shipped alongside

## 1. Estimate line order is now the master's, and the PDF agrees

`PATCH /api/estimates/{id}/items/order` (`EstimateItemsOrderRequest` →
`EstimateService.reorderItems`) persists an explicit order, so positions can be dragged **inside a
category and between categories**. Signed estimates are rejected like every other item write.

**The part that is easy to get wrong:** `EstimatePdfService` had to learn the same grouping.
Ordering that lives only in the app means the client receives a document arranged differently from
what the master arranged — and the master never sees it, because they are looking at the app. The
follow-up commit (`7c7aed9`) was exactly this: PDF grouping corrections plus tests.

So: **if you change ordering or grouping on one side, change it on the other.** The PDF is the
artefact the client keeps.

Tests: `EstimateServiceTest` (+108 lines) for the reorder semantics, `EstimatePdfServiceTest`
(+114 across the two commits) for the rendered grouping.

## 2. A trial no longer gets a grace period it cannot use

`BillingExpiryService` downgraded any lapsed PRO only after `planExpiresAt` **plus a grace
window**, so a late renewal charge kept PRO uninterrupted. Sensible for a paying subscription.

Wrong for a trial: **a trial has no card on file, so no charge can ever arrive.** Waiting for one
just handed out three extra free days, every time, to everyone — and quietly taught masters that
the paywall is soft.

The fix splits the query rather than the schedule: grace applies **only where a charge could still
land** (auto-renew on, card on file); everything else expires on time.
`UserRepository.findExpiredSubscriptions(now, graceCutoff)` now takes both cutoffs.

The downgrade stays **soft**, which is the product's downgrade principle: the plan flips to FREE,
everything the master created stays visible and downloadable, only *creating new* over the FREE
limits is blocked.

Tests: `BillingExpiryServiceTest` for the branch, and `BillingExpiryQueryIntegrationTest` because
the two-cutoff query is SQL — the exact thing a mocked repository cannot verify.

## Gotchas
- `reorderItems` is a full-list operation. A partial list is a data-loss shape; if you extend it,
  keep the "every item accounted for" check.
- Don't reintroduce a single grace cutoff in `findExpiredSubscriptions`. The two-argument shape is
  the fix, not an accident.
