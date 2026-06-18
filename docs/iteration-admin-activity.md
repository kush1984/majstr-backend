# Admin panel — user activity & activation funnel

- **Status:** ✅ Backend + static admin page complete. `./gradlew build` (compile +
  Mockito tests) pending a local run; JPQL is validated at app **startup** (a
  smoke-start confirms it) — queries mirror the existing working
  `Estimate.project.owner.id` paths.
- **Migration:** none.
- **Goal:** See in the admin who actually works vs. who's stuck (activation
  funnel), without manual SQL.

## What shipped (all in majstr-backend — admin is `static/admin/index.html` + endpoints)

### 1. User list — activity columns
`GET /api/admin/users` now returns, per user: `emailVerified`, `clientsCount`,
`projectsCount`, `estimatesCount`, `signedEstimatesCount`. **No N+1:**
`AdminUserService.search` loads the page of users, then runs **one grouped query
per entity** over the page's ids (`countByOwnerIdIn`, `countByProjectOwnerIdIn`,
`...AndStatus(SIGNED)`) and folds them in (same pattern as the project-list
unread-question count). Columns added to the table: ✓ (email), Об., Кошт., Підп.

### 2. User detail (click a user → modal)
`GET /api/admin/users/{id}` → `AdminUserDetail`: email + verified, trades, plan,
role, registration, last activity, and the **per-master funnel** — clients,
projects, estimates with a status breakdown (draft/sent/signed/rejected),
hasShareLink, hasSigned, catalog size, hasLogo, last estimate date. Built from a
single grouped status query + a few per-owner counts.

### 3. Aggregate activation funnel (top of the admin)
`GET /api/admin/metrics/funnel` → `ActivationFunnelResponse`: registered →
verified email → ≥1 project → ≥1 estimate → shared → has signed. Each step is
**one aggregate COUNT** (`countByRole`, `countDistinctOwners`,
`countDistinctProjectOwners[ByStatus]`, ...) — no per-user loop. Masters only
(`ROLE_USER`; distinct-owner steps are naturally master-only). The page renders
each step's % of registrations.

### 4. PDF-bypass signal
A ⚠️ marker in the list for **active + unverified** masters
(`estimatesCount > 0 && !emailVerified`). This is exact, not heuristic: an
unverified master **can't share** (the portal share endpoint 403s them), so
"active + unverified" ⇒ "active without sharing" ⇒ likely PDF-only use that
never touches the client portal. A real per-user **PDF-download counter** doesn't
exist — logged in open-questions.

## Security & performance
- All endpoints are under `/api/admin/**` → `SecurityConfig` requires
  `ROLE_ADMIN` (existing gate; `AdminAccessTest` covers it). New endpoints inherit it.
- Aggregated/grouped queries throughout — no N+1. Lazy `trades` mapped within the
  read-only service tx (`@BatchSize`), `findWithTradesById` for the detail.
- Admin search reuses the Fix-K-safe `searchAdmin` (no `lower(bytea)`).

## Tests
- `MetricsServiceTest`: `activationFunnel` counts each step.
- `AdminUserServiceTest` (new): list folds per-user counts (missing rows → 0);
  detail builds the funnel + status breakdown + flags.

## Not changed / follow-up (open-questions)
- **PDF-download metric** — there's no counter for "generated/downloaded a PDF",
  so true PDF-bypass can only be inferred (active+unverified). A real counter
  (increment on `GET /api/estimates/{id}/pdf`) would make it precise.
- Existing `MetricsService` churn still uses `findAll()` (flagged separately);
  not touched here — the new funnel/detail code is all aggregate.

## Verify
1. Login as ADMIN → list shows email✓ + Об./Кошт./Підп. counts.
2. Click a user → modal with the full funnel + estimate status breakdown.
3. Funnel section at the top (registered → … → signed, with %).
4. Cross-check against a real user.
5. Non-admin gets 403; Sentry clean; no N+1.
