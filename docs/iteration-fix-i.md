# Fix I — Code-review hardening (signed estimates, rate limits, perf)

- **Status:** ✅ Backend code complete — `./gradlew build` pending a local run
  (Gradle isn't reachable from the agent's sandbox).
- **Commit:** _(uncommitted at time of writing)_
- **Migrations:** V23 (estimates.version), V24 (performance indexes).
- **Source:** external code review (`docs/code-review.txt`, Cursor, 2026-06-09).
  This fix takes the confirmed HIGH items + the cheap perf wins; the rest was
  filed into `docs/open-questions.md` / the SPEC deploy checklist.

## 1. Signed estimates are immutable (HIGH 1 + 2)

- `EstimateService.update` / `addItem` / `addItemFromCatalog` / `updateItem` /
  `deleteItem` now call `requireNotSigned` → `EstimateSignedException` →
  **409 with code `ESTIMATE_SIGNED`** when `status == SIGNED`. The signature
  certifies exact items and totals; to revise the deal, create a new estimate.
- Manual `PUT` transition **to** `SIGNED` is rejected with 400
  (`InvalidEstimateStatusException`) — a signature only comes from the portal,
  so signer metadata is always real. DRAFT ↔ SENT ↔ REJECTED stay free-form.
- **Delete of a signed estimate stays allowed** (deliberate): it removes the
  record rather than corrupting it, and the whole project cascades on delete
  anyway, so blocking it would be security theater.
- `Estimate` gained a JPA `@Version` column (**V23**,
  `version BIGINT NOT NULL DEFAULT 0`) → two concurrent portal sign requests
  can't both win. The loser's commit throws
  `OptimisticLockingFailureException`, mapped to 409 in the advice. The
  pre-existing `SIGNED` → 409 CONFLICT check in `PublicEstimateService.sign`
  remains as the fast path.

## 2. Auth hardening (HIGH 5 + register limit)

- `LoginRequest.password` now `@Size(max = 100)` (mirrors register) — BCrypt
  cost scales with input length, so unbounded passwords were a cheap CPU attack.
- New `RegisterRateLimitFilter` + `RegisterRateLimiter`: caps
  `POST /api/auth/register` per **client IP** (no account exists yet, so IP is
  the only key; no body wrapper needed, unlike login). Config
  `app.rate-limit.register` — default **5/hour**. Wired in `SecurityConfig`
  before `UsernamePasswordAuthenticationFilter`, same as the other limiters.
  Curbs mass signups and verification-email (Resend) spend.

## 3. Performance (MEDIUM follow-ups)

- **Client N+1 on the project list:** `ProjectRepository` list methods now use
  `@EntityGraph(attributePaths = "client")` — the card DTO reads client
  id/name per row, which previously triggered one lazy SELECT per project.
- **V24 indexes:**
  - `idx_estimates_project_created (project_id, created_at DESC)` — serves the
    latest-estimate `DISTINCT ON` summary; the old single-column
    `idx_estimates_project_id` was dropped as redundant (the composite covers
    the prefix).
  - `idx_projects_owner_completed (owner_id, completed_at) WHERE status =
    'COMPLETED'` — dashboard "completed this month".
  - `idx_email_verification_tokens_expires_at` — daily token sweep.

## 4. Docs refreshed

- `CLAUDE.md`: stale "no actuator on classpath" and "no estimates domain yet"
  removed; intro scope updated; new state-machine section; error-code table
  extended (`ESTIMATE_SIGNED`, optimistic-lock 409, register 429, Sentry note).
- `README.md`: stale intro ("auth + foundation only"), single-`trade` register
  example, "7 days" refresh TTL fixed; health-check section added.
- `docs/open-questions.md`: six new OPEN items from the review (reuse
  detection, multiple share links, public files auth, register enumeration,
  I/O in @Transactional, MetricsService scans).
- SPEC deploy checklist (БЛОК 2): `server.forward-headers-strategy` behind the
  trusted proxy + disabling Swagger in prod.

## Not changed (confirmed)

- `PublicEstimateService.sign` flow, share-link semantics, portal payloads.
- Login rate limiting, refresh rotation, logout.
- Share (`POST .../share`) on a signed estimate stays allowed — the client can
  re-view what they signed.
- X-Forwarded-For handling in filters — unchanged; trusting it is a deploy
  concern (trusted reverse proxy + `forward-headers-strategy`), tracked in SPEC.

## Tests

- `EstimateServiceTest` (+6): update/addItem/updateItem/deleteItem blocked on
  SIGNED; manual →SIGNED rejected; SENT→REJECTED still allowed.
- `GlobalExceptionHandlerTest` (+2): `ESTIMATE_SIGNED` 409 with code;
  optimistic-lock 409 with generic message.
- `AuthControllerTest` (+1): 101-char login password → 400.
- `RegisterRateLimiterTest` (new): allows up to the cap, then 429 semantics
  (blocked + positive retry-after); the limit is per-IP.

## Build-verification note

Gradle can't run in the agent sandbox. Run `./gradlew build` locally — two new
migrations (V23, V24), no new dependency. Smoke checks after restart:
- `PUT /api/estimates/{id}` with `"status":"SIGNED"` → 400.
- Any item mutation on a signed estimate → 409 `ESTIMATE_SIGNED`.
- 6th registration from one IP within an hour → 429 with `Retry-After`.
