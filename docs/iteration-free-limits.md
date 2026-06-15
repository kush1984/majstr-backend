# FREE plan limits — estimates-per-project + preemptive UI blocking

- **Status:** ✅ Backend + PWA complete. Backend `./gradlew build` pending a local
  run; PWA tested locally (vitest 29 green, `tsc -b` clean).
- **Migrations / deps:** none.
- **Goal:** Close the monetization hole where a FREE contractor could keep 2
  projects but spawn unlimited draft estimates, and fix the bad "allow → reject
  at the end" UX by blocking create actions preemptively.

## Final limit model

| Plan | Projects | Estimates per project |
|------|----------|-----------------------|
| FREE | 2 | 3 |
| PRO  | ∞ | ∞ |
| TEAM | ∞ | ∞ |

3 estimates/project covers legit econom/mid/premium variants but blocks
draft-spam abuse. Numbers live in `PlanConfig` (one edit to change).

## Backend (majstr-backend)

- `Limit.MAX_ESTIMATES_PER_PROJECT` added; `PlanConfig` maps it (FREE 3, PRO/TEAM
  -1). Same machinery/place as the existing `MAX_PROJECTS`.
- `LimitService.requireCanAddEstimate(userId, projectId)` — counts **all**
  estimates of the project (any status, via `EstimateRepository.countByProjectId`),
  throws `LimitExceededException` at the cap. Live count ⇒ deleting an estimate
  frees a slot; reopen reuses the same estimate so it doesn't count.
- `EstimateService.createForProject` calls it after `loadOwned`, before save.
- `GlobalExceptionHandler` now branches the limit response by type → **403** with
  a machine code (`ESTIMATE_LIMIT_REACHED` / `PROJECT_LIMIT_REACHED`) and a
  localized message (`error.limit.estimates` + uk plural `plural.estimates.*`).
  The plural helper was generalized (`pluralKey(prefix, n)`).
- `GET /api/plan/limits` (`PlanController` + `PlanLimitsResponse`,
  `LimitService.limitsFor`) returns `{plan, maxProjects, maxEstimatesPerProject}`
  (null = unlimited) so the UI can disable buttons without hardcoding numbers.
- The server check stays the source of truth (a direct API call for the 4th
  estimate still 403s).

## Frontend (majstr-pwa)

- `usePlanLimits()` (`GET /api/plan/limits`, gated on auth, 5-min stale) +
  `isAtLimit(count, max)` (null max ⇒ unlimited, fails open).
- **Objects:** `ProjectsPage` disables the "+ new" buttons and shows an
  `UpgradeBanner` once `projects.length >= maxProjects`. `NewEstimatePage` (the
  actual create screen — always makes a new project) guards too: disabled submit
  + banner + a toast if bypassed, so direct nav to `/new` is covered.
- **Estimates:** `ProjectDetailPage` disables "+ new estimate" and shows the
  banner once `estimates.length >= maxEstimatesPerProject`.
- `UpgradeBanner` — soft amber upsell (not a wall) linking to Profile (where the
  plan/upgrade lives). Brand tokens, mobile-first. Disabled buttons carry a
  tooltip (`limits.atLimitTooltip`).
- Backend's localized 403 message surfaces via the existing `toAppError` → toast
  if a limit is somehow hit.

## Tests

- Backend: `LimitServiceTest` (+5) estimate cap allow/reject/unlimited +
  `limitsFor`; `EstimateServiceTest` (+1) create blocked at cap doesn't save;
  `GlobalExceptionHandlerTest` (+1) `ESTIMATE_LIMIT_REACHED` 403 + uk plural,
  and the project path now asserts `PROJECT_LIMIT_REACHED`.
- PWA: `usePlanLimits.test.tsx` — `isAtLimit` rule (at/over/under cap, null =
  unlimited) + hook fetches when logged in, not when logged out. Full suite 29 green.

## How to verify

1. FREE + 2 projects → "+ new" disabled on ProjectsPage + upgrade banner; `/new`
   submit disabled too.
2. FREE + 3 estimates on a project → "+ new estimate" disabled + banner.
3. Delete an estimate on a full project → slot frees, button re-enables.
4. PRO → no limits anywhere (both numbers null).
5. Direct API `POST /api/projects/{id}/estimates` for the 4th on FREE → 403
   `ESTIMATE_LIMIT_REACHED`.
6. Sentry clean.

## Not changed / follow-up

- Existing project-limit behaviour preserved (now also carries a code).
- Exact numbers are config — to be validated with real contractors (open-questions).
