# Fix B — Dashboard metrics + project card summary

- **Status:** ✅ Code complete — `./gradlew test` + app restart (V17) pending
  a local run (Gradle wasn't reachable from the agent's sandbox)
- **Commit:** _(uncommitted at time of writing)_
- **Migrations:** `V17__add_completed_at_to_projects`
- **Goal:** give the PWA dashboard and project cards the counts/sums they
  need, all computed on the backend (BigDecimal, HALF_UP) with aggregate
  queries — the frontend only renders.

## 1. Dashboard metrics endpoint

`GET /api/dashboard/metrics` (authenticated, current user only) →
```
{ "activeProjects": <count IN_PROGRESS>,
  "pendingEstimates": <count estimates SENT across my projects>,
  "completedThisMonth": { "count": <projects COMPLETED this month>,
                          "totalAmount": <sum of their latest estimates> } }
```
- `DashboardController` → `DashboardService` → `DashboardMetricsResponse`
  (nested `CompletedThisMonth`).
- Four DB-side aggregates, no entities loaded:
  `ProjectRepository.countByOwnerIdAndStatus` (active),
  `EstimateRepository.countByProjectOwnerIdAndStatus` (pending),
  `ProjectRepository.countByOwnerIdAndStatusAndCompletedAtGreaterThanEqual`
  (completed count), and a native SUM for the completed amount.
- `/api/dashboard/**` is not in `SecurityConfig.PUBLIC_PATHS`, so it requires
  a JWT by default; the service is scoped by `principal.id()`, so a user only
  ever sees their own numbers.

## 2. Project card summary (project list)

`GET /api/projects` items gain two additive fields (nothing removed):
- `latestEstimateTotal` — total of the project's latest estimate (by
  `createdAt`), `BigDecimal`, or `null` if the project has no estimate.
- `estimateStatus` — that estimate's `DRAFT/SENT/SIGNED/REJECTED`, or `null`.

"Latest estimate" = most recent by `createdAt` (tie-broken by id), per the
task. The total sums each line `ROUND(quantity*unit_price, 2)` (HALF_UP),
identical to `EstimateService`, so the card matches the estimate screen.

`completedAt` is also exposed on `ProjectResponse` (see §3).

## 3. completed_at + status consistency

- **Status names already matched the frontend** — `ProjectStatus`
  IN_PROGRESS/COMPLETED and `EstimateStatus` SENT exist as-is. No enum change.
- `Project` had no completion timestamp (`updated_at` is re-stamped on any
  edit), so "completed this month" couldn't be dated. Added
  `projects.completed_at` (V17, nullable), backfilled from `updated_at` for
  existing COMPLETED projects as a one-time approximation.
- `ProjectService.updateStatus` is the single status-change path: it stamps
  `completedAt = now` when entering COMPLETED (only if unset) and clears it
  when leaving. `update` (name/address/…) never touches status, so editing a
  completed project does **not** shift `completedAt`. The metric counts by
  `completedAt`, never `updatedAt`.

## Efficiency (no N+1)

- The project list runs **one** aggregate query for the whole page
  (`EstimateRepository.findLatestEstimateSummaries(projectIds)` — Postgres
  `DISTINCT ON` to pick the latest estimate per project, `LEFT JOIN` items,
  one `GROUP BY`), then maps results in memory. A list of N projects = 2
  queries total (projects + summaries), not 1 + N.
- `get`/`update`/`updateStatus` reuse the same query with a single id.
- Dashboard metrics = 4 aggregate queries, zero entities materialized.

## Not changed (confirmed)

- Estimate/money logic untouched; the card/metric totals reuse the same
  per-line HALF_UP rounding, so they agree with the estimate screen.
- Limits and feature flags untouched.
- Ownership enforced everywhere: dashboard scoped by `principal.id()`;
  project reads/writes go through `loadOwned` (foreign → 403).

## Tests

- `DashboardServiceTest` — metric composition, null SUM → 0.00, month-start is
  the 1st at UTC midnight.
- `ProjectServiceTest` (new) — entering COMPLETED stamps `completedAt`,
  COMPLETED→COMPLETED keeps the original stamp, leaving COMPLETED clears it,
  a plain edit doesn't shift it, latest-estimate summary populated / null
  when absent, foreign project → 403.
- `ProjectControllerTest` updated for the widened `ProjectResponse`.
- Aggregate SQL correctness itself isn't unit-tested (needs Testcontainers —
  tracked open question); verify against the dev DB after restart.

## Known simplification

"This month" is the current calendar month in **UTC** (consistent with the
admin `MetricsService`). Near a month boundary this can differ from the
contractor's Kyiv-local month. Logged in `open-questions.md`.
