# Iteration — self-serve 5-day PRO trial

Masters asked to try PRO before paying. A one-time, opt-in, no-card trial that
grants PRO for 5 days and reverts to FREE automatically — reusing the existing
billing plumbing so nothing new has to expire it.

## Decisions (with the user)

- **One-time, FREE-only, no card.** Only a master currently on FREE who has never
  used the trial can start it. No payment method, no auto-renew.
- **Reuse `plan` / `plan_expires_at`.** The trial is just PRO with an expiry; the
  daily `BillingExpiryService` (already downgrades lapsed subscriptions to FREE,
  soft) handles revert — no new scheduler. A trial gets the same grace window as a
  paid sub (harmless: there's no card to charge late).
- **Length is config.** `app.billing.trial-days` (env `BILLING_TRIAL_DAYS`,
  default 5).
- **Admin visibility.** `trial_started_at` is stamped once and never cleared, so
  admin can see exactly who tried PRO (and the trial can't be claimed twice).

## Backend

- **V53** — `ALTER TABLE users ADD COLUMN trial_started_at TIMESTAMPTZ` (NULL =
  never used).
- **`User.trialStartedAt`**; `BillingProperties.trialDays`; `application.yml`
  `trial-days`.
- **`BillingService.startTrial(userId)`** — fetches the user with trades in-session
  (the controller maps `UserResponse` after commit, open-in-view off), guards
  `trialStartedAt == null` and `plan == FREE`, then sets `trialStartedAt = now`,
  `plan = PRO`, `plan_expires_at = now + trialDays`. Throws
  `TrialNotAvailableException` → **409 `TRIAL_UNAVAILABLE`** otherwise.
- **`POST /api/billing/trial`** (authed) → returns the updated `UserResponse`.
- **`UserResponse.trialStartedAt`** — the PWA shows the button only when it's null
  and plan is FREE. **`AdminUserDetail.trialStartedAt`** + the admin user modal row
  "Пробний PRO".
- Messages: `error.trial.unavailable` (uk base + en).

## PWA (v0.9.2)

- `billingApi.startTrial()`; `UserResponse.trialStartedAt`.
- Profile plan card: an outline "Спробувати PRO 5 днів безкоштовно" button under
  the upgrade CTA, shown when `plan === 'FREE' && !trialStartedAt`. On click →
  `startTrial()`, writes the returned profile into the me cache, toasts
  `billing.trialStarted`.
- i18n `billing.tryTrial` / `billing.trialStarted` (uk + en).

## Tests

- `BillingServiceTest` — trial grants PRO + stamps for FREE-unused; rejects when
  already used; rejects when not FREE. `BillingProperties` fan-out (+trialDays) in
  the three billing test builders; `AuthControllerTest` UserResponse (+trialStartedAt).
- PWA `ProfilePage.trial.test.tsx` — button shows for FREE-unused and calls
  `startTrial`; hidden when trial used; hidden on PRO. Five UserResponse mock
  fixtures updated with `trialStartedAt`.

## Not done / deferred

- No email on trial start or T-1 "trial ending" reminder (could reuse the
  auto-renew reminder machinery later).
- No abuse guard beyond one-per-account (a new email = a new trial); acceptable for
  now.
