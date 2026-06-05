# Fix G — Refresh-token hardening audit

- **Status:** ✅ Backend code complete — `./gradlew build` + restart pending a
  local run (Gradle isn't reachable from the agent's sandbox).
- **Commit:** _(uncommitted at time of writing)_
- **Migrations:** none (logout, rotation, TTL, cleanup are all code/config).
- **Goal:** audit the existing refresh-token mechanism and fix the common
  weak spots — **not** a rewrite. Backend only; the PWA interceptor work
  (#1, #4) lands in the PWA repo.

The prompt's six points, each as *was → now*:

## 1. Parallel-request race on an expired token (frontend)
- **Was / is:** a frontend concern — the backend can't dedupe the client's
  concurrent `/refresh` calls.
- **Backend consequence (now documented):** rotation revokes the old token the
  instant it's used, so if the PWA fires N requests that all 401 and each calls
  `/refresh` with the same token, only the first succeeds; the rest present a
  now-revoked token → 401 → spurious logout. **The PWA interceptor must
  single-flight `/refresh`** (one in-flight promise, queue the rest, replay with
  the new token). Backend verified correct; flagged for the PWA.

## 2. Refresh-token rotation (already correct)
- **Was:** already implemented. `RefreshTokenService.rotate` looks the token up
  by hash, rejects unusable (revoked/expired), sets `revoked = true` on the old
  row, then issues a fresh pair.
- **Now:** unchanged, plus tests that pin it: a rotated-out (revoked) token is
  rejected, so a stolen old token is useless.

## 3. Logout invalidation (added)
- **Was:** no logout endpoint — a refresh token lived its full TTL after logout.
- **Now:** `POST /api/auth/logout` (public, refresh token in body) →
  `AuthService.logout` → `RefreshTokenService.revoke` marks that token revoked.
  Idempotent + silent (unknown/blank token = no-op) so it always succeeds even
  with an expired access token. Added to `SecurityConfig.PUBLIC_PATHS`. The PWA
  must call it on logout, not just clear local storage.

## 4. Frontend edge cases (frontend)
- Backend contract confirmed: a bad/expired/revoked refresh → `/refresh` returns
  **401** (`InvalidTokenException`), and `/login` `/refresh` `/logout` are public
  so they never sit behind the auth filter. The PWA must: on refresh failure do
  a clean logout + redirect to `/login` with no retry loop; never trigger the
  refresh interceptor for the auth endpoints themselves; persist and reuse the
  new token after a successful refresh.

## 5. Session length (product decision — confirmed)
- **Was:** 7 days, hardcoded.
- **Now:** **30 days**, configurable via env `REFRESH_TOKEN_TTL_DAYS`
  (`app.jwt.refresh-token-expiration-days`, default 30). Safe to keep long
  because tokens rotate on every use. Chosen for a daily work tool so the
  contractor isn't forced to log in weekly.

## 6. Expired-token cleanup (added)
- **Was:** `deleteExpired` query existed but nothing called it; tables grew
  unbounded (refresh tokens accumulate a revoked row per rotation).
- **Now:** `TokenCleanupService.purgeDeadTokens` — `@Scheduled` daily at 3am
  (cron `app.cleanup.tokens-cron`, overridable), `@EnableScheduling` on the app.
  Sweeps refresh tokens that are **expired or revoked**
  (`deleteExpiredOrRevoked`) and expired `email_verification_tokens` (the same
  accumulation, per the open-questions note) in one pass. Single-node; needs
  ShedLock if scaled out. Resolves the long-standing "background cleanup" open
  question.

## Tests

- `RefreshTokenServiceTest` — issue stores the **hash**, not the raw token, with
  the configured TTL; rotate revokes the old row and issues a new pair; a
  revoked token (rotated out) is rejected; an expired token is rejected; an
  unknown token is rejected; `revoke` marks the stored token revoked; unknown /
  blank tokens are no-ops.
- `TokenCleanupServiceTest` — the sweep calls both delete queries.
- `AuthServiceTest` — `logout` delegates to `RefreshTokenService.revoke`.

## Not changed (confirmed)

- Token hashing at rest (SHA-256), `/refresh` lookup-by-hash, and the rotation
  flow are unchanged — only logout, the env TTL, and the cleanup job were added.
- No migration: nothing in the schema changed.

## Build-verification note

Gradle can't run in the agent sandbox (loopback socket). Run `./gradlew build`
locally. No new external dependency. `@EnableScheduling` now active — confirm
the app starts and the daily job is registered (it logs only when it removes
rows).
