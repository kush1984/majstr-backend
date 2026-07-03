# Estimate churn monitoring (FREE delete→create loophole)

- **Status:** 🔨 Backend code complete; `./gradlew build` pending at the gate. V39
  migration **drill PASSED** (columns + truthful backfill hold).
- **Migration:** `V39__add_estimate_counters_to_projects.sql`.
- **Goal (user, 2026-07-03):** A FREE user can delete an estimate to free a slot and
  create another, sidestepping the "3 per object" concurrent cap. Decided **not to
  block** it (low severity — see open-questions "FREE estimate cap: delete→create
  loophole") but to **monitor**: show per-object estimates created / deleted in admin.

## What ships

- **V39:** `projects.estimates_created` + `estimates_deleted` (INT NOT NULL DEFAULT 0).
  Backfill `estimates_created` = the current live estimate count per object (truthful
  baseline; past deletions are unknowable → `deleted` starts 0).
- **Counters bumped** on every estimate lifecycle event, via race-safe bulk UPDATEs
  (`ProjectRepository.incrementEstimatesCreated/Deleted`, `@Modifying`): create
  (`EstimateService.createForProject` **and** `EstimateTemplateService.applyToProject`)
  and delete (`EstimateService.delete`). Never decremented — that's the point (churn
  is only visible if deletes accumulate).
- **Admin per-object view:** `AdminUserDetail.estimateChurn` (a list of
  `{object name, created, deleted}` from a single grouped query
  `findEstimateStatsByOwner`, no N+1) rendered as a table in the user-detail modal —
  a high `deleted` next to few concurrent estimates = someone churning the cap.

## Not changed / confirmed

- **No gate uses the counters** — purely observability. The concurrent FREE cap
  (`LimitService.requireCanAddEstimate`) is unchanged; the loophole stays open by
  decision, now watched.
- Record fan-out: `AdminUserDetail` gained a field → only `AdminUserService.detail`
  constructs it (updated). No other call site.
- Two services gained a `ProjectRepository` dependency → their Mockito tests get the
  `@Mock` (else the void increment NPEs); `AdminUserServiceTest.detail` needs no stub
  (Mockito returns an empty list for the new projection).

## Tests

- `EstimateServiceTest` — create verifies `incrementEstimatesCreated`, delete verifies
  `incrementEstimatesDeleted`. `EstimateTemplateServiceTest` gets the mock (applyToProject
  bumps created). Drill asserts the V39 columns + that `estimates_created` equals the
  live estimate count after backfill.

## Verify (after build green)

1. Admin → user detail → "Кошториси по об'єктах" table shows created/deleted per object.
2. Create then delete an estimate on an object → created +1, deleted +1 (churn visible).
