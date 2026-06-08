# Reliability — Health check + Sentry + error-handling hardening (backend)

- **Status:** ✅ Backend code complete — `./gradlew build` pending a local run
  (Gradle isn't reachable from the agent's sandbox).
- **Commit:** _(uncommitted at time of writing)_
- **Migrations:** none (no schema change).
- **Scope:** **Backend only.** The PWA half of the prompt (Sentry React, global
  Error Boundary, friendly network-error screens, client-portal resilience,
  TanStack Query retry policy) is a separate task in `majstr-pwa`.
- **Goal:** know about a failure *first* (before the user reports it), never
  leak internals to clients, and give external monitoring a real health signal.

## 1. Health check (Actuator)

- Added `spring-boot-starter-actuator`.
- `application.yml` → `management`:
  - `endpoints.web.exposure.include: health` — only `/actuator/health` is
    reachable; `env`, `beans`, `mappings`, etc. stay off (no info leak).
  - `endpoint.health.show-details: never` — anonymous callers get just
    `{"status":"UP"}` / `{"status":"DOWN"}`, no component breakdown (DB url,
    validation query). The **aggregate** status still folds in every indicator,
    so a DB outage flips the top-level status to `DOWN` without exposing details.
  - `endpoint.health.probes.enabled: true` — `/actuator/health/liveness` and
    `/actuator/health/readiness` for orchestrators.
  - `endpoint.health.group.readiness.include: readinessState,db` — readiness
    fails when the DB is unreachable, so a pod that can't serve is taken out of
    rotation.
  - `health.db.enabled: true` — explicit DB indicator (Actuator validates the
    `DataSource`).
- `SecurityConfig.PUBLIC_PATHS` now lists `/actuator/health` **and**
  `/actuator/health/**` so the probe sub-paths are reachable without auth.

**DB-down behavior:** stop Postgres → `GET /actuator/health` returns HTTP 503
with `{"status":"DOWN"}` (no details); bring it back → `200 {"status":"UP"}`.

## 2. Sentry (backend)

- Core SDK only: `io.sentry:sentry:8.43.1`. **No Spring Boot starter** — it is
  initialized manually so it stays env-gated and dodges Spring Boot 4
  auto-config surprises (CLAUDE.md *Gotchas*).
- `SentryProperties` (`app.sentry.*`, `isConfigured()` like `VapidProperties`)
  + `SentryInitializer` (`@PostConstruct`): blank `SENTRY_DSN` → logs
  "Sentry disabled" and never initializes, so the SDK is a no-op hub and every
  capture call does nothing (same fail-soft, env-gated pattern as Resend / VAPID).
- `setSendDefaultPii(false)` — Sentry never auto-collects PII (no email, body,
  headers, cookies). Environment tag from `SENTRY_ENVIRONMENT` (defaults to the
  active profile). `traces-sample-rate` configurable (default `0.0` = errors only).
- Capture point: `GlobalExceptionHandler.handleAny` (the 5xx/unhandled fallback)
  calls `reportToSentry`, attaching only the `endpoint` tag (`METHOD /uri`) and
  an opaque user id (from `UserPrincipal` if authenticated). 4xx handlers
  (validation, auth, not-found, rate-limit, …) do **not** report — only genuine
  5xx reach Sentry, so the inbox isn't drowned in expected client errors.
- `SentryProperties` registered in `MajstrApplication.@EnableConfigurationProperties`.

## 3. Global error handler — no-leak verification

- Already correct before this iteration and confirmed: `handleAny` returns a
  generic `"Internal server error"` (stack trace logged server-side only), and
  `spring.web.error.include-message/include-stacktrace: never` covers the
  container error path. Nothing changed in the mapping; only the Sentry capture
  was added.

## Not changed (confirmed)

- No new migration, no entity/DTO change, no change to existing status mappings
  (400/401/403/409/429 all unchanged).
- Existing `ErrorResponse` shape untouched.

## Tests

- `GlobalExceptionHandlerTest` (new) — a throwing controller via standalone
  MockMvc + the advice: asserts HTTP 500, `message: "Internal server error"`,
  correct `path`, and that the body does **not** contain the exception class,
  its message, or any `at com.majstr` stack frame. The Sentry capture inside the
  handler is a safe no-op (no DSN in tests).

## Build-verification note

Gradle can't run in the agent sandbox (loopback socket). Run `./gradlew build`
locally. Two new dependencies (`spring-boot-starter-actuator`,
`io.sentry:sentry:8.43.1`), no migration. To smoke-test Sentry end-to-end set a
real `SENTRY_DSN` and hit an endpoint that 500s — the event should appear in the
Sentry project tagged with the environment; with a blank DSN the log shows
"Sentry disabled".
