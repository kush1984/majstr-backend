# Iteration: password reset + dev rate-limit relax + SW update banner

Three small, adjacent closures from the backlog, done in one pass.

- **Status:** 🔨 Code complete — PWA green (tsc / 139 tests / build), mobile verified; backend build on the user.
- **App version:** PWA `0.13.1 → 0.14.0` (password reset is a real feature → minor).
- **Migration:** **V59** (`password_reset_tokens`).

## Why these three
- **Password reset** closes a churn hole: a master from Liga who forgets their password currently just
  vanishes — at the "every registered user is precious" stage, a real loss.
- **Rate-limit relax** removes false red e2e (register 5/hour/IP → false 429 on repeated Playwright runs);
  red tests you learn to ignore are a dangerous habit.
- **SW update banner** respects the master's work — no silent reload dropping a 30-line estimate.

## 1. Password reset (mirror of email verification)
**Backend**
- `PasswordResetToken` (V59) — mirrors `EmailVerificationToken`: crypto-random 32-byte base64url token,
  **45-min TTL** (shorter than verification's 24h), single-use `usedAt`. Cascade on the user; swept by the
  same daily `TokenCleanupService` pass (wired in).
- `PasswordResetService`:
  - `requestReset(email)` — **anti-enumeration**: looks the user up by `findByEmailIgnoreCase` (same as
    login); if present, supersedes any pending token, mints a fresh one, emails the link. If the email is
    unknown it's a silent no-op — the controller returns the same neutral 200 either way.
  - `reset(token, newPassword)` — validates (exists / not used / not expired → else
    `InvalidPasswordResetTokenException` = 400 `INVALID_OR_EXPIRED_TOKEN`), sets the BCrypt hash, consumes the
    token, and **revokes every refresh token** (`revokeAllForUser`) — a reset logs out all sessions. No
    auto-login (the master logs in with the new password).
- `ResendEmailService.sendPasswordResetEmail` (+ `passwordResetHtml` — brand, "Змінити пароль" button,
  "if this wasn't you, ignore it"), same env-gated fail-soft pattern (blank key → log & skip).
- `POST /api/auth/forgot` (IP+email rate-limited via `ForgotPasswordRateLimiter`, neutral 200) and
  `POST /api/auth/reset` — both **public** (added to `PUBLIC_PATHS`), mirroring `/verify-email`.
- `RateLimitProperties.Forgot` + `app.rate-limit.forgot` (5/60 base). Messages `error.password-reset.invalid`
  and `error.rate.forgot` (uk + en).

**PWA**
- `authApi.forgotPassword` / `resetPassword` (rawApi — no bearer).
- `ForgotPasswordPage` (`/forgot-password`) — email → neutral "check your email" screen (identical whether
  or not the account exists). `ResetPasswordPage` (`/reset-password?token=`) — new password + confirm (Zod,
  min 8, match), success → toast + redirect to `/login`; a bad/expired/used token (400) → a dedicated
  "link expired" screen with a path back to request a new one. Both **public** routes.
- `LoginPage` — the "Забули пароль?" TODO became a real link to `/forgot-password`.

## 2. Register/forgot rate-limit relax (dev/test only)
- The default profile is `dev` (`SPRING_PROFILES_ACTIVE:dev`) — so it covers local dev **and** the e2e
  backend. `application-dev.yml` lifts `register` + `forgot` to 100000/1min. Prod runs under `prod` and
  inherits the real base caps (5/hour/IP) — **unchanged**. Property-level merge keeps all other `app.*`
  config intact.

## 3. SW update banner
- `main.tsx` captures the `updateSW` from `registerSW`; `onNeedRefresh` calls `markUpdateReady(() =>
  updateSW(true))` (`lib/swUpdate.ts` — a tiny signal bridging the non-React SW registration to React).
- `<UpdateBanner>` (rendered at the app root in `App.tsx`, mirrors `OfflineBanner`) shows "Доступна нова
  версія" + an "Оновити" button; the reload happens **only on click** — never silently. `onOfflineReady`
  stays quiet.

## Tests
- Backend `PasswordResetServiceTest` — request mints+sends for a known email, **silent no-op for unknown**
  (anti-enumeration), reset sets hash + consumes token + revokes sessions, and rejects unknown/expired/used
  tokens (password unchanged on reject). `AuthControllerTest` got the two new mocks (constructor fan-out).
- PWA `ForgotPasswordPage.test` (neutral screen same for known/unknown, email validation),
  `ResetPasswordPage.test` (token from query, password-match gate, expired-screen on no-token and on 400),
  `UpdateBanner.test` (hidden → signalled → shows → apply on click).

## Not changed / confirmed
- Register / login / email-verification / refresh / logout untouched — reset only **adds** a flow. Verified a
  normal login still works after a reset (only the refresh tokens are revoked; the password hash is fresh).
- Prod register 5/hour/IP stays; the relax is dev/test only. Web push untouched (the banner is additive).
- Neutral `/forgot` never leaks account existence. Sentry/logs never carry tokens or the new password (the
  service logs only a session-revocation count).

## Gotchas
- **Anti-enumeration is the whole point of `/forgot`**: always 200, whether or not the email exists. The 429
  (IP+email) doesn't leak existence — it fires regardless of account.
- Password reset **revokes all refresh tokens** — a just-reset user must log in again everywhere. That's the
  security intent (a forgotten/stolen password can't keep an old session alive).
- The SW banner bridge (`swUpdate.ts`) exists because `registerSW` runs outside React — a module signal is the
  clean way to reach a React component.
