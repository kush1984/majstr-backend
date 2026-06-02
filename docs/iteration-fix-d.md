# Fix D — Soft email verification (Resend)

- **Status:** ✅ Code complete — `./gradlew test` + app restart (V19) pending a
  local run (Gradle wasn't reachable from the agent's sandbox)
- **Commit:** _(uncommitted at time of writing)_
- **Migrations:** `V19__add_email_verification`
- **Goal:** prove email ownership without blocking onboarding. A user registers
  and works immediately; the PWA shows a "confirm your email" banner, and only
  a client-facing action (creating a share link) requires a verified email.
  **Backend only** — the PWA banner/page is a separate task.
- **Resolves** the `Email verification on register` open question; the same
  `EmailService` unblocks `Password reset flow` and `Email notifications`.

## Model

- `User.emailVerified` (boolean). `EmailVerificationToken` (id, user, token,
  expiresAt, usedAt, createdAt; token UNIQUE).
- `V19` adds `users.email_verified` and the token table. **Existing users are
  set to `email_verified = TRUE`** so the new gate never locks out current /
  seed / test accounts; new registrations start `false`.

## Resend integration

- `EmailService` interface + `ResendEmailService` (Resend HTTP API,
  `https://api.resend.com/emails`). API key from env only (`RESEND_API_KEY`);
  when blank (local dev) it logs the verify link and skips sending.
- Send runs on a background thread (`@Async`, `@EnableAsync`) and swallows +
  logs any failure, so a mail problem never breaks registration.
- Ukrainian HTML email with a "Підтвердити email" button →
  `{APP_URL}/verify-email?token=...` (`APP_URL`, `EMAIL_FROM` via env).

## Endpoints

- `POST /api/auth/register` — after creating the user + seeding the catalog,
  issues a token and emails it (async, fail-soft).
- `POST /api/auth/verify-email { token }` — **public**; valid → `emailVerified
  = true`, `usedAt = now`. Missing / expired / used → 400 with a clear message
  (not 401, to avoid the PWA's refresh interceptor).
- `POST /api/auth/resend-verification` — authenticated; resends to the current
  user (no-op if already verified). Rate-limited **1 per 60s per user**
  (`VerificationEmailRateLimiter`, Bucket4j) → 429 + `Retry-After`.
- `GET /api/auth/me` — now returns `emailVerified` (banner).

## Soft gate

- Only `POST /api/estimates/{id}/share` requires a verified email:
  `ShareLinkService.create` throws `EmailNotVerifiedException` → **403 with
  `code: EMAIL_NOT_VERIFIED`** and the message "Підтвердіть email щоб надсилати
  кошториси клієнтам". Login and everything else stay open.
- `ErrorResponse` gained an optional `code` field (null-stripped) so the client
  can branch on `EMAIL_NOT_VERIFIED` rather than parsing the message.

## Not changed (confirmed)

- Login / general use is **not** gated — unverified users work normally.
- Existing accounts are verified by the migration, so nothing they could do
  before is now blocked.
- Pricing, limits, ownership checks untouched (share still goes through
  `loadOwned`, foreign → 403).

## Tests

- `EmailVerificationServiceTest` — issue saves a token + calls the mailer;
  verify activates the user; expired / used / unknown token → 400.
- `AuthServiceTest` — register seeds the catalog and issues the verification
  email; the new user comes back `emailVerified=false`.
- `ShareLinkServiceTest` — unverified owner → `EmailNotVerifiedException`
  (status not flipped); verified owner shares fine.
- `VerificationEmailRateLimiterTest` — second resend within the window blocked;
  users are independent.
- `AuthControllerTest` updated for the widened `UserResponse`.
- The Resend HTTP call and the DB-level wiring are best verified live (mailer
  is mocked in unit tests; Testcontainers is a tracked open question).

## Config / env

```
RESEND_API_KEY   (blank in dev → email logged & skipped, registration still works)
EMAIL_FROM       (default "Majstr <onboarding@resend.dev>")
APP_URL          (PWA base, default http://localhost:5173 — verify link target)
app.rate-limit.verification.cooldown-seconds: 60
```
Production needs a Resend-verified sending domain in `EMAIL_FROM`.
