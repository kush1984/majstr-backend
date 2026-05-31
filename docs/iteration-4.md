# Iteration 4 — Subscriptions + admin

- **Status:** ✅ Done
- **Commit:** `89cec63` (Wire real plans, limits, admin dashboard, dev seed
  + ops log)
- **Migrations:** `V11__add_plan_role_last_active_to_users`,
  `V12__create_catalog_templates_table`, `V13__seed_catalog_templates`
- **Goal:** the monetization foundation — plans, a real feature/limit gate,
  starter catalogs on register, an admin role with a metrics dashboard, and
  seedable dev data.

## Chunks

### Plans + plan→feature matrix
- `Plan` enum (FREE / PRO / TEAM); `V11` adds `plan` to users.
- `PlanConfig` holds the plan→feature and plan→limit matrix in **data/config**
  (per project principle: what differs between users lives in data, not in
  `if (user == X)` code).

### DefaultFeatureGuard (replaces the scaffold)
- `DefaultFeatureGuard` implements the existing `FeatureGuard` interface — the
  no-op placeholder from iteration 3 is gone, interface unchanged.
- `Feature` gates (e.g. branded PDF, portal/sign, AI) checked through it;
  `FeatureNotAvailableException` → mapped error when a plan lacks a feature.

### LimitService (value-unit limit only)
- `LimitService` enforces the **project** limit per plan (FREE is capped;
  PRO/TEAM unlimited). `Limit` describes a limit; `LimitExceededException`
  carries the limit info to the error response.
- The **client** limit was intentionally removed (fix) — one clear limit on
  the unit of value beats several confusing ones. Limits are enforced on
  CREATE only; existing over-limit data stays editable (soft enforcement —
  see open questions on downgrade behaviour).

### Catalog templates + auto-copy on register
- `CatalogTemplate` entity + `V12` table + `V13` seed (global starter sets by
  trade).
- `CatalogTemplateService.seedForUser` copies the starter catalog into a new
  user's personal `CatalogItem`s at registration, so nobody starts empty.

### Roles + admin endpoints
- `Role` enum (USER / ADMIN); `V11` adds `role` to users.
- `/api/admin/**` requires ADMIN (`SecurityConfig`).
- `AdminMetricsController` → overview + growth metrics
  (`MetricsService`, `MetricsOverviewResponse`, `MetricsGrowthResponse`).
- `AdminUserController` → user list (`AdminUserSummary`, paged) and
  `PATCH /api/admin/users/{id}/plan` (`PlanUpdateRequest`) for manual plan
  changes. (No audit log yet — logged as an open question.)

### lastActiveAt tracking
- `V11` adds `last_active_at`; `LastActiveTracker` updates it with a ~5-minute
  throttle (process-local map — multi-instance caveat applies).

### Admin dashboard page
- `static/admin/index.html` — simple Chart.js dashboard consuming the metrics
  endpoints.

### Dev data seeder
- `DevDataSeeder` (`@Profile("dev")`, `ApplicationRunner`) — idempotent seed
  of three accounts (`test@majstr.dev` FREE, `pro@majstr.dev` PRO,
  `admin@majstr.dev` ADMIN) each with a starter catalog, plus demo
  clients/project/estimate for the basic user. Skips emails that already
  exist.

### Catalog reset
- `CatalogResetResponse` + endpoint to re-seed a user's catalog from
  templates.

### Tests
- `AdminAccessTest`, `DefaultFeatureGuardTest`, `LimitServiceTest`,
  `CatalogTemplateServiceTest`, `MetricsServiceTest`.

## Notes / gotchas
- "ops log" in the commit message refers to operational logging (dev seed,
  lastActiveAt) — there is no separate audit/ops-log table. A real audit log
  for sensitive admin actions is a tracked open question.
- Plan changes are admin-only and manual here; self-serve billing + a
  subscription status separate from plan (ACTIVE / GRACE_PERIOD / EXPIRED) is
  deferred (see SPEC §G1 and `docs/open-questions.md`).

---

## What comes next (not in this repo / not done)

- **Iteration 5 — PWA auth flow** and **Iteration 6 — PWA main screens** live
  in the separate `majstr-pwa` frontend repo, not here.
- **Fix A (backend, before step 6):** catalog categories + `User.trade` →
  `User.trades` (multi-valued), with a data-preserving Flyway migration.
  Planned, not yet started — when it lands, add `iteration-fix-a.md` here.
